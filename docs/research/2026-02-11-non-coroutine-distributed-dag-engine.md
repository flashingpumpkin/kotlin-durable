# Non-Coroutine Distributed DAG Workflow Engine: Full Design Research

> **Purpose**: This document provides the complete technical foundation for building a Hatchet-style DAG workflow engine in Kotlin **without coroutines**, targeting a distributed multi-pod deployment with multi-tenant fairness and durable sleep. It supersedes the non-coroutine section of the prior design document by incorporating three requirements that were previously out of scope: multi-tenant fair queuing, distributed workers across multiple processes, and durable sleep surviving process restarts.

---

## Table of Contents

1. [Revised Requirements](#1-revised-requirements)
2. [Architecture Overview](#2-architecture-overview)
3. [Multi-Tenant Fair Queuing](#3-multi-tenant-fair-queuing)
4. [Distributed Worker Coordination](#4-distributed-worker-coordination)
5. [Durable Sleep Surviving Restarts](#5-durable-sleep-surviving-restarts)
6. [Core Interfaces (Non-Coroutine)](#6-core-interfaces-non-coroutine)
7. [DAG Runner Implementation](#7-dag-runner-implementation)
8. [PostgreSQL Schema](#8-postgresql-schema)
9. [Graceful Shutdown & Rolling Deploys](#9-graceful-shutdown--rolling-deploys)
10. [In-Memory Testing Without Coroutines](#10-in-memory-testing-without-coroutines)
11. [Resource Budget & Scaling](#11-resource-budget--scaling)
12. [Implementation Order](#12-implementation-order)
13. [Dependency List](#13-dependency-list)

---

## 1. Revised Requirements

### What Changed From the Original Design

| Requirement | Original Status | New Status | Impact |
|---|---|---|---|
| **Multi-tenant fairness** | Explicitly out of scope | **Required** — customers have different load characteristics | Need Hatchet-style fair queuing or equivalent |
| **Distributed workers** | Out of scope (single-process library) | **Required** — multiple worker pods / Cloud Run instances | Need cluster coordination, distributed task claiming, leader election |
| **Durable sleep** | Deferred ("can be added later") | **Required** — rolling deploys 24/7 | Need PostgreSQL-backed timers, recovery on restart |
| **Coroutines** | Recommended (Option A) | **Not used** — team has no experience | Use `ExecutorService`, `CompletableFuture`, `ScheduledExecutorService`, Java 21 virtual threads |

### Must-Have Requirements (Updated)

| Requirement | Rationale |
|---|---|
| **Hatchet-style DAG orchestration** | Workflows defined as DAGs of typed tasks with explicit dependencies. No determinism constraints on user code. |
| **PostgreSQL-only storage** | No Redis, Kafka, RabbitMQ. Single dependency beyond the JVM. |
| **Multi-tenant fair queuing** | Tenants with different load characteristics must get fair access to worker capacity. One tenant enqueueing 10,000 tasks must not starve a tenant with 1 task. |
| **Distributed workers** | Multiple pods/instances claim and execute tasks concurrently. No single point of failure for workers. |
| **Durable sleep** | `sleep(Duration.ofHours(24))` survives process restarts, rolling deploys, and pod evictions. Wake-up time persisted in PostgreSQL. |
| **At-least-once delivery** | Tasks are idempotent. Failed tasks are retried. Completed task outputs are cached. |
| **Fully testable in-memory** | Acceptance tests run without PostgreSQL. Virtual time control. Tests complete in milliseconds. |
| **No coroutine dependency** | Standard Java/Kotlin concurrency: `ExecutorService`, `CompletableFuture`, `ScheduledExecutorService`. Team has no coroutine experience. |

### Non-Goals

- gRPC-based worker dispatch (we use PostgreSQL polling)
- Deterministic replay (DAG orchestration, not journal replay)
- Dynamic workflow modification after creation
- Sub-10ms inter-step latency (polling-based, target ~100-500ms)

---

## 2. Architecture Overview

### 2.1 Deployment Topology

```
┌─────────────────────────────────────────────────────────────────────┐
│                        PostgreSQL                                    │
│                                                                     │
│  workflow_runs  │  tasks  │  ready_queue  │  task_events            │
│  durable_timers │  tenant_groups  │  task_addr_ptrs                 │
└──────────────────────────┬──────────────────────────────────────────┘
                           │
        ┌──────────────────┼──────────────────┐
        │                  │                  │
   ┌────▼─────┐      ┌────▼─────┐      ┌────▼─────┐
   │  Pod A    │      │  Pod B    │      │  Pod C    │
   │           │      │           │      │           │
   │ ┌───────┐ │      │ ┌───────┐ │      │ ┌───────┐ │
   │ │Poller │ │      │ │Poller │ │      │ │Poller │ │
   │ │Thread │ │      │ │Thread │ │      │ │Thread │ │
   │ └───┬───┘ │      │ └───┬───┘ │      │ └───┬───┘ │
   │     │     │      │     │     │      │     │     │
   │ ┌───▼───┐ │      │ ┌───▼───┐ │      │ ┌───▼───┐ │
   │ │Worker │ │      │ │Worker │ │      │ │Worker │ │
   │ │Pool   │ │      │ │Pool   │ │      │ │Pool   │ │
   │ │(10)   │ │      │ │(10)   │ │      │ │(10)   │ │
   │ └───────┘ │      │ └───────┘ │      │ └───────┘ │
   │           │      │           │      │           │
   │ ┌───────┐ │      │           │      │           │
   │ │Leader │ │      │ (standby) │      │ (standby) │
   │ │(timer │ │      │           │      │           │
   │ │ +house│ │      │           │      │           │
   │ │ keep) │ │      │           │      │           │
   │ └───────┘ │      │           │      │           │
   └───────────┘      └───────────┘      └───────────┘
```

**Key design decisions:**

1. **Every pod polls the ready queue** — all pods are equal task executors using `SKIP LOCKED`
2. **One pod is the leader** — runs the timer poller (durable sleep) and housekeeper (dead execution detection) via PostgreSQL advisory lock
3. **Leader election is automatic** — if the leader dies, another pod acquires the advisory lock
4. **No gRPC** — all coordination happens through PostgreSQL (simpler than Hatchet's approach, sufficient for <1000 tasks/sec)

### 2.2 Component Responsibilities

| Component | Runs On | Responsibility |
|---|---|---|
| **Task Poller** | Every pod | Claims ready tasks from `ready_queue` via `SKIP LOCKED`, dispatches to worker pool |
| **Worker Pool** | Every pod | Executes tasks, writes outputs, resolves child dependencies, enqueues newly-ready children |
| **Timer Poller** | Leader only | Scans `durable_timers` for expired timers, enqueues wake-up tasks into `ready_queue` |
| **Housekeeper** | Leader only | Detects dead executions (stale heartbeats), re-enqueues or fails them |
| **Heartbeat Thread** | Every pod | Updates `last_heartbeat` on running tasks owned by this pod |
| **DAG Trigger** | Any pod (API call) | Creates workflow run + all task records + enqueues root tasks |

### 2.3 Why No Coroutines — And What We Use Instead

| Coroutine Feature | Non-Coroutine Equivalent | Trade-off |
|---|---|---|
| `delay()` for retry backoff | `ScheduledExecutorService.schedule()` | Consumes a timer slot, not a thread |
| `Semaphore(permits)` for concurrency | `java.util.concurrent.Semaphore` | Identical semantics |
| Structured cancellation | `Future.cancel()` + `ExecutorService.shutdownNow()` | More manual, but sufficient |
| `Channel` for producer-consumer | `BlockingQueue` or `CompletableFuture` | Well-understood Java patterns |
| Virtual time in tests | `FakeClock` + manual event pump | More verbose but functional |
| `suspend fun` interfaces | Regular functions (blocking or returning `CompletableFuture`) | No `runBlocking` needed |

**Java 21 virtual threads** are available as an optimization. When configured, the worker pool uses `Executors.newVirtualThreadPerTaskExecutor()` instead of a fixed thread pool. This gives us coroutine-like resource efficiency (sleeping virtual threads consume ~5-10 KiB, not ~1 MB stack) without requiring coroutine knowledge. However, the engine does not depend on virtual threads — it works with platform threads on Java 21+ without configuration changes.

---

## 3. Multi-Tenant Fair Queuing

### 3.1 The Problem

Customer A enqueues 1 task. Customer B enqueues 10,000 tasks. Without fairness, a FIFO queue processes all 10,000 of B's tasks before A's single task. With multiple customers having different load characteristics, this is unacceptable.

### 3.2 Approach: Hatchet's Block-Based ID Encoding

We adopt Hatchet's block-based sequencing algorithm because it makes **reads trivially cheap** (just `ORDER BY id ASC`) by encoding the fairness schedule into the ID space at write time. No window functions, no `PARTITION BY`, no degradation as queue depth grows.

**How it works:**

The BIGINT ID space is partitioned into contiguous blocks of size `BLOCK_LENGTH` (default: 1,048,576):

```
Block 0:  IDs [0,             1,048,575]
Block 1:  IDs [1,048,576,     2,097,151]
Block 2:  IDs [2,097,152,     3,145,727]
...
```

Each tenant is assigned a sequential group ID. Within each block, the tenant's position is its group ID. The formula:

```
task_queue_id = tenant_group_id + (BLOCK_LENGTH * block_pointer)
```

**Example with 3 tenants:**

```
Tenant A (group_id=0): tasks at IDs 0, 1048576, 2097152, 3145728, ...
Tenant B (group_id=1): tasks at IDs 1, 1048577, 2097153, 3145729, ...
Tenant C (group_id=2): tasks at IDs 2, 1048578, 2097154, 3145730, ...
```

Reading in ascending ID order: `0(A), 1(B), 2(C), 1048576(A), 1048577(B), 1048578(C), ...`

**Natural round-robin without any computation at read time.**

### 3.3 Handling Load Imbalance

When Tenant B enqueues 10,000 tasks and Tenant A enqueues 1:

```
Tenant A: ID = 0 + 1048576 * 0 = 0          (1 task in block 0)
Tenant B: ID = 1 + 1048576 * 0 = 1          (block 0)
           ID = 1 + 1048576 * 1 = 1048577    (block 1)
           ID = 1 + 1048576 * 2 = 2097153    (block 2)
           ...
```

Processing order: `0(A), 1(B), 1048577(B), 2097153(B), ...`

Tenant A's single task is processed after Tenant B's **first** task, not after all 10,000. This is deterministic round-robin.

### 3.4 Concurrency Limiting Per Tenant

The dequeue query can limit the search window to `maxConcurrency * BLOCK_LENGTH` IDs:

```sql
WITH
    min_id AS (
        SELECT COALESCE(min(id), 0) AS min_id FROM ready_queue
    ),
    claimed AS (
        SELECT rq.id, rq.workflow_run_id, rq.task_name
        FROM ready_queue rq
        WHERE rq.id >= (SELECT min_id FROM min_id)
          AND rq.id < (SELECT min_id FROM min_id) + $1::int * 1048576
        ORDER BY rq.id ASC
        FOR UPDATE SKIP LOCKED
        LIMIT $2
    )
DELETE FROM ready_queue USING claimed
WHERE ready_queue.id = claimed.id
RETURNING claimed.*;
```

With `maxConcurrency = 4`, only 4 blocks are scanned. Each tenant gets at most 4 tasks in-flight before other tenants are served. This prevents a single tenant from monopolizing the worker pool.

### 3.5 State Management

Two tables track the fairness state:

```sql
-- Sequential group IDs for tenants
CREATE TABLE tenant_groups (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   TEXT UNIQUE NOT NULL,
    block_addr  BIGINT NOT NULL DEFAULT 0  -- last block assigned to this tenant
);

-- Global consumption frontier (singleton row)
CREATE TABLE task_addr_ptrs (
    max_assigned_block_addr BIGINT NOT NULL DEFAULT 0,
    onerow_id BOOL PRIMARY KEY DEFAULT TRUE,
    CONSTRAINT onerow_uni CHECK (onerow_id)
);

INSERT INTO task_addr_ptrs (max_assigned_block_addr) VALUES (0);
```

### 3.6 Enqueue Operation

```sql
-- Atomically: create or update tenant group, compute fair ID, insert queue item
WITH group_update AS (
    INSERT INTO tenant_groups (tenant_id, block_addr)
    VALUES ($1, (SELECT max_assigned_block_addr FROM task_addr_ptrs))
    ON CONFLICT (tenant_id)
    DO UPDATE SET block_addr = GREATEST(
        tenant_groups.block_addr + 1,
        (SELECT max_assigned_block_addr FROM task_addr_ptrs)
    )
    RETURNING id, block_addr
)
INSERT INTO ready_queue (id, workflow_run_id, task_name, tenant_id, enqueued_at)
VALUES (
    (SELECT id FROM group_update) + 1048576 * (SELECT block_addr FROM group_update),
    $2, $3, $1, now()
)
RETURNING *;
```

### 3.7 Constraints and Limits

- **Maximum distinct tenants**: must be < `BLOCK_LENGTH` (1,048,576). More than sufficient for most SaaS applications.
- **BIGINT space**: supports ~8.8 × 10^12 total task enqueues before exhaustion. At 10,000 tasks/sec, this lasts ~28 years.
- **`max_assigned_block_addr` update**: the leader's housekeeper periodically updates this pointer based on the highest consumed task ID, preventing new tenants from being buried behind unconsumed blocks.

### 3.8 Alternative Considered: Per-Tenant Slot Limiting

A simpler alternative is to limit concurrent tasks per tenant without true round-robin:

```sql
WITH running_per_tenant AS (
    SELECT tenant_id, count(*) AS running FROM tasks
    WHERE status = 'RUNNING' GROUP BY tenant_id
),
eligible AS (
    SELECT rq.* FROM ready_queue rq
    LEFT JOIN running_per_tenant rpt ON rq.tenant_id = rpt.tenant_id
    WHERE COALESCE(rpt.running, 0) < $1  -- max per tenant
    ORDER BY rq.enqueued_at ASC
    FOR UPDATE SKIP LOCKED
    LIMIT $2
)
DELETE FROM ready_queue USING eligible WHERE ready_queue.id = eligible.id
RETURNING eligible.*;
```

**Trade-off**: Simpler to implement, but does not provide round-robin interleaving. A high-volume tenant still consumes all available slots first within each poll cycle. Only caps total concurrent tasks per tenant. We recommend the block-based approach for true fairness, but this is a valid fallback if the team finds block-based queuing too complex for the initial release.

---

## 4. Distributed Worker Coordination

### 4.1 Task Claiming with SKIP LOCKED

Every pod runs a poller thread that claims tasks from `ready_queue`:

```sql
WITH claimed AS (
    SELECT id, workflow_run_id, task_name
    FROM ready_queue
    ORDER BY id ASC
    FOR UPDATE SKIP LOCKED
    LIMIT $1  -- batch size (e.g., 10)
)
DELETE FROM ready_queue
USING claimed
WHERE ready_queue.id = claimed.id
RETURNING claimed.workflow_run_id, claimed.task_name;
```

**Properties:**
- **No contention**: workers skip rows already locked by other pods
- **Atomic**: lock + read + delete in one operation
- **Crash-safe**: if a pod dies mid-transaction, the transaction rolls back and tasks reappear in the queue
- **Ordering preserved**: `ORDER BY id ASC` maintains the fairness encoding from Section 3

### 4.2 Worker Identity

Each pod generates a unique `worker_id` on startup:

```kotlin
val workerId: String = "${InetAddress.getLocalHost().hostName}-${ProcessHandle.current().pid()}-${UUID.randomUUID().toString().take(8)}"
// Example: "pod-abc-12345-f47ac10b"
```

This ID is written to `tasks.claimed_by` when a task transitions to `RUNNING`, enabling the housekeeper to identify which worker owns which task.

### 4.3 Heartbeat Protocol

Each pod runs a heartbeat thread that updates `last_heartbeat` for all tasks it is currently executing:

```kotlin
class HeartbeatThread(
    private val dataSource: DataSource,
    private val workerId: String,
    private val intervalMs: Long = 30_000,  // 30 seconds
) : Runnable {

    override fun run() {
        while (!Thread.currentThread().isInterrupted) {
            try {
                dataSource.connection.use { conn ->
                    conn.prepareStatement("""
                        UPDATE tasks
                        SET last_heartbeat = now()
                        WHERE claimed_by = ? AND status = 'RUNNING'
                    """).use { stmt ->
                        stmt.setString(1, workerId)
                        stmt.executeUpdate()
                    }
                }
                Thread.sleep(intervalMs)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
    }
}
```

### 4.4 Dead Execution Detection (Housekeeper)

The housekeeper runs on the **leader pod only** (see Section 4.5). It scans for tasks with stale heartbeats:

```sql
-- Find tasks where the worker has stopped heartbeating
SELECT workflow_run_id, task_name, claimed_by, retry_count, max_retries
FROM tasks
WHERE status = 'RUNNING'
  AND last_heartbeat < now() - INTERVAL '2 minutes'  -- 4 missed heartbeats at 30s interval
FOR UPDATE SKIP LOCKED;
```

For each dead task:
- If `retry_count < max_retries`: re-enqueue into `ready_queue`, increment `retry_count`
- If `retry_count >= max_retries`: mark as `FAILED`, log event, check if workflow should be marked `FAILED`

### 4.5 Leader Election with Advisory Locks

Only one pod should run the timer poller and housekeeper. We use PostgreSQL advisory locks:

```kotlin
class LeaderElection(
    private val dedicatedConnection: Connection,  // NOT from connection pool
    private val lockKey: Long = 8675309,  // arbitrary constant
) {
    @Volatile
    var isLeader: Boolean = false
        private set

    /**
     * Attempt to acquire leadership. Call periodically (e.g., every 10 seconds).
     * Returns true if this instance is now the leader.
     *
     * CRITICAL: This connection must NOT come from a connection pool.
     * Connection pools recycle connections, which would release the advisory lock.
     */
    fun tryAcquire(): Boolean {
        val stmt = dedicatedConnection.prepareStatement(
            "SELECT pg_try_advisory_lock(?)"
        )
        stmt.setLong(1, lockKey)
        val rs = stmt.executeQuery()
        rs.next()
        isLeader = rs.getBoolean(1)
        return isLeader
    }

    /**
     * Release leadership explicitly (e.g., during graceful shutdown).
     */
    fun release() {
        val stmt = dedicatedConnection.prepareStatement(
            "SELECT pg_advisory_unlock(?)"
        )
        stmt.setLong(1, lockKey)
        stmt.executeQuery()
        isLeader = false
    }
}
```

**Failover**: When the leader pod dies, its database connection closes, which automatically releases the advisory lock. The next pod that calls `tryAcquire()` becomes the new leader.

**Critical caveat**: The advisory lock must be held on a **dedicated, non-pooled connection**. HikariCP recycles connections, which would release the lock. Create a separate `DriverManager.getConnection()` for the leader election connection.

### 4.6 Consistency Under Concurrent Operations

**Fan-in safety**: When two pods complete parent tasks concurrently, both will attempt to decrement `pending_parent_count` on the child task. PostgreSQL serializes writes to the same row, so the decrement is atomic:

```sql
UPDATE tasks
SET pending_parent_count = pending_parent_count - 1
WHERE workflow_run_id = $1
  AND $2 = ANY(parent_names)
  AND status = 'PENDING'
RETURNING *;
```

Only one of the two UPDATEs will see `pending_parent_count` reach 0. That pod is responsible for enqueueing the child task. The other pod's UPDATE sees a count of 1 (decremented from 2) and does nothing further. No double-enqueue is possible.

**Ready queue double-insert prevention**: Since the child is only enqueued when `pending_parent_count` reaches 0, and PostgreSQL serializes the decrements, at most one pod will see the count reach 0. However, for defense-in-depth, we add a unique constraint:

```sql
CREATE UNIQUE INDEX idx_ready_queue_unique
ON ready_queue (workflow_run_id, task_name);
```

An `INSERT ... ON CONFLICT DO NOTHING` ensures idempotency even in edge cases.

---

## 5. Durable Sleep Surviving Restarts

### 5.1 The Problem

A workflow needs to wait 24 hours between steps. During that 24 hours:
- The pod might be restarted (rolling deploy)
- The pod might be evicted (Cloud Run scaling down)
- The pod might crash

The sleep must survive all of these events.

### 5.2 Design: PostgreSQL-Backed Timer Table

Durable sleep is modeled as a **timer row** in PostgreSQL. No in-process state is needed between the sleep start and wake-up.

```sql
CREATE TABLE durable_timers (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    workflow_run_id UUID NOT NULL,
    task_name       TEXT NOT NULL,  -- the task to enqueue when the timer fires
    tenant_id       TEXT NOT NULL,
    wake_at         TIMESTAMPTZ NOT NULL,
    fired           BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT fk_timer_task
        FOREIGN KEY (workflow_run_id, task_name) REFERENCES tasks(workflow_run_id, task_name)
);

CREATE INDEX idx_durable_timers_pending
ON durable_timers (wake_at ASC)
WHERE fired = FALSE;
```

### 5.3 Sleep Flow

**When a task calls `sleep(duration)`:**

1. The task execution writes the timer row:
   ```sql
   INSERT INTO durable_timers (workflow_run_id, task_name, tenant_id, wake_at)
   VALUES ($1, $2, $3, now() + $4::interval);
   ```
2. The task then creates a **continuation task** (the "after-sleep" task) in `SLEEPING` state:
   ```sql
   UPDATE tasks
   SET status = 'SLEEPING'
   WHERE workflow_run_id = $1 AND task_name = $2;
   ```
3. The task completes. No thread is held. The workflow exists only as rows in PostgreSQL.

**When the timer fires:**

The leader pod's timer poller runs periodically (default: every 5 seconds):

```sql
WITH expired AS (
    SELECT id, workflow_run_id, task_name, tenant_id
    FROM durable_timers
    WHERE wake_at <= now()
      AND fired = FALSE
    ORDER BY wake_at ASC
    FOR UPDATE SKIP LOCKED
    LIMIT 100
)
UPDATE durable_timers
SET fired = TRUE
FROM expired
WHERE durable_timers.id = expired.id
RETURNING expired.*;
```

For each expired timer:
1. Update the task status from `SLEEPING` to `QUEUED`
2. Enqueue the task into `ready_queue` (using the tenant-fair enqueue from Section 3.6)
3. The task is claimed by any available pod and execution continues

### 5.4 Sleep Within a DAG

Sleep is modeled as a **special task** in the DAG, not as a property of the engine. When the user defines:

```kotlin
val wf = workflow("order-processing") {
    val validate = task("validate") { ... }
    val charge = task("charge", dependsOn(validate)) { ... }
    val waitForFraud = sleep("fraud-check-window", Duration.ofHours(24), dependsOn(charge))
    val ship = task("ship", dependsOn(waitForFraud)) { ... }
}
```

The engine creates 4 task records. The `waitForFraud` task, when it becomes ready:
1. Is claimed from the ready queue
2. Its executor recognizes it as a sleep task
3. Writes a timer row to `durable_timers` with `wake_at = now() + 24h`
4. Sets the task status to `SLEEPING`
5. Returns immediately (no thread held)

When the timer fires, the task transitions to `COMPLETED`, which decrements `pending_parent_count` on `ship`, which gets enqueued.

### 5.5 Recovery After Crash

If the leader pod dies while holding expired timers:
- The timer rows remain in PostgreSQL with `fired = FALSE`
- The new leader's timer poller picks them up on the next scan
- Maximum delay: one poll interval (5 seconds)

If a pod dies while executing the task after a sleep wake-up:
- The housekeeper detects the stale heartbeat
- The task is re-enqueued for retry

### 5.6 Precision

Timer precision is bounded by the timer poller interval (default: 5 seconds). A sleep of 24 hours will wake up within 5 seconds of the target time. This is acceptable for workflow use cases. For sub-second precision, reduce the poll interval at the cost of more database queries.

---

## 6. Core Interfaces (Non-Coroutine)

All interfaces use blocking methods. No `suspend` keyword, no `CompletableFuture` (the engine is pull-based, not push-based).

### 6.1 Workflow Definition DSL

```kotlin
// ─── Task Definition ─────────────────────────────────────

data class RetryPolicy(
    val maxRetries: Int = 0,
    val initialDelayMs: Long = 1000,
    val backoffFactor: Double = 2.0,
    val maxDelayMs: Long = 60_000,
) {
    fun calculateDelayMs(retryCount: Int): Long {
        val delay = (initialDelayMs * backoffFactor.pow(retryCount - 1)).toLong()
        return delay.coerceAtMost(maxDelayMs)
    }

    companion object {
        fun default() = RetryPolicy()
    }
}

data class TaskDefinition<I, O>(
    val name: String,
    val parents: List<TaskDefinition<*, *>> = emptyList(),
    val retryPolicy: RetryPolicy = RetryPolicy.default(),
    val isSleep: Boolean = false,
    val sleepDuration: Duration? = null,
    val execute: ((TaskContext) -> O)? = null,  // null for sleep tasks
)

data class WorkflowDefinition(
    val name: String,
    val tasks: List<TaskDefinition<*, *>>,
)

// ─── Builder DSL ─────────────────────────────────────────

class WorkflowBuilder(val name: String) {
    private val tasks = mutableListOf<TaskDefinition<*, *>>()

    fun <O> task(
        name: String,
        parents: List<TaskDefinition<*, *>> = emptyList(),
        retryPolicy: RetryPolicy = RetryPolicy.default(),
        execute: (TaskContext) -> O,
    ): TaskDefinition<Unit, O> {
        val def = TaskDefinition<Unit, O>(
            name = name,
            parents = parents,
            retryPolicy = retryPolicy,
            execute = execute,
        )
        tasks.add(def)
        return def
    }

    fun sleep(
        name: String,
        duration: Duration,
        parents: List<TaskDefinition<*, *>> = emptyList(),
    ): TaskDefinition<Unit, Unit> {
        val def = TaskDefinition<Unit, Unit>(
            name = name,
            parents = parents,
            isSleep = true,
            sleepDuration = duration,
        )
        tasks.add(def)
        return def
    }

    fun build(): WorkflowDefinition = WorkflowDefinition(name, tasks.toList())
}

fun workflow(name: String, block: WorkflowBuilder.() -> Unit): WorkflowDefinition =
    WorkflowBuilder(name).apply(block).build()

// ─── Helper ──────────────────────────────────────────────

fun dependsOn(vararg parents: TaskDefinition<*, *>): List<TaskDefinition<*, *>> = parents.toList()
```

### 6.2 Task Context

```kotlin
interface TaskContext {
    val workflowRunId: UUID
    val taskName: String
    val retryCount: Int
    val tenantId: String

    /**
     * Read the serialized JSON output of a completed parent task.
     * Throws if the parent has not completed.
     */
    fun <O> output(parent: TaskDefinition<*, O>): O
}
```

### 6.3 Engine API

```kotlin
interface WorkflowEngine {
    /**
     * Trigger a new workflow run. Creates all task records and enqueues root tasks.
     * Returns the workflow run ID. Non-blocking — returns immediately.
     */
    fun trigger(
        workflow: WorkflowDefinition,
        tenantId: String,
        input: Any? = null,
        workflowRunId: UUID = UUID.randomUUID(),
    ): UUID

    /**
     * Get the current status of a workflow run.
     */
    fun getStatus(workflowRunId: UUID): WorkflowRunStatus?

    /**
     * Block until the workflow completes or the timeout is reached.
     * For testing and synchronous API endpoints.
     */
    fun awaitCompletion(
        workflowRunId: UUID,
        timeout: Duration = Duration.ofMinutes(5),
    ): WorkflowRunResult

    /**
     * Start the engine: begin polling for tasks, start heartbeat, attempt leader election.
     */
    fun start()

    /**
     * Stop the engine gracefully: stop claiming new tasks, wait for in-flight tasks,
     * release leadership.
     */
    fun stop(timeout: Duration = Duration.ofSeconds(30))
}
```

### 6.4 Repository Interfaces

```kotlin
// ─── Workflow Run ────────────────────────────────────────

interface WorkflowRunRepository {
    fun create(run: WorkflowRunRecord)
    fun findById(id: UUID): WorkflowRunRecord?
    fun updateStatus(id: UUID, status: RunStatus, completedAt: Instant? = null)
}

data class WorkflowRunRecord(
    val id: UUID,
    val workflowName: String,
    val tenantId: String,
    val status: RunStatus,
    val input: String?,  // serialized JSON
    val createdAt: Instant,
    val completedAt: Instant? = null,
)

enum class RunStatus { RUNNING, COMPLETED, FAILED, CANCELLED }

// ─── Task ────────────────────────────────────────────────

interface TaskRepository {
    fun createAll(tasks: List<TaskRecord>)
    fun findByName(workflowRunId: UUID, taskName: String): TaskRecord?
    fun findAllByWorkflowRunId(workflowRunId: UUID): List<TaskRecord>
    fun updateStatus(
        workflowRunId: UUID, taskName: String, status: TaskState,
        output: String? = null, error: String? = null,
        retryCount: Int? = null, claimedBy: String? = null,
    )
    fun decrementPendingParents(
        workflowRunId: UUID, parentTaskName: String,
    ): List<TaskRecord>
    fun findDeadExecutions(stalenessThreshold: Duration): List<TaskRecord>
    fun heartbeat(workerId: String)
}

data class TaskRecord(
    val workflowRunId: UUID,
    val taskName: String,
    val tenantId: String,
    val status: TaskState,
    val parentNames: List<String>,
    val pendingParentCount: Int,
    val input: String?,
    val output: String?,
    val error: String?,
    val retryCount: Int = 0,
    val maxRetries: Int = 0,
    val isSleep: Boolean = false,
    val sleepDuration: Duration? = null,
    val claimedBy: String? = null,
    val lastHeartbeat: Instant? = null,
    val createdAt: Instant,
    val startedAt: Instant? = null,
    val completedAt: Instant? = null,
)

enum class TaskState {
    PENDING, QUEUED, RUNNING, SLEEPING, COMPLETED, FAILED, CANCELLED, SKIPPED
}

// ─── Ready Queue ─────────────────────────────────────────

interface ReadyQueueRepository {
    fun enqueue(item: QueueItem)
    fun enqueueAll(items: List<QueueItem>)
    fun claim(limit: Int): List<QueueItem>
    fun claimWithConcurrencyLimit(maxConcurrencyPerTenant: Int, limit: Int): List<QueueItem>
}

data class QueueItem(
    val id: Long = 0,  // computed by block-based algorithm
    val workflowRunId: UUID,
    val taskName: String,
    val tenantId: String,
    val enqueuedAt: Instant,
)

// ─── Durable Timer ───────────────────────────────────────

interface TimerRepository {
    fun create(timer: TimerRecord)
    fun findExpired(limit: Int): List<TimerRecord>
    fun markFired(ids: List<Long>)
}

data class TimerRecord(
    val id: Long = 0,
    val workflowRunId: UUID,
    val taskName: String,
    val tenantId: String,
    val wakeAt: Instant,
    val fired: Boolean = false,
    val createdAt: Instant,
)

// ─── Event Log ───────────────────────────────────────────

interface EventRepository {
    fun append(event: TaskEvent)
    fun findByWorkflowRunId(workflowRunId: UUID): List<TaskEvent>
}

data class TaskEvent(
    val id: Long = 0,
    val workflowRunId: UUID,
    val taskName: String,
    val eventType: TaskEventType,
    val data: String? = null,
    val timestamp: Instant,
)

enum class TaskEventType {
    QUEUED, STARTED, COMPLETED, FAILED, RETRYING, CANCELLED, SKIPPED, SLEEPING, WOKEN
}
```

---

## 7. DAG Runner Implementation

### 7.1 Engine Core

```kotlin
class DagWorkflowEngine(
    private val workflowRunRepo: WorkflowRunRepository,
    private val taskRepo: TaskRepository,
    private val queueRepo: ReadyQueueRepository,
    private val timerRepo: TimerRepository,
    private val eventRepo: EventRepository,
    private val workflowRegistry: Map<String, WorkflowDefinition>,
    private val clock: Clock = Clock.systemUTC(),
    private val workerId: String = generateWorkerId(),
    private val workerPoolSize: Int = 10,
    private val pollIntervalMs: Long = 200,
    private val timerPollIntervalMs: Long = 5_000,
    private val heartbeatIntervalMs: Long = 30_000,
    private val leaderElection: LeaderElection? = null,
) : WorkflowEngine {

    private val workerPool: ExecutorService = Executors.newFixedThreadPool(workerPoolSize)
    private val scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(3)
    private val activeTasks = ConcurrentHashMap<String, Future<*>>()

    @Volatile private var running = false

    override fun trigger(
        workflow: WorkflowDefinition,
        tenantId: String,
        input: Any?,
        workflowRunId: UUID,
    ): UUID {
        // 1. Create workflow run record
        workflowRunRepo.create(WorkflowRunRecord(
            id = workflowRunId,
            workflowName = workflow.name,
            tenantId = tenantId,
            status = RunStatus.RUNNING,
            input = input?.let { Json.encodeToString(serializer(), it) },
            createdAt = Instant.now(clock),
        ))

        // 2. Create all task records in one batch
        val taskRecords = workflow.tasks.map { taskDef ->
            TaskRecord(
                workflowRunId = workflowRunId,
                taskName = taskDef.name,
                tenantId = tenantId,
                status = if (taskDef.parents.isEmpty()) TaskState.QUEUED else TaskState.PENDING,
                parentNames = taskDef.parents.map { it.name },
                pendingParentCount = taskDef.parents.size,
                input = null, output = null, error = null,
                maxRetries = taskDef.retryPolicy.maxRetries,
                isSleep = taskDef.isSleep,
                sleepDuration = taskDef.sleepDuration,
                createdAt = Instant.now(clock),
            )
        }
        taskRepo.createAll(taskRecords)

        // 3. Enqueue root tasks (those with no parents)
        val rootTasks = taskRecords.filter { it.pendingParentCount == 0 }
        queueRepo.enqueueAll(rootTasks.map { task ->
            QueueItem(
                workflowRunId = workflowRunId,
                taskName = task.taskName,
                tenantId = tenantId,
                enqueuedAt = Instant.now(clock),
            )
        })

        return workflowRunId
    }

    override fun start() {
        running = true

        // Task poller — runs on every pod
        scheduler.scheduleWithFixedDelay(
            this::pollAndDispatch,
            0, pollIntervalMs, TimeUnit.MILLISECONDS,
        )

        // Heartbeat — runs on every pod
        scheduler.scheduleWithFixedDelay(
            { taskRepo.heartbeat(workerId) },
            heartbeatIntervalMs, heartbeatIntervalMs, TimeUnit.MILLISECONDS,
        )

        // Leader duties — timer poller + housekeeper
        scheduler.scheduleWithFixedDelay(
            this::leaderDuties,
            0, timerPollIntervalMs, TimeUnit.MILLISECONDS,
        )
    }

    override fun stop(timeout: Duration) {
        running = false

        // 1. Stop the scheduler (no more polling)
        scheduler.shutdown()

        // 2. Wait for in-flight tasks to complete
        workerPool.shutdown()
        if (!workerPool.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            workerPool.shutdownNow()
        }

        // 3. Release leadership
        leaderElection?.release()
    }

    private fun pollAndDispatch() {
        if (!running) return
        try {
            val claimed = queueRepo.claim(workerPoolSize)
            for (item in claimed) {
                val future = workerPool.submit {
                    executeTask(item.workflowRunId, item.taskName)
                }
                activeTasks["${item.workflowRunId}:${item.taskName}"] = future
            }
        } catch (e: Exception) {
            // Log and continue — poller must not die
        }
    }

    private fun leaderDuties() {
        if (leaderElection != null && !leaderElection.tryAcquire()) return

        try {
            // Fire expired timers
            val expired = timerRepo.findExpired(100)
            if (expired.isNotEmpty()) {
                timerRepo.markFired(expired.map { it.id })
                for (timer in expired) {
                    taskRepo.updateStatus(
                        timer.workflowRunId, timer.taskName, TaskState.COMPLETED,
                    )
                    eventRepo.append(TaskEvent(
                        workflowRunId = timer.workflowRunId,
                        taskName = timer.taskName,
                        eventType = TaskEventType.WOKEN,
                        timestamp = Instant.now(clock),
                    ))
                    // Resolve children of the sleep task
                    resolveChildren(timer.workflowRunId, timer.taskName, timer.tenantId)
                }
            }

            // Detect dead executions
            val dead = taskRepo.findDeadExecutions(Duration.ofMinutes(2))
            for (task in dead) {
                handleDeadExecution(task)
            }
        } catch (e: Exception) {
            // Log and continue
        }
    }

    private fun executeTask(workflowRunId: UUID, taskName: String) {
        val task = taskRepo.findByName(workflowRunId, taskName) ?: return
        val workflow = workflowRegistry[task.workflowRunId.toString()]
            ?: workflowRegistry.values.first()  // simplified lookup

        val taskDef = workflow.tasks.firstOrNull { it.name == taskName } ?: return

        // Handle sleep tasks
        if (taskDef.isSleep && taskDef.sleepDuration != null) {
            executeSleep(workflowRunId, taskName, task.tenantId, taskDef.sleepDuration)
            return
        }

        // Mark as RUNNING
        taskRepo.updateStatus(workflowRunId, taskName, TaskState.RUNNING, claimedBy = workerId)
        eventRepo.append(TaskEvent(
            workflowRunId = workflowRunId, taskName = taskName,
            eventType = TaskEventType.STARTED, timestamp = Instant.now(clock),
        ))

        try {
            // Build context with parent outputs
            val context = TaskContextImpl(
                workflowRunId = workflowRunId,
                taskName = taskName,
                tenantId = task.tenantId,
                retryCount = task.retryCount,
                taskRepo = taskRepo,
            )

            // Execute the task
            @Suppress("UNCHECKED_CAST")
            val execute = taskDef.execute as? (TaskContext) -> Any?
            val result = execute?.invoke(context)
            val outputJson = result?.let { Json.encodeToString(serializer(), it) }

            // Mark COMPLETED
            taskRepo.updateStatus(workflowRunId, taskName, TaskState.COMPLETED,
                output = outputJson)
            eventRepo.append(TaskEvent(
                workflowRunId = workflowRunId, taskName = taskName,
                eventType = TaskEventType.COMPLETED, data = outputJson,
                timestamp = Instant.now(clock),
            ))

            // Resolve children
            resolveChildren(workflowRunId, taskName, task.tenantId)

        } catch (e: Exception) {
            handleFailure(workflowRunId, taskName, task, taskDef, e)
        } finally {
            activeTasks.remove("$workflowRunId:$taskName")
        }
    }

    private fun executeSleep(
        workflowRunId: UUID, taskName: String, tenantId: String, duration: Duration,
    ) {
        taskRepo.updateStatus(workflowRunId, taskName, TaskState.SLEEPING)
        eventRepo.append(TaskEvent(
            workflowRunId = workflowRunId, taskName = taskName,
            eventType = TaskEventType.SLEEPING,
            data = """{"duration":"$duration"}""",
            timestamp = Instant.now(clock),
        ))
        timerRepo.create(TimerRecord(
            workflowRunId = workflowRunId,
            taskName = taskName,
            tenantId = tenantId,
            wakeAt = Instant.now(clock).plus(duration),
            createdAt = Instant.now(clock),
        ))
    }

    private fun resolveChildren(workflowRunId: UUID, parentTaskName: String, tenantId: String) {
        val readyChildren = taskRepo.decrementPendingParents(workflowRunId, parentTaskName)
        if (readyChildren.isNotEmpty()) {
            queueRepo.enqueueAll(readyChildren.map { child ->
                QueueItem(
                    workflowRunId = workflowRunId,
                    taskName = child.taskName,
                    tenantId = tenantId,
                    enqueuedAt = Instant.now(clock),
                )
            })
        }

        // Check if workflow is complete
        checkWorkflowCompletion(workflowRunId)
    }

    private fun checkWorkflowCompletion(workflowRunId: UUID) {
        val tasks = taskRepo.findAllByWorkflowRunId(workflowRunId)
        val allTerminal = tasks.all { it.status in TERMINAL_STATES }
        if (allTerminal) {
            val anyFailed = tasks.any { it.status == TaskState.FAILED }
            val finalStatus = if (anyFailed) RunStatus.FAILED else RunStatus.COMPLETED
            workflowRunRepo.updateStatus(workflowRunId, finalStatus, Instant.now(clock))
        }
    }

    private fun handleFailure(
        workflowRunId: UUID, taskName: String, task: TaskRecord,
        taskDef: TaskDefinition<*, *>, error: Exception,
    ) {
        if (error is TerminalError || task.retryCount >= taskDef.retryPolicy.maxRetries) {
            taskRepo.updateStatus(workflowRunId, taskName, TaskState.FAILED,
                error = error.message, retryCount = task.retryCount)
            eventRepo.append(TaskEvent(
                workflowRunId = workflowRunId, taskName = taskName,
                eventType = TaskEventType.FAILED, data = error.message,
                timestamp = Instant.now(clock),
            ))
            checkWorkflowCompletion(workflowRunId)
        } else {
            val newRetryCount = task.retryCount + 1
            val backoffMs = taskDef.retryPolicy.calculateDelayMs(newRetryCount)
            taskRepo.updateStatus(workflowRunId, taskName, TaskState.QUEUED,
                retryCount = newRetryCount)
            eventRepo.append(TaskEvent(
                workflowRunId = workflowRunId, taskName = taskName,
                eventType = TaskEventType.RETRYING,
                data = """{"retryCount":$newRetryCount,"backoffMs":$backoffMs}""",
                timestamp = Instant.now(clock),
            ))
            // Schedule re-enqueue after backoff
            scheduler.schedule({
                queueRepo.enqueue(QueueItem(
                    workflowRunId = workflowRunId,
                    taskName = taskName,
                    tenantId = task.tenantId,
                    enqueuedAt = Instant.now(clock),
                ))
            }, backoffMs, TimeUnit.MILLISECONDS)
        }
    }

    private fun handleDeadExecution(task: TaskRecord) {
        if (task.retryCount < task.maxRetries) {
            taskRepo.updateStatus(task.workflowRunId, task.taskName, TaskState.QUEUED,
                retryCount = task.retryCount + 1)
            queueRepo.enqueue(QueueItem(
                workflowRunId = task.workflowRunId,
                taskName = task.taskName,
                tenantId = task.tenantId,
                enqueuedAt = Instant.now(clock),
            ))
        } else {
            taskRepo.updateStatus(task.workflowRunId, task.taskName, TaskState.FAILED,
                error = "Worker died and retries exhausted")
            checkWorkflowCompletion(task.workflowRunId)
        }
    }

    companion object {
        private val TERMINAL_STATES = setOf(
            TaskState.COMPLETED, TaskState.FAILED, TaskState.CANCELLED, TaskState.SKIPPED
        )

        fun generateWorkerId(): String {
            val host = try { InetAddress.getLocalHost().hostName } catch (e: Exception) { "unknown" }
            val pid = ProcessHandle.current().pid()
            val rand = UUID.randomUUID().toString().take(8)
            return "$host-$pid-$rand"
        }
    }
}

class TerminalError(message: String) : RuntimeException(message)
```

### 7.2 Retry Backoff Handling

Retry backoff is handled via `ScheduledExecutorService.schedule()`. The task is re-enqueued after the backoff delay. This does **not** consume a thread — `ScheduledExecutorService` uses a timer wheel internally.

If the pod dies during the backoff wait:
- The scheduled re-enqueue never fires
- The housekeeper detects the task as dead (it was marked `QUEUED` but never got a heartbeat) and re-enqueues it
- Worst case: the retry is delayed by the housekeeper scan interval (2 minutes)

---

## 8. PostgreSQL Schema

```sql
-- ═══════════════════════════════════════════════════════════════
-- Multi-Tenant Fair Queuing Support
-- ═══════════════════════════════════════════════════════════════

CREATE TABLE tenant_groups (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   TEXT UNIQUE NOT NULL,
    block_addr  BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE task_addr_ptrs (
    max_assigned_block_addr BIGINT NOT NULL DEFAULT 0,
    onerow_id BOOL PRIMARY KEY DEFAULT TRUE,
    CONSTRAINT onerow_uni CHECK (onerow_id)
);

INSERT INTO task_addr_ptrs (max_assigned_block_addr) VALUES (0);

-- ═══════════════════════════════════════════════════════════════
-- Workflow Runs
-- ═══════════════════════════════════════════════════════════════

CREATE TABLE workflow_runs (
    id              UUID PRIMARY KEY,
    workflow_name   TEXT NOT NULL,
    tenant_id       TEXT NOT NULL,
    status          TEXT NOT NULL DEFAULT 'RUNNING',
    input           JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at    TIMESTAMPTZ
);

CREATE INDEX idx_workflow_runs_status ON workflow_runs (status)
    WHERE status = 'RUNNING';

CREATE INDEX idx_workflow_runs_tenant ON workflow_runs (tenant_id, status);

-- ═══════════════════════════════════════════════════════════════
-- Tasks
-- ═══════════════════════════════════════════════════════════════

CREATE TABLE tasks (
    workflow_run_id     UUID NOT NULL REFERENCES workflow_runs(id),
    task_name           TEXT NOT NULL,
    tenant_id           TEXT NOT NULL,
    status              TEXT NOT NULL DEFAULT 'PENDING',
    parent_names        TEXT[] NOT NULL DEFAULT '{}',
    pending_parent_count INT NOT NULL DEFAULT 0,
    input               JSONB,
    output              JSONB,
    error               TEXT,
    retry_count         INT NOT NULL DEFAULT 0,
    max_retries         INT NOT NULL DEFAULT 0,
    is_sleep            BOOLEAN NOT NULL DEFAULT FALSE,
    sleep_duration_ms   BIGINT,
    claimed_by          TEXT,
    last_heartbeat      TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at          TIMESTAMPTZ,
    completed_at        TIMESTAMPTZ,
    PRIMARY KEY (workflow_run_id, task_name)
);

CREATE INDEX idx_tasks_active ON tasks (workflow_run_id, status)
    WHERE status IN ('PENDING', 'QUEUED', 'RUNNING', 'SLEEPING');

CREATE INDEX idx_tasks_dead_detection ON tasks (last_heartbeat)
    WHERE status = 'RUNNING';

-- ═══════════════════════════════════════════════════════════════
-- Ready Queue (with block-based fair IDs)
-- ═══════════════════════════════════════════════════════════════

CREATE TABLE ready_queue (
    id                  BIGINT NOT NULL,  -- computed by block-based algorithm
    workflow_run_id     UUID NOT NULL,
    task_name           TEXT NOT NULL,
    tenant_id           TEXT NOT NULL,
    enqueued_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    FOREIGN KEY (workflow_run_id, task_name) REFERENCES tasks(workflow_run_id, task_name)
);

CREATE UNIQUE INDEX idx_ready_queue_unique ON ready_queue (workflow_run_id, task_name);

-- No need for a separate dispatch index: PRIMARY KEY on id is sufficient.
-- ORDER BY id ASC provides fair round-robin via the block-based algorithm.

-- ═══════════════════════════════════════════════════════════════
-- Durable Timers
-- ═══════════════════════════════════════════════════════════════

CREATE TABLE durable_timers (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    workflow_run_id     UUID NOT NULL,
    task_name           TEXT NOT NULL,
    tenant_id           TEXT NOT NULL,
    wake_at             TIMESTAMPTZ NOT NULL,
    fired               BOOLEAN NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (workflow_run_id, task_name) REFERENCES tasks(workflow_run_id, task_name)
);

CREATE INDEX idx_durable_timers_pending ON durable_timers (wake_at ASC)
    WHERE fired = FALSE;

-- ═══════════════════════════════════════════════════════════════
-- Task Events (append-only log)
-- ═══════════════════════════════════════════════════════════════

CREATE TABLE task_events (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY,
    workflow_run_id     UUID NOT NULL,
    task_name           TEXT NOT NULL,
    event_type          TEXT NOT NULL,
    data                JSONB,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (workflow_run_id, task_name, id)
);

-- ═══════════════════════════════════════════════════════════════
-- Key Operations
-- ═══════════════════════════════════════════════════════════════

-- Claim tasks with fairness (simple version):
-- WITH claimed AS (
--     SELECT id, workflow_run_id, task_name
--     FROM ready_queue
--     ORDER BY id ASC
--     FOR UPDATE SKIP LOCKED
--     LIMIT $1
-- )
-- DELETE FROM ready_queue USING claimed
-- WHERE ready_queue.id = claimed.id
-- RETURNING claimed.*;

-- Claim tasks with per-tenant concurrency limit:
-- WITH min_id AS (
--     SELECT COALESCE(min(id), 0) AS min_id FROM ready_queue
-- ),
-- claimed AS (
--     SELECT rq.id, rq.workflow_run_id, rq.task_name
--     FROM ready_queue rq
--     WHERE rq.id >= (SELECT min_id FROM min_id)
--       AND rq.id < (SELECT min_id FROM min_id) + $1::int * 1048576
--     ORDER BY rq.id ASC
--     FOR UPDATE SKIP LOCKED
--     LIMIT $2
-- )
-- DELETE FROM ready_queue USING claimed
-- WHERE ready_queue.id = claimed.id
-- RETURNING claimed.*;

-- Enqueue with block-based fair ID:
-- (see Section 3.6)

-- Decrement pending parents:
-- UPDATE tasks
-- SET pending_parent_count = pending_parent_count - 1
-- WHERE workflow_run_id = $1
--   AND $2 = ANY(parent_names)
--   AND status = 'PENDING'
-- RETURNING *;

-- Fire expired timers:
-- WITH expired AS (
--     SELECT id, workflow_run_id, task_name, tenant_id
--     FROM durable_timers
--     WHERE wake_at <= now() AND fired = FALSE
--     ORDER BY wake_at ASC
--     FOR UPDATE SKIP LOCKED
--     LIMIT 100
-- )
-- UPDATE durable_timers SET fired = TRUE
-- FROM expired WHERE durable_timers.id = expired.id
-- RETURNING expired.*;

-- Update max_assigned_block_addr (leader housekeeper, periodic):
-- WITH max_consumed AS (
--     SELECT COALESCE(FLOOR(max(id)::decimal / 1048576), 0) AS block
--     FROM ready_queue
-- )
-- UPDATE task_addr_ptrs
-- SET max_assigned_block_addr = (SELECT block FROM max_consumed);
```

---

## 9. Graceful Shutdown & Rolling Deploys

### 9.1 The Problem

With rolling deploys running 24/7, pods are regularly terminated. We need:
1. In-flight tasks complete without being lost
2. No duplicate execution of tasks
3. Durable sleeps survive pod termination
4. Minimal disruption to throughput

### 9.2 Shutdown Sequence

When a pod receives `SIGTERM`:

```
Phase 1: Stop Claiming (immediate)
  └─ Set running = false
  └─ Poller stops claiming new tasks from ready_queue
  └─ Heartbeat continues (in-flight tasks are still alive)

Phase 2: Drain (up to terminationGracePeriodSeconds)
  └─ Wait for workerPool.awaitTermination()
  └─ In-flight tasks complete normally
  └─ As each task completes, it resolves children (which get enqueued
     and claimed by OTHER pods)

Phase 3: Release Leadership (if leader)
  └─ Release advisory lock
  └─ Another pod becomes leader on next tryAcquire()

Phase 4: Forced Shutdown (if drain timeout exceeded)
  └─ workerPool.shutdownNow() — interrupts running threads
  └─ Interrupted tasks leave status = RUNNING in the database
  └─ Heartbeat stops → housekeeper on another pod detects dead
     executions within 2 minutes
```

### 9.3 Kubernetes Configuration

```yaml
apiVersion: apps/v1
kind: Deployment
spec:
  replicas: 3
  strategy:
    rollingUpdate:
      maxUnavailable: 0      # Never remove a pod before replacement is ready
      maxSurge: 1            # Add one new pod before removing old
  template:
    spec:
      terminationGracePeriodSeconds: 120  # 2 minutes to drain
      containers:
      - name: worker
        lifecycle:
          preStop:
            exec:
              command: ["sh", "-c", "sleep 5"]  # Allow load balancer to deregister
```

### 9.4 What Happens to Tasks During Shutdown

| Task State | What Happens | Recovery |
|---|---|---|
| In `ready_queue` | Nothing — other pods claim them | Immediate (next poll) |
| `RUNNING` with active heartbeat | Drains within grace period | Completes normally |
| `RUNNING` but pod killed | Heartbeat stops | Housekeeper re-enqueues within 2 min |
| `SLEEPING` (durable timer) | Timer in PostgreSQL, no pod resources | Timer poller fires on leader pod |
| Retry backoff (scheduled) | In-memory timer lost | Housekeeper detects and re-enqueues |

---

## 10. In-Memory Testing Without Coroutines

### 10.1 Time Control: FakeClock + ManualScheduler

Without coroutines, we cannot use `advanceUntilIdle()`. Instead, we use:

1. **`FakeClock`**: Controls `Instant.now()` for timestamps
2. **`ManualScheduler`**: Replaces `ScheduledExecutorService` in tests, allowing manual time advancement

```kotlin
class FakeClock(
    private var now: Instant = Instant.parse("2026-01-01T00:00:00Z"),
    private val zone: ZoneId = ZoneOffset.UTC,
) : Clock() {
    override fun instant(): Instant = now
    override fun withZone(zone: ZoneId): Clock = FakeClock(now, zone)
    override fun getZone(): ZoneId = zone

    fun advance(duration: Duration) {
        now = now.plus(duration)
    }

    fun set(instant: Instant) {
        now = instant
    }
}

/**
 * A scheduler that does not execute tasks automatically.
 * Tests call tick() or advanceTo() to fire pending tasks.
 */
class ManualScheduler(private val clock: FakeClock) : ScheduledExecutorService {
    private val pendingTasks = PriorityQueue<ScheduledTask>(compareBy { it.executeAt })
    private val immediateQueue = ArrayDeque<Runnable>()

    data class ScheduledTask(
        val executeAt: Instant,
        val runnable: Runnable,
    )

    override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> {
        val executeAt = clock.instant().plusMillis(unit.toMillis(delay))
        pendingTasks.add(ScheduledTask(executeAt, command))
        return CompletedScheduledFuture() // simplified
    }

    override fun scheduleWithFixedDelay(
        command: Runnable, initialDelay: Long, delay: Long, unit: TimeUnit,
    ): ScheduledFuture<*> {
        // For tests, we manually invoke; just record the task
        val executeAt = clock.instant().plusMillis(unit.toMillis(initialDelay))
        pendingTasks.add(ScheduledTask(executeAt, command))
        return CompletedScheduledFuture()
    }

    override fun submit(task: Runnable): Future<*> {
        immediateQueue.add(task)
        return CompletableFuture.completedFuture(null)
    }

    /**
     * Execute all tasks that are due at or before the current clock time.
     */
    fun tick() {
        // Execute immediate tasks
        while (immediateQueue.isNotEmpty()) {
            immediateQueue.poll().run()
        }
        // Execute scheduled tasks that are due
        while (pendingTasks.isNotEmpty() && !pendingTasks.peek().executeAt.isAfter(clock.instant())) {
            pendingTasks.poll().runnable.run()
        }
    }

    /**
     * Advance the clock to the given instant and execute all due tasks.
     */
    fun advanceTo(instant: Instant) {
        clock.set(instant)
        tick()
    }

    /**
     * Advance the clock by the given duration and execute all due tasks.
     */
    fun advanceBy(duration: Duration) {
        clock.advance(duration)
        tick()
    }

    /**
     * Run until no more tasks are pending (for tests that just want completion).
     */
    fun drainAll() {
        while (pendingTasks.isNotEmpty() || immediateQueue.isNotEmpty()) {
            if (immediateQueue.isNotEmpty()) {
                immediateQueue.poll().run()
            } else if (pendingTasks.isNotEmpty()) {
                val next = pendingTasks.peek()
                clock.set(next.executeAt)
                pendingTasks.poll().runnable.run()
            }
        }
    }

    // ... remaining ExecutorService methods (shutdown, etc.) as no-ops for tests
}
```

### 10.2 In-Memory Repository Implementations

```kotlin
class InMemoryTaskRepository : TaskRepository {
    private val tasks = ConcurrentHashMap<Pair<UUID, String>, TaskRecord>()

    override fun createAll(tasks: List<TaskRecord>) {
        for (task in tasks) {
            this.tasks[task.workflowRunId to task.taskName] = task
        }
    }

    override fun findByName(workflowRunId: UUID, taskName: String): TaskRecord? =
        tasks[workflowRunId to taskName]

    override fun findAllByWorkflowRunId(workflowRunId: UUID): List<TaskRecord> =
        tasks.values.filter { it.workflowRunId == workflowRunId }

    override fun updateStatus(
        workflowRunId: UUID, taskName: String, status: TaskState,
        output: String?, error: String?, retryCount: Int?, claimedBy: String?,
    ) {
        tasks.compute(workflowRunId to taskName) { _, existing ->
            existing?.copy(
                status = status,
                output = output ?: existing.output,
                error = error ?: existing.error,
                retryCount = retryCount ?: existing.retryCount,
                claimedBy = claimedBy ?: existing.claimedBy,
            )
        }
    }

    override fun decrementPendingParents(
        workflowRunId: UUID, parentTaskName: String,
    ): List<TaskRecord> {
        val readyTasks = mutableListOf<TaskRecord>()
        synchronized(this) {  // simulate PostgreSQL row-level serialization
            tasks.forEach { (key, record) ->
                if (key.first == workflowRunId
                    && parentTaskName in record.parentNames
                    && record.status == TaskState.PENDING
                ) {
                    val updated = record.copy(pendingParentCount = record.pendingParentCount - 1)
                    tasks[key] = updated
                    if (updated.pendingParentCount == 0) {
                        val queued = updated.copy(status = TaskState.QUEUED)
                        tasks[key] = queued
                        readyTasks.add(queued)
                    }
                }
            }
        }
        return readyTasks
    }

    override fun findDeadExecutions(stalenessThreshold: Duration): List<TaskRecord> =
        emptyList()  // Not needed in single-threaded tests

    override fun heartbeat(workerId: String) {}  // No-op in tests

    fun reset() = tasks.clear()
}

class InMemoryReadyQueueRepository : ReadyQueueRepository {
    private val queue = PriorityBlockingQueue<QueueItem>(
        100, compareBy { it.id }
    )
    private var nextId = 0L

    override fun enqueue(item: QueueItem) {
        queue.add(item.copy(id = nextId++))
    }

    override fun enqueueAll(items: List<QueueItem>) {
        items.forEach { enqueue(it) }
    }

    override fun claim(limit: Int): List<QueueItem> {
        val claimed = mutableListOf<QueueItem>()
        repeat(limit) {
            queue.poll()?.let { claimed.add(it) } ?: return claimed
        }
        return claimed
    }

    override fun claimWithConcurrencyLimit(maxConcurrencyPerTenant: Int, limit: Int): List<QueueItem> =
        claim(limit)  // simplified for tests

    fun reset() { queue.clear(); nextId = 0 }
}

class InMemoryTimerRepository(private val clock: Clock) : TimerRepository {
    private val timers = mutableListOf<TimerRecord>()
    private var nextId = 1L

    override fun create(timer: TimerRecord) {
        synchronized(timers) {
            timers.add(timer.copy(id = nextId++))
        }
    }

    override fun findExpired(limit: Int): List<TimerRecord> {
        synchronized(timers) {
            return timers
                .filter { !it.fired && !it.wakeAt.isAfter(clock.instant()) }
                .take(limit)
        }
    }

    override fun markFired(ids: List<Long>) {
        synchronized(timers) {
            for (id in ids) {
                val idx = timers.indexOfFirst { it.id == id }
                if (idx >= 0) timers[idx] = timers[idx].copy(fired = true)
            }
        }
    }

    fun reset() = synchronized(timers) { timers.clear(); nextId = 1 }
}
```

### 10.3 Test Harness

```kotlin
class TestWorkflowEngine(
    val clock: FakeClock = FakeClock(),
) {
    val workflowRunRepo = InMemoryWorkflowRunRepository()
    val taskRepo = InMemoryTaskRepository()
    val queueRepo = InMemoryReadyQueueRepository()
    val timerRepo = InMemoryTimerRepository(clock)
    val eventRepo = InMemoryEventRepository()

    /**
     * Create an engine pre-wired with in-memory repositories.
     * The engine uses a synchronous executor that runs tasks inline
     * for deterministic testing.
     */
    fun createEngine(
        workflowRegistry: Map<String, WorkflowDefinition>,
    ): DagWorkflowEngine {
        return DagWorkflowEngine(
            workflowRunRepo = workflowRunRepo,
            taskRepo = taskRepo,
            queueRepo = queueRepo,
            timerRepo = timerRepo,
            eventRepo = eventRepo,
            workflowRegistry = workflowRegistry,
            clock = clock,
            workerId = "test-worker",
            workerPoolSize = 1,
            leaderElection = null,  // always leader in tests
        )
    }

    /**
     * Run one cycle: poll for tasks, execute them, resolve children.
     * Call repeatedly until no more work is available.
     */
    fun runCycle(engine: DagWorkflowEngine) {
        // Simulate one poll-and-dispatch cycle
        val claimed = queueRepo.claim(10)
        for (item in claimed) {
            // Execute synchronously in test
            engine.executeTask(item.workflowRunId, item.taskName)
        }
    }

    /**
     * Run until all tasks are in terminal state or no progress is made.
     */
    fun runUntilComplete(engine: DagWorkflowEngine, workflowRunId: UUID) {
        var maxIterations = 100
        while (maxIterations-- > 0) {
            // Fire any expired timers
            val expired = timerRepo.findExpired(100)
            if (expired.isNotEmpty()) {
                timerRepo.markFired(expired.map { it.id })
                for (timer in expired) {
                    taskRepo.updateStatus(timer.workflowRunId, timer.taskName, TaskState.COMPLETED)
                    val readyChildren = taskRepo.decrementPendingParents(
                        timer.workflowRunId, timer.taskName
                    )
                    queueRepo.enqueueAll(readyChildren.map {
                        QueueItem(
                            workflowRunId = it.workflowRunId,
                            taskName = it.taskName,
                            tenantId = it.tenantId,
                            enqueuedAt = clock.instant(),
                        )
                    })
                }
            }

            // Execute available tasks
            val claimed = queueRepo.claim(10)
            if (claimed.isEmpty() && expired.isEmpty()) {
                // Check if we're waiting on timers
                val tasks = taskRepo.findAllByWorkflowRunId(workflowRunId)
                val sleeping = tasks.any { it.status == TaskState.SLEEPING }
                if (sleeping) {
                    // Advance clock to next timer
                    // (caller should use clock.advance() for explicit time control)
                    break
                }
                break  // No more work
            }

            for (item in claimed) {
                engine.executeTask(item.workflowRunId, item.taskName)
            }
        }
    }

    fun reset() {
        workflowRunRepo.reset()
        taskRepo.reset()
        queueRepo.reset()
        timerRepo.reset()
        eventRepo.reset()
    }
}
```

### 10.4 Example Acceptance Tests

```kotlin
class WorkflowAcceptanceTest {

    @Test
    fun `linear DAG executes in order`() {
        val harness = TestWorkflowEngine()
        val executionOrder = mutableListOf<String>()

        val wf = workflow("linear") {
            val a = task("step-a") { executionOrder.add("a"); "result-a" }
            val b = task("step-b", dependsOn(a)) {
                executionOrder.add("b"); "result-b-${it.output(a)}"
            }
            val c = task("step-c", dependsOn(b)) {
                executionOrder.add("c"); "result-c-${it.output(b)}"
            }
        }

        val engine = harness.createEngine(mapOf(wf.name to wf))
        val runId = engine.trigger(wf, "tenant-1")
        harness.runUntilComplete(engine, runId)

        assertEquals(listOf("a", "b", "c"), executionOrder)
        assertEquals(RunStatus.COMPLETED, harness.workflowRunRepo.findById(runId)!!.status)
    }

    @Test
    fun `diamond DAG with fan-out fan-in`() {
        val harness = TestWorkflowEngine()
        val executionOrder = Collections.synchronizedList(mutableListOf<String>())

        val wf = workflow("diamond") {
            val a = task("a") { executionOrder.add("a"); 1 }
            val b = task("b", dependsOn(a)) { executionOrder.add("b"); 2 }
            val c = task("c", dependsOn(a)) { executionOrder.add("c"); 3 }
            val d = task("d", dependsOn(b, c)) {
                executionOrder.add("d"); it.output(b) as Int + it.output(c) as Int
            }
        }

        val engine = harness.createEngine(mapOf(wf.name to wf))
        val runId = engine.trigger(wf, "tenant-1")
        harness.runUntilComplete(engine, runId)

        assertEquals("a", executionOrder.first())
        assertEquals("d", executionOrder.last())
        assertEquals(RunStatus.COMPLETED, harness.workflowRunRepo.findById(runId)!!.status)
    }

    @Test
    fun `durable sleep pauses and resumes workflow`() {
        val harness = TestWorkflowEngine()
        val executionOrder = mutableListOf<String>()

        val wf = workflow("sleep-test") {
            val a = task("before") { executionOrder.add("before"); "done" }
            val s = sleep("wait-24h", Duration.ofHours(24), dependsOn(a))
            task("after", dependsOn(s)) { executionOrder.add("after"); "done" }
        }

        val engine = harness.createEngine(mapOf(wf.name to wf))
        val runId = engine.trigger(wf, "tenant-1")

        // Execute until blocked on sleep
        harness.runUntilComplete(engine, runId)
        assertEquals(listOf("before"), executionOrder)
        assertEquals(TaskState.SLEEPING,
            harness.taskRepo.findByName(runId, "wait-24h")!!.status)

        // Advance clock past the sleep duration
        harness.clock.advance(Duration.ofHours(24).plusSeconds(1))

        // Continue execution
        harness.runUntilComplete(engine, runId)
        assertEquals(listOf("before", "after"), executionOrder)
        assertEquals(RunStatus.COMPLETED, harness.workflowRunRepo.findById(runId)!!.status)
    }

    @Test
    fun `retry with backoff on transient failure`() {
        val harness = TestWorkflowEngine()
        var attempts = 0

        val wf = workflow("retry-test") {
            task("flaky", retryPolicy = RetryPolicy(maxRetries = 2)) {
                attempts++
                if (attempts < 3) throw RuntimeException("Transient")
                "success"
            }
        }

        val engine = harness.createEngine(mapOf(wf.name to wf))
        val runId = engine.trigger(wf, "tenant-1")
        harness.runUntilComplete(engine, runId)

        assertEquals(3, attempts)
        assertEquals(RunStatus.COMPLETED, harness.workflowRunRepo.findById(runId)!!.status)
    }

    @Test
    fun `multi-tenant fairness - tasks interleaved`() {
        val harness = TestWorkflowEngine()
        val executionOrder = mutableListOf<String>()

        val wf = workflow("simple") {
            task("work") { ctx ->
                executionOrder.add(ctx.tenantId)
                "done"
            }
        }

        val engine = harness.createEngine(mapOf(wf.name to wf))

        // Tenant B enqueues 5 workflows, Tenant A enqueues 1
        repeat(5) { engine.trigger(wf, "tenant-B") }
        engine.trigger(wf, "tenant-A")

        harness.runUntilComplete(engine, UUID.randomUUID()) // run all

        // tenant-A should not be last (fairness)
        // With round-robin, tenant-A should appear within the first few executions
        val tenantAIndex = executionOrder.indexOf("tenant-A")
        assertTrue(tenantAIndex < 5, "tenant-A was at index $tenantAIndex, expected < 5")
    }
}
```

### 10.5 Testing Pyramid

```
                    ┌──────────────────────┐
                    │   Integration Tests   │  Real PostgreSQL (Testcontainers)
                    │   (~10 tests)         │  Verify: SKIP LOCKED, block-based IDs,
                    │                       │  advisory lock leader election,
                    │                       │  durable timer firing, fan-in atomicity
                    ├───────────────────────┤
                    │   Acceptance Tests     │  In-memory repos + FakeClock
                    │   (~50 tests)          │  Verify: DAG resolution, fan-out/in,
                    │                       │  retry, durable sleep, multi-tenant
                    │                       │  fairness, failure propagation
                    ├───────────────────────┤
                    │   Unit Tests           │  Isolated components
                    │   (~100 tests)         │  Verify: RetryPolicy, DAG builder,
                    │                       │  block-based ID computation,
                    │                       │  FakeClock, ManualScheduler
                    └───────────────────────┘
```

---

## 11. Resource Budget & Scaling

### 11.1 Per-Pod Resource Usage

| Component | Threads | Memory | DB Connections |
|---|---|---|---|
| Worker pool | 10 (configurable) | ~10 MB (thread stacks) | Up to 10 (from pool) |
| Task poller | 1 (shared scheduler) | ~1 KB | 1 per poll |
| Heartbeat | 1 (shared scheduler) | ~1 KB | 1 per beat |
| Timer poller (leader) | 1 (shared scheduler) | ~1 KB | 1 per poll |
| Housekeeper (leader) | 1 (shared scheduler) | ~1 KB | 1 per scan |
| Leader election | 0 (advisory lock) | ~1 KB | 1 dedicated (non-pooled) |
| **Total per pod** | **~13** | **~12 MB** | **3-11 active** |

### 11.2 Connection Pool Sizing

Using HikariCP formula `(core_count * 2) + effective_spindle_count`:

| Deployment | Cores | Pool Size | Leader Extra | Total |
|---|---|---|---|---|
| Development | 2 | 5 | 1 dedicated | 6 |
| Cloud Run (small) | 1 | 3 | 1 dedicated | 4 |
| Production (4-core) | 4 | 9 | 1 dedicated | 10 |

### 11.3 Scaling Characteristics

| Metric | 1 Pod | 3 Pods | 10 Pods |
|---|---|---|---|
| Concurrent task execution | 10 | 30 | 100 |
| DB connections (total) | 11 | 33 | 110 |
| Timer poll frequency | 5s | 5s (leader only) | 5s (leader only) |
| Dead execution detection | 2 min | 2 min | 2 min |
| Failover time (leader) | N/A | ~10s | ~10s |

### 11.4 Virtual Thread Optimization (Optional)

If the team wants to increase concurrency without adding threads:

```kotlin
// Replace fixed thread pool:
val workerPool = Executors.newFixedThreadPool(10)

// With virtual threads (Java 21+):
val workerPool = Executors.newVirtualThreadPerTaskExecutor()
```

Virtual threads allow thousands of concurrent tasks (each consuming ~5-10 KiB heap instead of ~1 MB stack). This is useful if tasks frequently block on I/O (HTTP calls, database queries). No code changes needed beyond the executor creation.

### 11.5 Throughput Estimates

| Bottleneck | Estimated Throughput | Notes |
|---|---|---|
| SKIP LOCKED dequeue | ~5,000-10,000 tasks/sec per PG | Limited by PG CPU and I/O |
| Task execution (10 threads, 100ms/task) | ~100 tasks/sec per pod | Limited by worker pool |
| Task execution (3 pods) | ~300 tasks/sec | Linear scaling |
| Timer polling (5s interval) | 100 timers/poll | Batched, single query |
| Heartbeat update | 1 UPDATE/30s per pod | Negligible |

---

## 12. Implementation Order

| Phase | What | Why First |
|---|---|---|
| **1. Interfaces + Data Classes** | All `*Repository` interfaces, `TaskRecord`, `QueueItem`, `WorkflowDefinition` DSL | Define the contracts before implementing anything |
| **2. In-Memory Repositories** | `InMemoryTaskRepository`, `InMemoryReadyQueueRepository`, `InMemoryTimerRepository`, etc. | Test doubles needed before engine implementation |
| **3. FakeClock + ManualScheduler** | Time control for tests | Required for acceptance tests |
| **4. DAG Runner** | `DagWorkflowEngine` core logic: trigger, poll, execute, resolve, retry | The heart of the system |
| **5. Acceptance Tests** | Linear DAG, diamond DAG, retry, durable sleep, multi-tenant fairness | Verify correctness against in-memory doubles |
| **6. PostgreSQL Schema** | DDL script + migration | Foundation for production repositories |
| **7. PostgreSQL Repositories** | `PostgresTaskRepository`, `PostgresReadyQueueRepository` with SKIP LOCKED, block-based enqueue | Production persistence layer |
| **8. Leader Election** | Advisory lock + timer poller + housekeeper | Required for multi-pod deployment |
| **9. Heartbeat + Dead Execution** | Heartbeat thread + housekeeper detection | Required for fault tolerance |
| **10. Graceful Shutdown** | Drain protocol + SIGTERM handler | Required for rolling deploys |
| **11. Integration Tests** | Testcontainers: SKIP LOCKED, block-based IDs, advisory locks, timer firing | Verify PostgreSQL-specific behavior |
| **12. Observability** | Event log queries, status API, metrics | Production readiness |

---

## 13. Dependency List

```kotlin
// build.gradle.kts
plugins {
    kotlin("jvm") version "2.1.10"
    kotlin("plugin.serialization") version "2.1.10"
}

dependencies {
    // Core
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // PostgreSQL
    implementation("org.postgresql:postgresql:42.7.4")
    implementation("com.zaxxer:HikariCP:6.2.1")

    // Testing
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("org.assertj:assertj-core:3.27.0")

    // Integration testing
    testImplementation("org.testcontainers:postgresql:1.20.4")
    testImplementation("org.testcontainers:junit-jupiter:1.20.4")
}
```

Note: **No `kotlinx-coroutines` dependency.** The entire engine uses standard Java concurrency (`java.util.concurrent`).

---

## Sources

### Hatchet
- [An unfair advantage: multi-tenant queues in Postgres (Hatchet Blog)](https://hatchet.run/blog/multi-tenant-queues)
- [abelanger5/postgres-fair-queue reference implementation](https://github.com/abelanger5/postgres-fair-queue)
- [Hatchet v1-core.sql Schema](https://github.com/hatchet-dev/hatchet/blob/main/sql/schema/v1-core.sql)
- [Hatchet Architecture](https://docs.hatchet.run/home/architecture)
- [Hatchet Durable Sleep](https://docs.hatchet.run/home/durable-sleep)
- [Hatchet Workers](https://docs.hatchet.run/home/workers)

### Multi-Tenant Queuing Alternatives
- [How we built a fair multi-tenant queuing system (Inngest)](https://www.inngest.com/blog/building-the-inngest-queue-pt-i-fairness-multi-tenancy)
- [Multi-Tenant Job Queue with PostgreSQL (Holistics)](https://www.holistics.io/blog/how-we-built-a-multi-tenant-job-queue-system-with-postgresql-ruby/)
- [Performance isolation in multi-tenant databases (Cloudflare)](https://blog.cloudflare.com/performance-isolation-in-a-multi-tenant-database-environment/)
- [2DFQ: Two-Dimensional Fair Queuing (Brown University)](https://cs.brown.edu/~jcmace/papers/mace162dfq.pdf)

### PostgreSQL Patterns
- [The Unreasonable Effectiveness of SKIP LOCKED](https://www.inferable.ai/blog/posts/postgres-skip-locked)
- [Implementing a Postgres job queue](https://aminediro.com/posts/pg_job_queue/)
- [Turning PostgreSQL into a queue (10K jobs/sec)](https://gist.github.com/chanks/7585810)
- [Queue System using SKIP LOCKED (Neon)](https://neon.com/guides/queue-system)
- [HikariCP Pool Sizing](https://github.com/brettwooldridge/HikariCP/wiki/About-Pool-Sizing)

### Distributed Coordination
- [PostgreSQL Advisory Locks for Leader Election](https://jeremydmiller.com/2020/05/05/using-postgresql-advisory-locks-for-leader-election/)
- [Leader Elections with Postgres Advisory Locks](https://ramitmittal.com/blog/general/leader-election-advisory-locks)
- [Orchestrating Distributed Tasks with Advisory Locks](https://leapcell.io/blog/orchestrating-distributed-tasks-with-postgresql-advisory-locks)

### Durable Execution
- [Absurd Workflows: Durable Execution With Just Postgres (Armin Ronacher)](https://lucumr.pocoo.org/2025/11/3/absurd-workflows/)
- [Why Postgres for Durable Execution (DBOS)](https://www.dbos.dev/blog/why-postgres-durable-execution)

### db-scheduler
- [kagkarlsson/db-scheduler GitHub](https://github.com/kagkarlsson/db-scheduler)
- [Task Schedulers in Java: Modern Alternatives to Quartz](https://foojay.io/today/task-schedulers-in-java-modern-alternatives-to-quartz-scheduler/)

### Graceful Shutdown
- [Kubernetes: Terminating with Grace (Google Cloud)](https://cloud.google.com/blog/products/containers-kubernetes/kubernetes-best-practices-terminating-with-grace)
- [Graceful Shutdown Handlers for Long-Running Processes](https://oneuptime.com/blog/post/2026-02-09-graceful-shutdown-handlers/view)

### Previous Research (This Project)
- [DAG Workflow Engine Design](./2026-02-11-dag-workflow-engine-design.md)
- [Durable Execution Research Report](./2026-02-11-093500-durable-execution-research.md)
- [Workflow Suspension Internals](./2026-02-11-workflow-suspension-internals.md)
- [In-Memory Testing Approaches](./2026-02-11-in-memory-testing-approaches.md)
