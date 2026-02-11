# Distributed Worker Coordination for a PostgreSQL-Backed DAG Workflow Engine

> **Purpose**: Technical research on distributed worker coordination patterns for a PostgreSQL-backed DAG workflow engine. Covers task claiming with SKIP LOCKED, heartbeat-based liveness, durable sleep, db-scheduler internals, Hatchet's gRPC architecture, graceful shutdown, and PostgreSQL leader election.

---

## Table of Contents

1. [Task Claiming with SKIP LOCKED](#1-task-claiming-with-skip-locked)
2. [Heartbeat-Based Liveness Detection](#2-heartbeat-based-liveness-detection)
3. [Durable Sleep Surviving Process Restarts](#3-durable-sleep-surviving-process-restarts)
4. [db-scheduler Cluster Coordination Internals](#4-db-scheduler-cluster-coordination-internals)
5. [Hatchet gRPC Workers vs PostgreSQL Polling](#5-hatchet-grpc-workers-vs-postgresql-polling)
6. [Graceful Shutdown During Rolling Deploys](#6-graceful-shutdown-during-rolling-deploys)
7. [Leader Election with PostgreSQL Advisory Locks](#7-leader-election-with-postgresql-advisory-locks)
8. [Recommendations for Our DAG Engine](#8-recommendations-for-our-dag-engine)

---

## 1. Task Claiming with SKIP LOCKED

### 1.1 The Problem: Multiple Workers Competing for Tasks

When multiple worker pods/instances poll the same `ready_queue` table, three approaches exist:

**Approach 1: Naive SELECT then UPDATE (broken)**
```sql
BEGIN;
SELECT * FROM jobs WHERE status = 'pending' LIMIT 1;
-- Worker processes... but another worker already selected the same row!
UPDATE jobs SET status = 'completed' WHERE id = ?;
COMMIT;
```
Multiple workers can select the same row simultaneously before any marks it complete. This causes duplicate execution.

**Approach 2: SELECT FOR UPDATE (correct but slow)**
```sql
BEGIN;
SELECT * FROM jobs WHERE status = 'pending' FOR UPDATE LIMIT 1;
-- Other workers block here, waiting for the lock
UPDATE jobs SET status = 'completed' WHERE id = ?;
COMMIT;
```
Workers serialize on the same row. Only one worker can run at a time. This creates a bottleneck.

**Approach 3: SELECT FOR UPDATE SKIP LOCKED (correct and fast)**
```sql
BEGIN;
SELECT * FROM jobs WHERE status = 'pending' FOR UPDATE SKIP LOCKED LIMIT 1;
-- Other workers skip this row and lock the NEXT available one
UPDATE jobs SET status = 'completed' WHERE id = ?;
COMMIT;
```
Workers never block each other. Each gets a different row. Available since PostgreSQL 9.5.

### 1.2 Production-Grade Claim Pattern

The recommended pattern combines SELECT, UPDATE, and DELETE in a single atomic CTE:

```sql
-- Claim up to $1 tasks from the ready queue
WITH claimed AS (
    SELECT id, workflow_run_id, task_name
    FROM ready_queue
    ORDER BY priority DESC, id ASC
    FOR UPDATE SKIP LOCKED
    LIMIT $1
)
DELETE FROM ready_queue
USING claimed
WHERE ready_queue.id = claimed.id
RETURNING claimed.workflow_run_id, claimed.task_name;
```

This pattern:
- Locks available rows (skipping locked ones)
- Removes them from the queue atomically
- Returns the claimed work to the caller
- The transaction holds the lock until commit/rollback

### 1.3 Alternative: UPDATE-Returning Pattern (Inferable's Approach)

For queues where tasks remain in the table (status-based):

```sql
UPDATE jobs SET
    status = 'running',
    remaining_attempts = remaining_attempts - 1,
    last_retrieved_at = now(),
    executing_machine_id = $machine_id
WHERE id IN (
    SELECT id FROM jobs
    WHERE status = 'pending'
      AND cluster_id = $cluster_id
    LIMIT $batch_size
    FOR UPDATE SKIP LOCKED
)
RETURNING id, target_fn, target_args;
```

### 1.4 Critical Indexing

Performance depends entirely on having the right index:

```sql
-- For the ready_queue dispatch pattern
CREATE INDEX idx_ready_queue_dispatch ON ready_queue (priority DESC, id ASC);

-- For status-based queues
CREATE INDEX idx_jobs_pending ON jobs (status, created_at)
    WHERE status = 'pending';
```

The partial index (`WHERE status = 'pending'`) is critical. Without it, PostgreSQL scans completed rows too, which degrades performance as the table grows.

### 1.5 Key Properties of SKIP LOCKED

| Property | Behavior |
|---|---|
| **Scope** | Row-level locks only. Table-level ROW SHARE lock is still taken normally. |
| **Consistency** | Intentionally provides an inconsistent view of data. By design for queue patterns. |
| **Crash safety** | Lock is tied to the transaction. Worker crash = transaction rollback = lock released. |
| **Batch support** | Combine with `LIMIT N` to grab batches. Locking stops once enough rows are returned. |
| **Ordering** | `ORDER BY` defines processing order (FIFO, priority-based, etc.). |
| **Visibility timeout** | Not built-in. Must implement separately via `visible_at` column or heartbeat. |

### 1.6 Visibility Timeout Alternative

Instead of heartbeats for stuck job detection, some systems use a declarative timeout:

```sql
-- When dequeuing, set a future visibility time
UPDATE jobs SET
    status = 'in_progress',
    visible_at = now() + interval '60 seconds',
    retry_count = retry_count + 1
WHERE id IN (
    SELECT id FROM jobs
    WHERE (status = 'pending')
       OR (status = 'in_progress' AND visible_at <= now())
    ORDER BY created_at
    LIMIT $batch_size
    FOR UPDATE SKIP LOCKED
)
RETURNING *;
```

If the worker crashes, the job becomes eligible again after `visible_at` expires. This is simpler than heartbeats but less responsive (must wait for the full timeout even if the worker died instantly).

---

## 2. Heartbeat-Based Liveness Detection

### 2.1 Core Concept

Workers periodically update a `last_heartbeat` timestamp on tasks they are executing. A separate process (or any worker) detects "dead" executions where the heartbeat has gone stale.

### 2.2 Schema Extensions for Heartbeat

```sql
ALTER TABLE tasks ADD COLUMN picked_by       TEXT;         -- worker identifier
ALTER TABLE tasks ADD COLUMN picked_at       TIMESTAMPTZ;  -- when claimed
ALTER TABLE tasks ADD COLUMN last_heartbeat  TIMESTAMPTZ;  -- last liveness signal
ALTER TABLE tasks ADD COLUMN version         BIGINT NOT NULL DEFAULT 0;  -- optimistic lock
```

### 2.3 Heartbeat Update Loop

Each worker runs a background thread/coroutine that periodically updates heartbeats:

```kotlin
// Worker heartbeat loop (runs every heartbeatInterval)
class HeartbeatManager(
    private val dataSource: DataSource,
    private val workerId: String,
    private val heartbeatInterval: Duration = Duration.ofMinutes(5),
    private val clock: Clock = Clock.systemUTC(),
) {
    private val currentExecutions = ConcurrentHashMap<TaskKey, Instant>()

    fun registerExecution(key: TaskKey) {
        currentExecutions[key] = Instant.now(clock)
    }

    fun unregisterExecution(key: TaskKey) {
        currentExecutions.remove(key)
    }

    // Called periodically on a background thread
    fun updateHeartbeats() {
        if (currentExecutions.isEmpty()) return

        val keys = currentExecutions.keys.toList()
        dataSource.connection.use { conn ->
            val sql = """
                UPDATE tasks
                SET last_heartbeat = now()
                WHERE (workflow_run_id, task_name) = ANY($1)
                  AND picked_by = $2
                  AND status = 'RUNNING'
            """.trimIndent()

            conn.prepareStatement(sql).use { stmt ->
                stmt.setArray(1, conn.createArrayOf("text", keys.map { it.toString() }.toTypedArray()))
                stmt.setString(2, workerId)
                val updated = stmt.executeUpdate()

                if (updated < keys.size) {
                    log.warn("Heartbeat: updated $updated of ${keys.size} executions")
                }
            }
        }
    }
}
```

### 2.4 Dead Execution Detection

A housekeeper process scans for stale heartbeats:

```sql
-- Find dead executions: running tasks whose heartbeat is stale
SELECT workflow_run_id, task_name, picked_by, last_heartbeat
FROM tasks
WHERE status = 'RUNNING'
  AND last_heartbeat < now() - ($1 * interval '1 second')
ORDER BY last_heartbeat ASC;
```

Where `$1` is `heartbeatInterval * missedHeartbeatsLimit` (e.g., 5 min * 6 = 30 min).

### 2.5 Dead Execution Recovery

```kotlin
fun detectAndRecoverDeadExecutions(maxAge: Duration) {
    val deadExecutions = findDeadExecutions(maxAge)
    for (execution in deadExecutions) {
        log.warn("Dead execution detected: ${execution.taskName} " +
                 "picked by ${execution.pickedBy}, " +
                 "last heartbeat ${execution.lastHeartbeat}")

        // Option A: Re-queue for retry
        if (execution.retryCount < execution.maxRetries) {
            rescheduleTask(execution)
        }
        // Option B: Mark as failed
        else {
            markTaskFailed(execution, "Dead execution: worker ${execution.pickedBy} stopped heartbeating")
        }
    }
}

private fun rescheduleTask(execution: TaskRecord) {
    // Atomically reset the task and re-enqueue
    dataSource.connection.use { conn ->
        conn.autoCommit = false
        try {
            // Reset task status
            conn.prepareStatement("""
                UPDATE tasks
                SET status = 'QUEUED',
                    picked_by = NULL,
                    picked_at = NULL,
                    last_heartbeat = NULL,
                    retry_count = retry_count + 1
                WHERE workflow_run_id = $1 AND task_name = $2
                  AND status = 'RUNNING'
            """).use { it.executeUpdate() }

            // Re-enqueue
            conn.prepareStatement("""
                INSERT INTO ready_queue (workflow_run_id, task_name, priority)
                VALUES ($1, $2, $3)
            """).use { it.executeUpdate() }

            conn.commit()
        } catch (e: Exception) {
            conn.rollback()
            throw e
        }
    }
}
```

### 2.6 Heartbeat Configuration Parameters

| Parameter | db-scheduler Default | Recommended | Rationale |
|---|---|---|---|
| `heartbeatInterval` | 5 minutes | 30-60 seconds | Faster detection of dead workers |
| `missedHeartbeatsLimit` | 6 | 4-6 | `interval * limit` = max time before recovery |
| `maxAgeBeforeConsideredDead` | 30 minutes | 2-5 minutes | How long before re-dispatching |
| `deadExecutionCheckInterval` | 5 minutes | 30 seconds | How often to scan for dead executions |

Trade-off: More frequent heartbeats mean faster detection but more database writes. For a system with 100 concurrent tasks and 30s heartbeat interval, that is ~200 UPDATE/minute -- negligible for PostgreSQL.

---

## 3. Durable Sleep Surviving Process Restarts

### 3.1 The Problem

In-process `delay()` or `Thread.sleep()` is lost when the process restarts. For durable sleep (e.g., "wait 7 days then send a reminder"), the sleep state must be persisted.

### 3.2 Pattern: Scheduled Execution Time in the Database

The fundamental pattern is to store the wake-up time as a row in the database, then have workers poll for tasks whose wake-up time has passed.

```sql
-- Schema for durable timers
CREATE TABLE scheduled_timers (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    workflow_run_id     UUID NOT NULL,
    task_name           TEXT NOT NULL,
    wake_at             TIMESTAMPTZ NOT NULL,
    status              TEXT NOT NULL DEFAULT 'WAITING',
        -- WAITING | FIRED | CANCELLED
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (workflow_run_id, task_name) REFERENCES tasks(workflow_run_id, task_name)
);

CREATE INDEX idx_timers_pending ON scheduled_timers (wake_at ASC)
    WHERE status = 'WAITING';
```

### 3.3 Timer Polling Loop

```kotlin
class DurableTimerPoller(
    private val dataSource: DataSource,
    private val queueRepo: ReadyQueueRepository,
    private val pollInterval: Duration = Duration.ofSeconds(10),
) {
    fun pollAndFireTimers() {
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                // Claim fired timers
                val sql = """
                    WITH fired AS (
                        SELECT id, workflow_run_id, task_name
                        FROM scheduled_timers
                        WHERE status = 'WAITING'
                          AND wake_at <= now()
                        ORDER BY wake_at ASC
                        FOR UPDATE SKIP LOCKED
                        LIMIT 100
                    )
                    UPDATE scheduled_timers
                    SET status = 'FIRED'
                    FROM fired
                    WHERE scheduled_timers.id = fired.id
                    RETURNING fired.workflow_run_id, fired.task_name;
                """.trimIndent()

                val firedTimers = conn.prepareStatement(sql).use { stmt ->
                    stmt.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) {
                                add(rs.getString("workflow_run_id") to rs.getString("task_name"))
                            }
                        }
                    }
                }

                // Enqueue the associated tasks
                for ((runId, taskName) in firedTimers) {
                    queueRepo.enqueue(QueueItem(
                        workflowRunId = UUID.fromString(runId),
                        taskName = taskName,
                        priority = 0,
                        enqueuedAt = Instant.now(),
                    ))
                }

                conn.commit()
            } catch (e: Exception) {
                conn.rollback()
                throw e
            }
        }
    }
}
```

### 3.4 Implementing Durable Sleep in the Workflow DSL

```kotlin
// Inside a DAG task definition, "sleep" becomes a scheduled timer + continuation task
fun WorkflowBuilder.sleepTask(
    name: String,
    duration: Duration,
    parents: List<TaskDefinition<*, *>> = emptyList(),
): TaskDefinition<Unit, Unit> {
    // Create a "timer" task that sets the wake_at time
    val timerTask = task<Unit, Unit>("$name-timer", dependsOn = parents) { ctx ->
        // When this task runs, it creates a durable timer row and suspends
        ctx.scheduleDurableTimer(duration)
        // The task itself completes, but the continuation is gated by the timer
    }

    // Create a "wake" task whose enqueue is controlled by the timer firing
    val wakeTask = task<Unit, Unit>("$name-wake", dependsOn = listOf(timerTask)) {
        // This is the continuation -- no-op, just marks resume point
    }

    return wakeTask
}
```

### 3.5 How Absurd (Armin Ronacher's Library) Does It

The Absurd library implements durable sleep within its step/checkpoint framework:

1. `ctx.sleep(durationSeconds)` creates a checkpoint with a wake-up time
2. The task is "suspended" -- its claim expires, and it returns to the pool
3. The worker polling loop checks for tasks whose sleep checkpoint has a `wake_at <= now()`
4. When found, the task is re-claimed and replayed; completed steps are loaded from cache, the sleep step returns immediately since the timer has fired, and execution continues with the next step

### 3.6 How db-scheduler Does It

db-scheduler stores the execution time directly on the task row:

```sql
-- Task scheduled for future execution
UPDATE scheduled_tasks
SET execution_time = now() + interval '7 days',
    picked = false,
    picked_by = NULL
WHERE task_name = $1 AND task_instance = $2;
```

The polling loop only fetches tasks where `execution_time <= now()`, so the task naturally sleeps until the right time. This survives restarts because the state is in the database.

### 3.7 How DBOS Does It

DBOS achieves durable sleep by combining PostgreSQL transactions with checkpointing. Each step within a transaction is checkpointed. A "sleep" step stores the target wake time as a checkpoint. On recovery, the system loads the checkpoint, checks if the wake time has passed, and either waits the remaining time or continues immediately.

### 3.8 Recommended Approach for Our DAG Engine

**Use the db-scheduler pattern**: Store `scheduled_at` time on the task or in a separate timers table. The polling loop picks up tasks whose time has arrived. This is the simplest pattern that survives arbitrary restarts:

```
1. Task calls durableSleep(7.days)
2. Engine sets task.scheduled_at = now() + 7 days, status = SLEEPING
3. Engine removes task from ready_queue (nothing to execute)
4. Timer poller runs every N seconds, finds tasks where scheduled_at <= now()
5. Timer poller re-enqueues those tasks into ready_queue
6. Worker claims task, sees it was sleeping, continues execution
```

Between steps 2 and 5, the process can restart any number of times. The state is entirely in PostgreSQL.

---

## 4. db-scheduler Cluster Coordination Internals

### 4.1 Architecture Overview

db-scheduler uses a single database table (`scheduled_tasks`) for all coordination. No external services, no advisory locks, no LISTEN/NOTIFY. Just the table and SQL.

### 4.2 The Scheduled Tasks Table

```sql
CREATE TABLE scheduled_tasks (
    task_name            TEXT NOT NULL,
    task_instance        TEXT NOT NULL,
    task_data            BYTEA,
    execution_time       TIMESTAMPTZ NOT NULL,
    picked               BOOLEAN NOT NULL DEFAULT false,
    picked_by            TEXT,
    last_success         TIMESTAMPTZ,
    last_failure         TIMESTAMPTZ,
    consecutive_failures INT DEFAULT 0,
    last_heartbeat       TIMESTAMPTZ,
    version              BIGINT NOT NULL DEFAULT 0,
    priority             SMALLINT,
    PRIMARY KEY (task_name, task_instance)
);
```

### 4.3 Two Polling Strategies

**Strategy 1: Fetch-and-Lock-on-Execute (Default)**

```
1. SELECT candidates WHERE execution_time <= now() AND picked = false
   (no row lock -- just a read)
2. For each candidate, try to UPDATE:
   UPDATE scheduled_tasks
   SET picked = true,
       picked_by = $scheduler_id,
       last_heartbeat = now(),
       version = version + 1
   WHERE task_name = $1 AND task_instance = $2
     AND version = $expected_version        -- optimistic lock!
   RETURNING *;
3. If UPDATE returns 0 rows, another instance claimed it first -> skip
4. If UPDATE returns 1 row, this instance won the race -> execute
```

The `version` column provides optimistic locking. No row locks are held during the SELECT, so there is no contention. The UPDATE with `WHERE version = $expected` ensures exactly one scheduler wins.

**Strategy 2: Lock-and-Fetch (SKIP LOCKED)**

```sql
-- Single query to fetch AND lock in one step
SELECT *
FROM scheduled_tasks
WHERE execution_time <= now()
  AND picked = false
ORDER BY priority DESC NULLS LAST, execution_time ASC
FOR UPDATE SKIP LOCKED
LIMIT $batch_size;
```

Fetched rows are already locked -- no second UPDATE needed to claim them. More efficient for high-throughput (>1000 executions/second) because it eliminates the separate claim UPDATE.

### 4.4 Heartbeat During Execution

While a task executes, db-scheduler periodically updates its heartbeat:

```java
// From Scheduler.java -- heartbeat update loop
void updateHeartbeats() {
    List<CurrentlyExecuting> currentlyProcessing = executor.getCurrentlyExecuting();
    for (CurrentlyExecuting executing : currentlyProcessing) {
        try {
            taskRepository.updateHeartbeat(
                executing.getExecution(),
                Instant.now(clock)
            );
        } catch (Exception e) {
            // Log warning, retry up to 3 times
            failedHeartbeats.incrementAndGet();
        }
    }
}
```

Default heartbeat interval: **5 minutes**. Configurable via `heartbeatInterval(Duration)`.

### 4.5 Dead Execution Detection

Runs on a separate housekeeper thread:

```java
void detectDeadExecutions() {
    Duration maxAge = heartbeatInterval.multipliedBy(missedHeartbeatsLimit);
    // e.g., 5min * 6 = 30 minutes

    Instant oldestAcceptableHeartbeat = clock.instant().minus(maxAge);

    List<Execution> deadExecutions = taskRepository.getDeadExecutions(
        oldestAcceptableHeartbeat
    );

    for (Execution dead : deadExecutions) {
        Task task = taskResolver.resolve(dead.taskInstance.getTaskName());
        if (task != null) {
            // Ask the task what to do about its dead execution
            task.getDeadExecutionHandler().deadExecution(
                dead, new ExecutionOperations(taskRepository, dead)
            );
        } else {
            // Task type no longer registered -- remove if old enough
            if (dead.lastHeartbeat.isBefore(deleteUnresolvedAfter)) {
                taskRepository.remove(dead);
            }
        }
    }
}
```

The `DeadExecutionHandler` interface lets each task type decide its own recovery strategy:

```java
// Built-in: ReviveDeadExecution -- reschedule to now()
public class ReviveDeadExecution implements DeadExecutionHandler {
    @Override
    public void deadExecution(Execution execution, ExecutionOperations ops) {
        ops.reschedule(
            new ExecutionComplete(execution, Instant.now(), Result.OK),
            Instant.now()  // reschedule to execute immediately
        );
    }
}
```

### 4.6 Scheduler Lifecycle

```
start()
  |
  +-- Start OnStartup tasks
  +-- Start dueExecutor (polling loop)
  |     |
  |     +-- poll for due executions
  |     +-- claim via optimistic lock or SKIP LOCKED
  |     +-- submit to thread pool
  |     +-- wait for next poll interval
  |
  +-- Start housekeeperExecutor
        |
        +-- updateHeartbeats() [every heartbeatInterval]
        +-- detectDeadExecutions() [every heartbeatInterval]

stop(Duration waitForExecuting, Duration waitForHousekeeper)
  |
  +-- Set shuttingDown flag
  +-- Interrupt dueExecutor if sleeping, or wait gracefully
  +-- Await in-flight tasks up to waitForExecuting
  +-- Shut down housekeeperExecutor last (heartbeats continue while tasks finish)
```

### 4.7 Key Configuration Parameters

| Parameter | Default | Purpose |
|---|---|---|
| `pollingInterval` | 10s | How often to check for due tasks |
| `threads` | 10 | Execution thread pool size |
| `heartbeatInterval` | 5m | How often to update heartbeat |
| `missedHeartbeatsLimit` | 6 | Missed beats before "dead" |
| `pollUsingLockAndFetch(upper, lower)` | off | Enable SKIP LOCKED strategy |
| `enableImmediateExecution()` | false | Wake scheduler for instant tasks |
| `registerShutdownHook()` | off | JVM shutdown hook for graceful stop |

---

## 5. Hatchet gRPC Workers vs PostgreSQL Polling

### 5.1 Hatchet's Architecture

Hatchet uses a three-tier architecture:

```
┌───────────────────────────────────────┐
│           Workers (your code)          │
│  Python / TypeScript / Go processes    │
│  Connect via bidirectional gRPC        │
│  Send heartbeats, receive dispatches   │
└──────────────┬────────────────────────┘
               │ gRPC (persistent connection)
┌──────────────▼────────────────────────┐
│           Engine (orchestrator)         │
│  Stateless, horizontally scalable      │
│  Task scheduling, queue management     │
│  Priority, rate limiting, fairness     │
│  Retry logic, timeout handling         │
└──────────────┬────────────────────────┘
               │ SQL
┌──────────────▼────────────────────────┐
│         PostgreSQL (state store)        │
│  Workflow definitions, task state       │
│  Queue tables, event log               │
│  SKIP LOCKED for engine coordination   │
└────────────────────────────────────────┘
```

**Workers do NOT poll PostgreSQL directly.** They maintain long-lived gRPC connections to the engine. The engine polls PostgreSQL using SKIP LOCKED and dispatches tasks to workers over gRPC.

### 5.2 Why Hatchet Chose gRPC Over Direct Polling

Hatchet discovered that direct PostgreSQL polling hits scaling limits:

> "At scale, every task in Hatchet corresponds to at minimum 5 PostgreSQL transactions. A simple Postgres queue utilizing FOR UPDATE SKIP LOCKED doesn't cut it."

The pathological case: many tasks in the backlog + many workers + workers long-polling at approximately the same time leads to CPU spikes and runaway database degradation.

The gRPC layer provides:
- **Reduced database contention**: Only the engine polls PostgreSQL, not N workers
- **Push-based dispatch**: Workers get tasks instantly via gRPC stream, no polling delay
- **Complex scheduling**: Rate limiting, concurrency control, and fairness algorithms are easier to implement in the engine than as SQL queries with contention
- **Latency**: 25-50ms task start times

### 5.3 Hatchet's Multi-Tenant Queue Optimization

For fair scheduling across tenants, Hatchet moved from read-time fairness to write-time fairness:

**Problem with read-time fairness:**
```sql
-- This doesn't work: FOR UPDATE is not allowed with window functions
SELECT *, ROW_NUMBER() OVER (PARTITION BY group_key ORDER BY created_at)
FROM tasks
WHERE status = 'pending'
FOR UPDATE SKIP LOCKED;
```

**Solution -- Write-time sequencing:**
- Partition the BIGINT ID space into blocks per tenant/group
- Assign task IDs at write time using: `task_id = group_id + blockLength * pointer(group_id)`
- Reading becomes a simple `ORDER BY id ASC` with SKIP LOCKED
- Read performance is constant regardless of queue size

### 5.4 PostgreSQL Polling: When It Works, When It Doesn't

**PostgreSQL polling works well for:**
- Single-process or small-cluster deployments (2-10 workers)
- Moderate throughput (<1000 tasks/second)
- Simple FIFO or priority-based ordering
- Systems where the workflow engine is embedded in the application

**PostgreSQL polling struggles with:**
- Large clusters (100+ workers) all polling the same table
- Very high throughput (>5000 tasks/second)
- Complex scheduling (per-tenant fairness, dynamic rate limiting)
- Cross-region deployments

### 5.5 Hybrid Approach: PostgreSQL Polling + LISTEN/NOTIFY

For medium-scale systems, combine polling with PostgreSQL's LISTEN/NOTIFY for immediate wake-up:

```sql
-- Trigger on ready_queue insert
CREATE OR REPLACE FUNCTION notify_new_task() RETURNS trigger AS $$
BEGIN
    PERFORM pg_notify('new_task', NEW.workflow_run_id::text || ':' || NEW.task_name);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER ready_queue_notify
    AFTER INSERT ON ready_queue
    FOR EACH ROW EXECUTE FUNCTION notify_new_task();
```

```kotlin
// Worker listens for notifications
fun startListening(connection: PgConnection) {
    connection.execSQLUpdate("LISTEN new_task")

    // Hybrid: poll every 10s as fallback, but wake immediately on NOTIFY
    while (running) {
        val notifications = connection.getNotifications(10_000) // 10s timeout
        if (notifications.isNotEmpty() || /* timeout elapsed */) {
            claimAndExecuteTasks()
        }
    }
}
```

Important caveat: LISTEN/NOTIFY notifications are not persistent. If a worker is disconnected when the notification fires, it is lost. Always use polling as a fallback. The LISTEN just minimizes latency.

### 5.6 Comparison Matrix

| Dimension | PostgreSQL Polling | gRPC Dispatch (Hatchet) |
|---|---|---|
| **Architecture** | Workers poll DB directly | Engine polls DB, pushes to workers |
| **Latency** | pollInterval/2 average | 25-50ms |
| **DB connections** | N workers * pool_size | Single engine pool |
| **Throughput ceiling** | ~1000-5000 tasks/sec | 20k+ tasks/min demonstrated |
| **Complexity** | Low (SQL only) | High (gRPC server, protocol) |
| **Failure detection** | Heartbeat in DB | gRPC connection health |
| **Multi-tenant fairness** | Hard (SQL limitations) | Centralized in engine |
| **Operational overhead** | None (embedded) | Engine deployment/scaling |
| **Best for** | Embedded library, small clusters | Platform service, large scale |

---

## 6. Graceful Shutdown During Rolling Deploys

### 6.1 The Problem

During a rolling deploy, Kubernetes sends SIGTERM to pods being replaced. Workers may be executing long-running tasks. We need to:

1. Stop claiming new work
2. Let in-progress tasks complete (or checkpoint)
3. Release any held resources
4. Exit cleanly before SIGKILL

### 6.2 Kubernetes Termination Sequence

```
1. Pod marked for deletion
2. Pod removed from Service endpoints (new traffic stops)
3. preStop hook executes (if configured)
4. SIGTERM sent to main process
5. terminationGracePeriodSeconds countdown begins (default: 30s)
6. SIGKILL sent if process hasn't exited
```

### 6.3 Worker Shutdown Implementation

```kotlin
class GracefulWorkerShutdown(
    private val claimLoop: ClaimLoop,
    private val heartbeatManager: HeartbeatManager,
    private val taskExecutor: ExecutorService,
    private val maxShutdownWait: Duration = Duration.ofSeconds(55),
    private val heartbeatContinuation: Duration = Duration.ofSeconds(5),
) {
    @Volatile
    private var shuttingDown = false

    init {
        // Register JVM shutdown hook for SIGTERM
        Runtime.getRuntime().addShutdownHook(Thread {
            shutdown()
        })
    }

    fun shutdown() {
        if (shuttingDown) return
        shuttingDown = true

        log.info("Graceful shutdown initiated")

        // Phase 1: Stop claiming new work (immediate)
        claimLoop.stop()
        log.info("Stopped claiming new tasks")

        // Phase 2: Wait for in-flight tasks to complete
        taskExecutor.shutdown()
        val completed = taskExecutor.awaitTermination(
            maxShutdownWait.toMillis(),
            TimeUnit.MILLISECONDS
        )

        if (!completed) {
            log.warn("In-flight tasks did not complete within ${maxShutdownWait}. " +
                     "They will be detected as dead and retried by another worker.")
            // Do NOT force-shutdown -- let heartbeat expiry handle it
        }

        // Phase 3: Stop heartbeating (after tasks finish)
        // Keep heartbeating for a few more seconds so completed task states
        // are not prematurely marked dead
        Thread.sleep(heartbeatContinuation.toMillis())
        heartbeatManager.stop()

        log.info("Graceful shutdown complete")
    }

    fun isShuttingDown(): Boolean = shuttingDown
}
```

### 6.4 Claim Loop with Shutdown Awareness

```kotlin
class ClaimLoop(
    private val queueRepo: ReadyQueueRepository,
    private val pollInterval: Duration = Duration.ofSeconds(1),
) {
    @Volatile
    private var running = true
    private val thread = Thread(::loop, "claim-loop")

    fun start() {
        thread.start()
    }

    fun stop() {
        running = false
        thread.interrupt()
    }

    private fun loop() {
        while (running) {
            try {
                val claimed = queueRepo.claim(batchSize = 10)
                for (task in claimed) {
                    if (!running) {
                        // Shutdown started between claim and dispatch
                        // Re-enqueue the task so another worker picks it up
                        queueRepo.enqueue(task)
                        continue
                    }
                    dispatch(task)
                }

                if (claimed.isEmpty()) {
                    Thread.sleep(pollInterval.toMillis())
                }
            } catch (e: InterruptedException) {
                // Expected during shutdown
                break
            }
        }
    }
}
```

### 6.5 Kubernetes Configuration

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: workflow-worker
spec:
  replicas: 3
  strategy:
    rollingUpdate:
      maxSurge: 1
      maxUnavailable: 0  # Never remove a pod before replacement is ready
  template:
    spec:
      terminationGracePeriodSeconds: 120  # Match your max task duration + buffer
      containers:
        - name: worker
          lifecycle:
            preStop:
              exec:
                command:
                  - /bin/sh
                  - -c
                  # Signal the app to stop claiming, then wait for drain
                  - "curl -s -X POST localhost:8080/admin/drain && sleep 110"
          readinessProbe:
            httpGet:
              path: /health/ready
              port: 8080
            periodSeconds: 5
          livenessProbe:
            httpGet:
              path: /health/live
              port: 8080
            periodSeconds: 10
```

### 6.6 Health Endpoint for Drain Awareness

```kotlin
@RestController
class HealthController(
    private val shutdown: GracefulWorkerShutdown,
) {
    @GetMapping("/health/live")
    fun liveness(): ResponseEntity<String> {
        // Always alive unless JVM is dying
        return ResponseEntity.ok("OK")
    }

    @GetMapping("/health/ready")
    fun readiness(): ResponseEntity<String> {
        // Not ready = stop sending new work
        return if (shutdown.isShuttingDown()) {
            ResponseEntity.status(503).body("DRAINING")
        } else {
            ResponseEntity.ok("READY")
        }
    }

    @PostMapping("/admin/drain")
    fun drain(): ResponseEntity<String> {
        shutdown.initiateGracefulDrain()
        return ResponseEntity.ok("DRAINING")
    }
}
```

### 6.7 Checkpoint Pattern for Long-Running Tasks

For tasks that may exceed the grace period, implement checkpoint-resume:

```kotlin
// Inside a long-running task
suspend fun processLargeBatch(ctx: TaskContext<BatchInput>): BatchOutput {
    val items = ctx.input.items
    val startIndex = ctx.checkpoint?.lastProcessedIndex ?: 0

    for (i in startIndex until items.size) {
        // Check if we should stop
        if (ctx.isShuttingDown) {
            // Save checkpoint so another worker can resume
            ctx.saveCheckpoint(BatchCheckpoint(lastProcessedIndex = i))
            throw TaskSuspendedException("Shutting down, checkpointed at index $i")
        }

        processItem(items[i])

        // Periodic checkpoint (every 10 items)
        if (i % 10 == 0) {
            ctx.saveCheckpoint(BatchCheckpoint(lastProcessedIndex = i + 1))
        }
    }

    return BatchOutput(processedCount = items.size)
}
```

### 6.8 Shutdown Sequence Summary

```
Time    Event
────    ─────
T+0s    Kubernetes sends SIGTERM (or preStop hook fires)
T+0s    Worker marks itself as draining
T+0s    Health/ready endpoint returns 503
T+0s    Claim loop stops -- no new tasks picked up
T+0s    Kubernetes removes pod from service endpoints
T+1-60s In-flight tasks complete normally
T+60s   Heartbeat manager sends final updates
T+65s   Process exits cleanly
T+120s  (Safety net) Kubernetes sends SIGKILL if still running
```

---

## 7. Leader Election with PostgreSQL Advisory Locks

### 7.1 Core Concept

PostgreSQL advisory locks are application-managed locks identified by an arbitrary integer. `pg_try_advisory_lock(key)` returns `true` if the lock was acquired, `false` if another session holds it. The lock is automatically released when the database connection closes.

### 7.2 Lock Types

| Function | Scope | Release |
|---|---|---|
| `pg_advisory_lock(key)` | Session | Explicit unlock or session end |
| `pg_try_advisory_lock(key)` | Session | Explicit unlock or session end |
| `pg_advisory_xact_lock(key)` | Transaction | Transaction end |
| `pg_try_advisory_xact_lock(key)` | Transaction | Transaction end |

For leader election, use **session-level** locks. The leader holds the lock as long as its database connection is alive.

### 7.3 Leader Election Implementation

```kotlin
class PostgresLeaderElection(
    private val dataSource: DataSource,
    private val lockId: Long = 839201,  // Arbitrary constant
    private val pollInterval: Duration = Duration.ofSeconds(10),
    private val onBecomeLeader: () -> Unit,
    private val onLoseLeadership: () -> Unit,
) {
    @Volatile
    private var isLeader = false
    private var leaderConnection: Connection? = null
    private val thread = Thread(::electionLoop, "leader-election")

    fun start() {
        thread.isDaemon = true
        thread.start()
    }

    fun stop() {
        thread.interrupt()
        releaseLeadership()
    }

    private fun electionLoop() {
        // Random initial delay to prevent thundering herd on startup
        Thread.sleep(Random.nextLong(0, pollInterval.toMillis()))

        while (!Thread.currentThread().isInterrupted) {
            try {
                if (!isLeader) {
                    tryAcquireLeadership()
                } else {
                    verifyLeadership()
                }
                Thread.sleep(pollInterval.toMillis())
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                log.warn("Leader election error", e)
                releaseLeadership()
                Thread.sleep(pollInterval.toMillis())
            }
        }
        releaseLeadership()
    }

    private fun tryAcquireLeadership() {
        // IMPORTANT: Use a dedicated connection, NOT from the pool
        // Connection poolers (HikariCP) return connections to the pool,
        // which would release the advisory lock!
        val conn = dataSource.connection
        conn.autoCommit = true  // Advisory locks work outside transactions

        val acquired = conn.prepareStatement(
            "SELECT pg_try_advisory_lock(?)"
        ).use { stmt ->
            stmt.setLong(1, lockId)
            stmt.executeQuery().use { rs ->
                rs.next() && rs.getBoolean(1)
            }
        }

        if (acquired) {
            leaderConnection = conn
            isLeader = true
            log.info("Acquired leadership (lock $lockId)")
            onBecomeLeader()
        } else {
            conn.close()  // Release connection if we didn't get the lock
        }
    }

    private fun verifyLeadership() {
        val conn = leaderConnection ?: return
        try {
            // Verify the connection is still alive and we still hold the lock
            val stillLeader = conn.prepareStatement("""
                SELECT count(*) FROM pg_locks
                WHERE pid = pg_backend_pid()
                  AND locktype = 'advisory'
                  AND objid = ?
            """).use { stmt ->
                stmt.setLong(1, lockId)
                stmt.executeQuery().use { rs ->
                    rs.next() && rs.getInt(1) > 0
                }
            }

            if (!stillLeader) {
                log.warn("Lost leadership -- lock no longer held")
                releaseLeadership()
            }
        } catch (e: SQLException) {
            log.warn("Connection error during leadership check", e)
            releaseLeadership()
        }
    }

    private fun releaseLeadership() {
        if (isLeader) {
            isLeader = false
            onLoseLeadership()
            log.info("Released leadership")
        }
        leaderConnection?.let {
            try {
                it.prepareStatement("SELECT pg_advisory_unlock(?)").use { stmt ->
                    stmt.setLong(1, lockId)
                    stmt.execute()
                }
                it.close()
            } catch (e: Exception) {
                // Connection may already be dead
            }
        }
        leaderConnection = null
    }
}
```

### 7.4 Important Caveats

**Connection pooling conflict**: Advisory locks are tied to the database session (connection). If you use a connection pool (HikariCP, PgBouncer), the lock is tied to the physical connection, not your application's logical session. When the pool recycles the connection, the lock may be released unexpectedly.

**Solution**: Use a **dedicated, non-pooled connection** for the leader election lock. This connection stays open as long as the instance is alive.

**PgBouncer in transaction mode**: Advisory locks do NOT work with PgBouncer in transaction pooling mode. Connections are swapped between clients after each transaction, releasing advisory locks. Use session pooling mode or connect directly to PostgreSQL for the leader election connection.

### 7.5 Use Cases in a DAG Workflow Engine

| Use Case | Lock ID | Purpose |
|---|---|---|
| Timer poller | `100001` | Only one instance polls for fired durable timers |
| Dead execution detector | `100002` | Only one instance scans for dead executions |
| Workflow cleanup (archival) | `100003` | Only one instance archives old workflow data |
| Schema migration | `100004` | Only one instance runs migrations on startup |

```kotlin
// Example: Only the leader runs the dead execution detector
val leaderElection = PostgresLeaderElection(
    dataSource = dataSource,
    lockId = 100002,
    pollInterval = Duration.ofSeconds(15),
    onBecomeLeader = {
        deadExecutionDetector.start()
        log.info("This instance is now responsible for dead execution detection")
    },
    onLoseLeadership = {
        deadExecutionDetector.stop()
        log.info("This instance is no longer responsible for dead execution detection")
    },
)
leaderElection.start()
```

### 7.6 Alternative: Two-Key Advisory Locks

For more granular locking, use the two-key variant:

```sql
-- Lock a specific workflow run for exclusive access
SELECT pg_try_advisory_lock($workflow_type_id, $workflow_run_hash);
```

This gives 2^64 possible lock combinations (two INT arguments). Useful for per-workflow or per-tenant locking.

### 7.7 Monitoring Advisory Locks

```sql
-- View all currently held advisory locks
SELECT l.pid,
       l.objid AS lock_id,
       a.application_name,
       a.client_addr,
       a.state,
       l.granted
FROM pg_locks l
JOIN pg_stat_activity a ON l.pid = a.pid
WHERE l.locktype = 'advisory'
ORDER BY l.objid;
```

---

## 8. Recommendations for Our DAG Engine

### 8.1 For the Current Scope (Single-Process, Embedded Library)

The current design (from the DAG workflow engine design doc) is explicitly single-process with "distributed workers across multiple processes" listed as a non-goal. The following patterns are still valuable even in single-process mode:

| Pattern | Use Now? | Rationale |
|---|---|---|
| SKIP LOCKED claim | **Yes** | Correct even with one process. Prevents self-contention with concurrent threads. Trivial to extend to multi-process later. |
| Heartbeat | **Yes** | Detects tasks stuck due to thread death, OOM, or deadlock within the process. |
| Durable sleep | **Later** | Listed as explicit non-goal. Add when needed. |
| Leader election | **No** | Not needed for single-process. |
| Graceful shutdown | **Yes** | Essential for clean Kubernetes deploys even with one replica. |

### 8.2 For Future Multi-Worker Extension

When distributed workers become necessary:

1. **Add worker registration table**: Track active workers with heartbeats
2. **Add `picked_by` column to tasks**: Know which worker is executing what
3. **Add leader election for housekeeping**: Timer polling, dead execution detection
4. **Keep PostgreSQL polling**: For the expected scale (<1000 tasks/sec), direct PostgreSQL polling with SKIP LOCKED is simpler and sufficient vs. adding a gRPC layer
5. **Add LISTEN/NOTIFY**: For low-latency dispatch without high-frequency polling

### 8.3 Minimum Implementation for Multi-Process Workers (~300 lines)

```
Component                        Lines    Priority
─────────────────────────────    ─────    ────────
SKIP LOCKED claim query           20      P0 (already have this)
Heartbeat update loop             40      P0
Dead execution detector           50      P0
Graceful shutdown handler         60      P0
Worker registration table         20      P1
Leader election (advisory lock)   80      P1
Durable timer table + poller      60      P1
LISTEN/NOTIFY optimization        30      P2
```

### 8.4 Schema Extensions for Multi-Worker Support

```sql
-- Worker registration
CREATE TABLE workers (
    id              TEXT PRIMARY KEY,       -- e.g., "worker-pod-abc123"
    hostname        TEXT NOT NULL,
    started_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_heartbeat  TIMESTAMPTZ NOT NULL DEFAULT now(),
    status          TEXT NOT NULL DEFAULT 'ACTIVE',
        -- ACTIVE | DRAINING | STOPPED
    metadata        JSONB
);

CREATE INDEX idx_workers_heartbeat ON workers (last_heartbeat)
    WHERE status = 'ACTIVE';

-- Add worker tracking to tasks
ALTER TABLE tasks ADD COLUMN picked_by       TEXT REFERENCES workers(id);
ALTER TABLE tasks ADD COLUMN picked_at       TIMESTAMPTZ;
ALTER TABLE tasks ADD COLUMN last_heartbeat  TIMESTAMPTZ;

-- Durable timers
CREATE TABLE durable_timers (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    workflow_run_id     UUID NOT NULL,
    task_name           TEXT NOT NULL,
    wake_at             TIMESTAMPTZ NOT NULL,
    status              TEXT NOT NULL DEFAULT 'WAITING',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (workflow_run_id, task_name)
        REFERENCES tasks(workflow_run_id, task_name)
);

CREATE INDEX idx_timers_pending ON durable_timers (wake_at ASC)
    WHERE status = 'WAITING';
```

---

## Sources

### PostgreSQL SKIP LOCKED
- [The Unreasonable Effectiveness of SKIP LOCKED in PostgreSQL](https://www.inferable.ai/blog/posts/postgres-skip-locked)
- [Implementing a Postgres job queue in less than an hour](https://aminediro.com/posts/pg_job_queue/)
- [Building a Simple yet Robust Job Queue System Using Postgres](https://www.danieleteti.it/post/building-a-simple-yet-robust-job-queue-system-using-postgresql/)
- [Queue System using SKIP LOCKED in Neon Postgres](https://neon.com/guides/queue-system)
- [Learning Notes #51 -- Postgres as a Queue using SKIP LOCKED](https://parottasalna.com/2025/01/11/learning-notes-51-postgres-as-a-queue-using-skip-locked/)

### Durable Execution
- [Absurd Workflows: Durable Execution With Just Postgres](https://lucumr.pocoo.org/2025/11/3/absurd-workflows/)
- [Why Postgres is a Good Choice for Durable Workflow Execution (DBOS)](https://www.dbos.dev/blog/why-postgres-durable-execution)
- [Absurd library (GitHub)](https://github.com/earendil-works/absurd)

### db-scheduler
- [db-scheduler GitHub](https://github.com/kagkarlsson/db-scheduler)
- [db-scheduler README](https://github.com/kagkarlsson/db-scheduler/blob/master/README.md)
- [Scheduler.java source](https://github.com/kagkarlsson/db-scheduler/blob/master/db-scheduler/src/main/java/com/github/kagkarlsson/scheduler/Scheduler.java)
- [ExecutePicked.java source](https://github.com/kagkarlsson/db-scheduler/blob/master/db-scheduler/src/main/java/com/github/kagkarlsson/scheduler/ExecutePicked.java)

### Hatchet
- [Hatchet Architecture](https://docs.hatchet.run/home/architecture)
- [Hatchet Workers](https://docs.hatchet.run/home/workers)
- [Hatchet Multi-Tenant Queues](https://hatchet.run/blog/multi-tenant-queues)
- [Hatchet GitHub](https://github.com/hatchet-dev/hatchet)
- [HN Discussion: Hatchet v1](https://news.ycombinator.com/item?id=43572733)

### PostgreSQL Advisory Locks & Leader Election
- [Using PostgreSQL Advisory Locks for Leader Election (Jeremy D. Miller)](https://jeremydmiller.com/2020/05/05/using-postgresql-advisory-locks-for-leader-election/)
- [Leader Elections with Postgres Advisory Locks (Ramit Mittal)](https://ramitmittal.com/blog/general/leader-election-advisory-locks)
- [Orchestrating Distributed Tasks with PostgreSQL Advisory Locks (Leapcell)](https://leapcell.io/blog/orchestrating-distributed-tasks-with-postgresql-advisory-locks)
- [PostgreSQL Advisory Locks Explained (Flavio Del Grosso)](https://flaviodelgrosso.com/blog/postgresql-advisory-locks)
- [How to Use Advisory Locks in PostgreSQL (OneUptime)](https://oneuptime.com/blog/post/2026-01-25-use-advisory-locks-postgresql/view)
- [Leader Election with PostgreSQL Advisory Locks (Sylvain Kerkour)](https://kerkour.com/postgresql-leader-election-advisory-lock)

### Graceful Shutdown
- [How to Implement Graceful Shutdown (Kubernetes Recipe Book)](https://kubernetes.recipes/recipes/deployments/graceful-shutdown/)
- [Kubernetes Best Practices: Terminating with Grace (Google Cloud)](https://cloud.google.com/blog/products/containers-kubernetes/kubernetes-best-practices-terminating-with-grace)
- [Graceful Shutdown Handlers for Long-Running Kubernetes Processes (OneUptime)](https://oneuptime.com/blog/post/2026-02-09-graceful-shutdown-handlers/view)
- [Application Lifecycle Management in Kubernetes (trivago)](https://tech.trivago.com/post/2021-06-09-application-lifecycle-management-kubernetes)
- [Graceful Cleanup with Java Shutdown Hooks in Kubernetes](https://dkbalachandar.wordpress.com/2025/08/08/graceful-cleanup-with-java-shutdown-hooks-in-a-kubernetes-job/)

### LISTEN/NOTIFY
- [Using Postgres as a Message Queue (JVM Advent)](https://www.javaadvent.com/2022/12/using-postgres-as-a-message-queue.html)
- [Using Postgres as a Task Queue (Perfects Engineering)](https://blog.perfects.engineering/using_postgres_as_task_queue/)
