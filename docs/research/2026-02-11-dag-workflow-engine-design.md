# Hatchet-Style DAG Durable Workflow Engine in Kotlin: Design Research Report

> **Purpose**: This document provides the technical foundation for building a Hatchet-style DAG-based durable workflow engine in Kotlin using PostgreSQL. It presents two complete architectural options (with and without coroutines), evaluates db-scheduler as a backend, and defines the in-memory testability strategy. This will guide implementation.

---

## Table of Contents

1. [Design Goals & Constraints](#1-design-goals--constraints)
2. [Hatchet's DAG Engine Internals (Reference Architecture)](#2-hatchets-dag-engine-internals)
3. [db-scheduler Evaluation: Can We Use It?](#3-db-scheduler-evaluation)
4. [Option A: Coroutine-Based DAG Engine](#4-option-a-coroutine-based-dag-engine)
5. [Option B: Non-Coroutine DAG Engine (CompletableFuture + Thread Pool)](#5-option-b-non-coroutine-dag-engine)
6. [Shared PostgreSQL Schema](#6-shared-postgresql-schema)
7. [In-Memory Testing Architecture](#7-in-memory-testing-architecture)
8. [Resource Budget Analysis](#8-resource-budget-analysis)
9. [Comparison Matrix](#9-comparison-matrix)
10. [Recommendation](#10-recommendation)

---

## 1. Design Goals & Constraints

### Must-Have Requirements

| Requirement | Rationale |
|---|---|
| **Hatchet-style DAG orchestration** | Workflows defined as DAGs of typed tasks with explicit dependencies. No determinism constraints on user code. |
| **PostgreSQL-only storage** | No Redis, Kafka, RabbitMQ, or external servers. Single dependency beyond the JVM. |
| **Minimal resource consumption** | Small connection pool (3-9 connections), bounded thread usage, no thread-per-workflow. |
| **Fully testable in-memory** | All acceptance tests run without PostgreSQL, using in-memory doubles. Virtual time control. Tests complete in milliseconds. |
| **Library-embedded** | No external engine/server process. The workflow engine runs inside your application. |
| **At-least-once delivery** | Tasks are idempotent. Failed tasks are retried. Completed task outputs are cached. |

### Non-Goals (Explicitly Out of Scope)

- Multi-tenant fairness (Hatchet's block-based queuing)
- gRPC-based worker dispatch (we use in-process execution)
- Deterministic replay (we use DAG orchestration, not journal replay)
- Distributed workers across multiple processes (single-process library)
- Durable sleep surviving process restarts (can be added later)

### Architecture Principle: Hatchet's Key Insight

Hatchet's core design principle is **separation of orchestration from execution**:

```
Traditional (Temporal/DBOS):     Hatchet-style (DAG):
─────────────────────────────    ─────────────────────────
Workflow IS a function.          Workflow IS a graph.
The function runs continuously.  Tasks run independently.
State lives in the function.     State lives in the database.
Crash → replay the function.     Crash → re-dispatch the task.
Determinism required.            No determinism required.
```

The workflow function never "runs" as a long-lived process. Instead, the engine creates all tasks upfront, resolves dependencies reactively, and dispatches ready tasks to an executor. Between tasks, **zero application resources are consumed** — the workflow exists only as rows in PostgreSQL.

---

## 2. Hatchet's DAG Engine Internals

### 2.1 How Hatchet Resolves DAGs

Hatchet uses a **reactive match-condition system** rather than polling parent states:

```
Workflow Definition:
    step1 → step2a → step3
          → step2b ↗

Runtime Flow:
1. DAG created → all v1_task rows inserted in one batch
2. Root tasks (step1) → immediately queued in v1_queue_item
3. step1 COMPLETES → v1_task_event(COMPLETED) written
4. Engine evaluates v1_match_condition for step2a, step2b
5. Both conditions satisfied → step2a, step2b queued
6. step2a COMPLETES → match_condition for step3 partially satisfied
7. step2b COMPLETES → match_condition for step3 FULLY satisfied
8. step3 queued → dispatched → completes
9. All tasks terminal → DAG marked COMPLETED
```

The critical tables in Hatchet's schema:

| Table | Purpose |
|---|---|
| `v1_dag` | One row per workflow run. Links to workflow definition. |
| `v1_task` | One row per task in the DAG. Has `initial_state`, `step_index`, parent refs. Partitioned by day. |
| `v1_queue_item` | Hot dispatch table. Only contains tasks ready to execute. Kept small for fast reads. |
| `v1_task_event` | Append-only event log. COMPLETED, FAILED, CANCELLED, SIGNAL events. |
| `v1_match` / `v1_match_condition` | Reactive dependency resolution. Each parent→child edge is a condition. When all conditions satisfied, child is queued. |
| `v1_task_runtime` | Tracks which worker runs a task and its timeout. |
| `v1_payload` | Stores task inputs and outputs (JSONB inline or external). |
| `v1_retry_queue_item` | Failed tasks scheduled for retry with computed backoff time. |

### 2.2 Key Design Decisions from Hatchet

1. **BIGINT identity PKs, not UUIDs** — avoids index bloat on high-volume tables
2. **Daily range partitioning** on `inserted_at` — enables partition drops for data lifecycle
3. **Separate queue table from task table** — keeps the dispatch path fast
4. **Match conditions use CNF** (conjunctive normal form) — `OR` groups within `AND` across groups — enabling complex conditional logic
5. **Buffered writes every ~10ms** — reduces per-task transaction overhead
6. **PostgreSQL triggers for cascading operations** — task insert trigger populates queue, DAG-to-task junction, lookup table

### 2.3 What We Adopt vs. Simplify

| Hatchet Feature | Our Approach |
|---|---|
| Match condition system (v1_match) | **Simplify**: Use a `pending_count` column on each task. Decrement atomically when parent completes. When 0 → queue. |
| Block-based fair queuing | **Skip**: Not needed for single-tenant. Use simple `ORDER BY priority, created_at`. |
| Daily partitioning | **Skip initially**: Add later if data volume warrants it. |
| gRPC worker dispatch | **Replace**: In-process function dispatch via interface. |
| Separate v1_queue_item table | **Adopt**: Keep a separate ready-queue for fast dispatch. |
| v1_task_event log | **Adopt**: Append-only event log for observability and debugging. |
| v1_payload separate table | **Simplify**: Store input/output as JSONB directly on task row. |

---

## 3. db-scheduler Evaluation

### 3.1 What db-scheduler Provides

db-scheduler (v16.7.0) is a battle-tested Java persistent task scheduler using a **single database table**:

```sql
CREATE TABLE scheduled_tasks (
    task_name       TEXT NOT NULL,
    task_instance   TEXT NOT NULL,
    task_data       BYTEA,
    execution_time  TIMESTAMP WITH TIME ZONE NOT NULL,
    picked          BOOLEAN NOT NULL,
    picked_by       TEXT,
    last_success    TIMESTAMP WITH TIME ZONE,
    last_failure    TIMESTAMP WITH TIME ZONE,
    consecutive_failures INT,
    last_heartbeat  TIMESTAMP WITH TIME ZONE,
    version         BIGINT NOT NULL,
    priority        SMALLINT,
    PRIMARY KEY (task_name, task_instance)
);
```

**Proven capabilities:**
- Cluster coordination via optimistic locking (`version` column) or `SELECT FOR UPDATE SKIP LOCKED`
- Heartbeat-based dead execution detection (default: 5 min heartbeat, 30 min expiry)
- `CompletionHandler` mechanism: `OnCompleteRemove`, `OnCompleteReschedule`, `OnCompleteReplace`
- Task chaining via `OnCompleteReplace` (atomic single-task replacement)
- Spring Boot auto-configuration with `TransactionAwareDataSourceProxy`
- Throughput: 2,000-11,000 executions/sec depending on strategy and cores
- `KotlinSerializer` using `kotlinx.serialization` available in examples

### 3.2 Can db-scheduler Model a DAG?

| DAG Feature | db-scheduler Support | Assessment |
|---|---|---|
| Task chaining (A → B) | `OnCompleteReplace` (atomic) | **Native** |
| Fan-out (A → B, C, D) | `SchedulerClient.schedule()` from handler body | **Possible** but not atomic from CompletionHandler |
| Fan-in (B, C, D → E) | **Not supported** — no barrier primitive | **Must build externally** |
| Task output caching | Not built-in (task_data is for inputs) | **Must build externally** |
| DAG state tracking | No concept of DAG/workflow | **Must build externally** |
| Execution history | Tasks deleted on completion | **Must build externally** |
| Dynamic task types | All task types must be registered at startup | **Limiting** |

### 3.3 The Verdict: Wrap db-scheduler or Build Our Own?

**Option 1: Use db-scheduler as execution backend + add DAG tables**

```
Pros:
✓ Proven polling loop, heartbeat, dead execution recovery
✓ SKIP LOCKED already implemented
✓ Spring Boot integration works out of the box
✓ 10+ years of production use

Cons:
✗ Fan-in requires a separate barrier table (db-scheduler has no native support)
✗ CompletionHandler is NOT transactional for multi-task scheduling
✗ All task types must be statically registered — dynamic step names need a generic wrapper
✗ No execution history (tasks deleted on completion)
✗ task_data is BYTEA, not JSONB — can't query it
✗ Adding DAG tables means maintaining two parallel persistence models
✗ db-scheduler's polling interval (default 10s) adds latency between DAG steps
✗ Testing requires either real DB or mocking db-scheduler's internals (not designed for it)
```

**Option 2: Build our own using the same patterns**

```
Pros:
✓ Full control over schema (JSONB, proper DAG columns, fan-in barriers)
✓ Native fan-in via atomic pending_count decrement
✓ Transactional multi-task scheduling (fan-out)
✓ Execution history built into the schema
✓ JSONB payloads queryable via SQL
✓ Clean interface boundaries → easy in-memory doubles for testing
✓ No polling latency — immediate dispatch after parent completion
✓ Schema optimized for DAG patterns from the start

Cons:
✗ Must implement: polling loop, heartbeat, dead execution detection
✗ Must handle SKIP LOCKED correctly
✗ More code to write and maintain
✗ Risk of subtle concurrency bugs
```

### 3.4 Recommendation: Build Our Own, Borrowing db-scheduler's Patterns

The gap between what db-scheduler provides and what a DAG engine needs is too large. The fan-in barrier, execution history, DAG state tracking, and in-memory testability all require building significant infrastructure on top — at which point db-scheduler's polling loop becomes more of an impediment than a help (especially the 10s default latency between steps).

**However**, we should steal db-scheduler's best ideas:
- The single-table `scheduled_tasks` concept → our `ready_queue` table
- Optimistic locking via `version` column for state updates
- `SELECT FOR UPDATE SKIP LOCKED` for task claiming
- Heartbeat-based dead execution detection
- `CompletionHandler` concept → our `TaskCompletionStrategy` interface
- `enableImmediateExecution()` concept → immediate dispatch after parent completion

The implementation cost of the polling loop + heartbeat + dead execution detection is ~200-300 lines of Kotlin — manageable and worth the full control over DAG semantics.

---

## 4. Option A: Coroutine-Based DAG Engine

### 4.1 Architecture Overview

```
┌──────────────────────────────────────────────────────────┐
│                    Application Code                       │
│                                                          │
│  val workflow = workflow("order-processing") {            │
│      val validate = task("validate") { order ->           │
│          validateOrder(order)                              │
│      }                                                    │
│      val charge = task("charge", dependsOn(validate)) {   │
│          chargeCard(it.output(validate))                   │
│      }                                                    │
│      val ship = task("ship", dependsOn(charge)) {         │
│          shipOrder(it.output(charge))                      │
│      }                                                    │
│  }                                                        │
│                                                          │
│  engine.trigger(workflow, OrderInput("item-1", 99))       │
└──────────────────────┬───────────────────────────────────┘
                       │
┌──────────────────────▼───────────────────────────────────┐
│                   WorkflowEngine                          │
│                                                          │
│  ┌─────────────┐  ┌──────────────┐  ┌────────────────┐  │
│  │ DAG Builder │  │  DAG Runner   │  │  Task Executor  │  │
│  │ (DSL)       │  │  (resolve +   │  │  (run tasks     │  │
│  │             │  │   dispatch)   │  │   on dispatcher)│  │
│  └─────────────┘  └──────┬───────┘  └────────┬───────┘  │
│                          │                    │          │
│  ┌───────────────────────▼────────────────────▼───────┐  │
│  │              Repository Interfaces                  │  │
│  │                                                     │  │
│  │  WorkflowRepository   TaskRepository                │  │
│  │  QueueRepository      EventRepository               │  │
│  └─────────────────────────┬─────────────────────────┘  │
│                             │                            │
│         ┌───────────────────┼──────────────────┐         │
│         │ Production        │ Test             │         │
│         │ PostgreSQL impl   │ InMemory impl    │         │
│         └───────────────────┴──────────────────┘         │
└──────────────────────────────────────────────────────────┘
```

### 4.2 Core Interfaces

```kotlin
// ─── Workflow Definition DSL ─────────────────────────────

data class TaskDefinition<I, O>(
    val name: String,
    val parents: List<TaskDefinition<*, *>> = emptyList(),
    val retryPolicy: RetryPolicy = RetryPolicy.default(),
    val execute: suspend (TaskContext<I>) -> O,
)

data class WorkflowDefinition(
    val name: String,
    val tasks: List<TaskDefinition<*, *>>,
)

class WorkflowBuilder(val name: String) {
    private val tasks = mutableListOf<TaskDefinition<*, *>>()

    fun <I, O> task(
        name: String,
        parents: List<TaskDefinition<*, *>> = emptyList(),
        retryPolicy: RetryPolicy = RetryPolicy.default(),
        execute: suspend (TaskContext<I>) -> O,
    ): TaskDefinition<I, O> {
        val def = TaskDefinition(name, parents, retryPolicy, execute)
        tasks.add(def)
        return def
    }

    fun build(): WorkflowDefinition = WorkflowDefinition(name, tasks.toList())
}

fun workflow(name: String, block: WorkflowBuilder.() -> Unit): WorkflowDefinition =
    WorkflowBuilder(name).apply(block).build()

// ─── Task Context (available inside task execution) ──────

interface TaskContext<I> {
    val input: I
    val workflowRunId: UUID
    val taskName: String
    val retryCount: Int

    /** Read the output of a completed parent task. */
    fun <O> output(parent: TaskDefinition<*, O>): O
}

// ─── Engine API ──────────────────────────────────────────

interface WorkflowEngine {
    /** Start a workflow run. Returns the run ID. */
    suspend fun <I> trigger(
        workflow: WorkflowDefinition,
        input: I,
        workflowRunId: UUID = UUID.randomUUID(),
    ): UUID

    /** Get the current status of a workflow run. */
    suspend fun getStatus(workflowRunId: UUID): WorkflowRunStatus?

    /** Await completion and return all task outputs. */
    suspend fun awaitCompletion(
        workflowRunId: UUID,
        timeout: Duration = Duration.ofMinutes(5),
    ): WorkflowRunResult
}

data class WorkflowRunStatus(
    val workflowRunId: UUID,
    val workflowName: String,
    val status: RunStatus, // RUNNING, COMPLETED, FAILED, CANCELLED
    val taskStatuses: Map<String, TaskStatus>,
    val createdAt: Instant,
    val completedAt: Instant?,
)

enum class RunStatus { RUNNING, COMPLETED, FAILED, CANCELLED }

data class TaskStatus(
    val taskName: String,
    val status: TaskState, // PENDING, QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED, SKIPPED
    val retryCount: Int,
    val output: Any?,
    val error: String?,
)

enum class TaskState { PENDING, QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED, SKIPPED }
```

### 4.3 Repository Interfaces (The Testability Boundary)

```kotlin
// ─── Workflow Run Persistence ────────────────────────────

interface WorkflowRunRepository {
    suspend fun create(run: WorkflowRunRecord): Unit
    suspend fun findById(id: UUID): WorkflowRunRecord?
    suspend fun updateStatus(id: UUID, status: RunStatus, completedAt: Instant? = null): Unit
}

data class WorkflowRunRecord(
    val id: UUID,
    val workflowName: String,
    val status: RunStatus,
    val input: String, // serialized JSON
    val createdAt: Instant,
    val completedAt: Instant? = null,
)

// ─── Task Persistence ────────────────────────────────────

interface TaskRepository {
    /** Create all tasks for a DAG in one batch. */
    suspend fun createAll(tasks: List<TaskRecord>): Unit

    /** Find a task by workflow run ID and task name. */
    suspend fun findByName(workflowRunId: UUID, taskName: String): TaskRecord?

    /** Find all tasks for a workflow run. */
    suspend fun findAllByWorkflowRunId(workflowRunId: UUID): List<TaskRecord>

    /** Update task status and optionally set output/error. */
    suspend fun updateStatus(
        workflowRunId: UUID,
        taskName: String,
        status: TaskState,
        output: String? = null,
        error: String? = null,
        retryCount: Int? = null,
    ): Unit

    /**
     * Atomically decrement pending_parent_count for all children
     * of the given parent task. Returns tasks whose count reached 0
     * (i.e., all parents completed → ready to queue).
     */
    suspend fun decrementPendingParents(
        workflowRunId: UUID,
        parentTaskName: String,
    ): List<TaskRecord>
}

data class TaskRecord(
    val workflowRunId: UUID,
    val taskName: String,
    val status: TaskState,
    val parentNames: List<String>,
    val pendingParentCount: Int,
    val input: String?, // serialized JSON
    val output: String?, // serialized JSON
    val error: String?,
    val retryCount: Int = 0,
    val maxRetries: Int = 0,
    val priority: Int = 0,
    val createdAt: Instant,
    val startedAt: Instant? = null,
    val completedAt: Instant? = null,
)

// ─── Ready Queue ─────────────────────────────────────────

interface ReadyQueueRepository {
    /** Enqueue a task that is ready for execution. */
    suspend fun enqueue(item: QueueItem): Unit

    /** Enqueue multiple tasks atomically. */
    suspend fun enqueueAll(items: List<QueueItem>): Unit

    /**
     * Claim up to [limit] ready tasks. Uses SKIP LOCKED in production.
     * Returns claimed tasks (removed from queue).
     */
    suspend fun claim(limit: Int): List<QueueItem>
}

data class QueueItem(
    val workflowRunId: UUID,
    val taskName: String,
    val priority: Int = 0,
    val enqueuedAt: Instant,
)

// ─── Event Log ───────────────────────────────────────────

interface EventRepository {
    suspend fun append(event: TaskEvent): Unit
    suspend fun findByWorkflowRunId(workflowRunId: UUID): List<TaskEvent>
}

data class TaskEvent(
    val id: Long = 0,
    val workflowRunId: UUID,
    val taskName: String,
    val eventType: TaskEventType,
    val data: String? = null, // serialized JSON
    val timestamp: Instant,
)

enum class TaskEventType { QUEUED, STARTED, COMPLETED, FAILED, RETRYING, CANCELLED, SKIPPED }
```

### 4.4 DAG Runner (Coroutine-Based)

The core engine loop that resolves the DAG and dispatches tasks:

```kotlin
class CoroutineDagRunner(
    private val workflowRunRepo: WorkflowRunRepository,
    private val taskRepo: TaskRepository,
    private val queueRepo: ReadyQueueRepository,
    private val eventRepo: EventRepository,
    private val taskExecutor: TaskExecutor,
    private val clock: Clock = Clock.systemUTC(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val concurrency: Int = 10,
) : WorkflowEngine {

    private val semaphore = Semaphore(concurrency)

    override suspend fun <I> trigger(
        workflow: WorkflowDefinition,
        input: I,
        workflowRunId: UUID,
    ): UUID {
        // 1. Create workflow run record
        workflowRunRepo.create(WorkflowRunRecord(
            id = workflowRunId,
            workflowName = workflow.name,
            status = RunStatus.RUNNING,
            input = Json.encodeToString(serializer(), input),
            createdAt = Instant.now(clock),
        ))

        // 2. Create all task records in one batch
        val taskRecords = workflow.tasks.map { taskDef ->
            TaskRecord(
                workflowRunId = workflowRunId,
                taskName = taskDef.name,
                status = if (taskDef.parents.isEmpty()) TaskState.QUEUED else TaskState.PENDING,
                parentNames = taskDef.parents.map { it.name },
                pendingParentCount = taskDef.parents.size,
                input = null,
                output = null,
                error = null,
                maxRetries = taskDef.retryPolicy.maxRetries,
                createdAt = Instant.now(clock),
            )
        }
        taskRepo.createAll(taskRecords)

        // 3. Enqueue root tasks (those with no parents)
        val rootTasks = taskRecords.filter { it.pendingParentCount == 0 }
        queueRepo.enqueueAll(rootTasks.map { task ->
            QueueItem(workflowRunId, task.taskName, task.priority, Instant.now(clock))
        })

        // 4. Start the dispatch loop
        startDispatchLoop(workflowRunId, workflow)

        return workflowRunId
    }

    private fun startDispatchLoop(
        workflowRunId: UUID,
        workflow: WorkflowDefinition,
    ) {
        CoroutineScope(dispatcher + SupervisorJob()).launch {
            while (isActive) {
                val claimed = queueRepo.claim(concurrency)
                if (claimed.isEmpty()) {
                    // Check if workflow is complete
                    val tasks = taskRepo.findAllByWorkflowRunId(workflowRunId)
                    val allTerminal = tasks.all { it.status in terminalStates }
                    if (allTerminal) {
                        val anyFailed = tasks.any { it.status == TaskState.FAILED }
                        val finalStatus = if (anyFailed) RunStatus.FAILED else RunStatus.COMPLETED
                        workflowRunRepo.updateStatus(workflowRunId, finalStatus, Instant.now(clock))
                        break
                    }
                    delay(50) // Brief pause before re-checking
                    continue
                }

                for (item in claimed) {
                    launch {
                        semaphore.withPermit {
                            executeAndResolve(workflowRunId, item.taskName, workflow)
                        }
                    }
                }
            }
        }
    }

    private suspend fun executeAndResolve(
        workflowRunId: UUID,
        taskName: String,
        workflow: WorkflowDefinition,
    ) {
        val taskDef = workflow.tasks.first { it.name == taskName }

        // Mark as RUNNING
        taskRepo.updateStatus(workflowRunId, taskName, TaskState.RUNNING)
        eventRepo.append(TaskEvent(
            workflowRunId = workflowRunId,
            taskName = taskName,
            eventType = TaskEventType.STARTED,
            timestamp = Instant.now(clock),
        ))

        try {
            // Build context with parent outputs
            val parentOutputs = taskDef.parents.associate { parent ->
                val parentRecord = taskRepo.findByName(workflowRunId, parent.name)!!
                parent.name to parentRecord.output
            }
            val context = TaskContextImpl(workflowRunId, taskName, parentOutputs)

            // Execute the task
            val result = taskExecutor.execute(taskDef, context)
            val outputJson = Json.encodeToString(serializer(), result)

            // Mark COMPLETED
            taskRepo.updateStatus(workflowRunId, taskName, TaskState.COMPLETED, output = outputJson)
            eventRepo.append(TaskEvent(
                workflowRunId = workflowRunId,
                taskName = taskName,
                eventType = TaskEventType.COMPLETED,
                data = outputJson,
                timestamp = Instant.now(clock),
            ))

            // Resolve children: atomically decrement pending counts
            val readyChildren = taskRepo.decrementPendingParents(workflowRunId, taskName)
            if (readyChildren.isNotEmpty()) {
                queueRepo.enqueueAll(readyChildren.map { child ->
                    QueueItem(workflowRunId, child.taskName, child.priority, Instant.now(clock))
                })
            }

        } catch (e: Exception) {
            handleFailure(workflowRunId, taskName, taskDef, e)
        }
    }

    private suspend fun handleFailure(
        workflowRunId: UUID,
        taskName: String,
        taskDef: TaskDefinition<*, *>,
        error: Exception,
    ) {
        val task = taskRepo.findByName(workflowRunId, taskName)!!

        if (error is TerminalError || task.retryCount >= taskDef.retryPolicy.maxRetries) {
            // Permanent failure
            taskRepo.updateStatus(workflowRunId, taskName, TaskState.FAILED,
                error = error.message, retryCount = task.retryCount)
            eventRepo.append(TaskEvent(
                workflowRunId = workflowRunId,
                taskName = taskName,
                eventType = TaskEventType.FAILED,
                data = error.message,
                timestamp = Instant.now(clock),
            ))
        } else {
            // Retry with backoff
            val newRetryCount = task.retryCount + 1
            val backoffMs = taskDef.retryPolicy.calculateDelayMs(newRetryCount)
            taskRepo.updateStatus(workflowRunId, taskName, TaskState.QUEUED,
                retryCount = newRetryCount)
            eventRepo.append(TaskEvent(
                workflowRunId = workflowRunId,
                taskName = taskName,
                eventType = TaskEventType.RETRYING,
                data = """{"retryCount":$newRetryCount,"backoffMs":$backoffMs}""",
                timestamp = Instant.now(clock),
            ))
            delay(backoffMs)
            queueRepo.enqueue(QueueItem(workflowRunId, taskName, task.priority + 1, Instant.now(clock)))
        }
    }

    companion object {
        private val terminalStates = setOf(
            TaskState.COMPLETED, TaskState.FAILED, TaskState.CANCELLED, TaskState.SKIPPED
        )
    }
}
```

### 4.5 Task Executor Interface

```kotlin
interface TaskExecutor {
    suspend fun <I, O> execute(
        taskDef: TaskDefinition<I, O>,
        context: TaskContext<I>,
    ): O
}

class DefaultTaskExecutor : TaskExecutor {
    override suspend fun <I, O> execute(
        taskDef: TaskDefinition<I, O>,
        context: TaskContext<I>,
    ): O {
        return taskDef.execute(context)
    }
}
```

### 4.6 Resource Characteristics

| Resource | At Rest (no workflows) | 1,000 queued tasks | 1,000 sleeping between steps |
|---|---|---|---|
| OS threads | 0 (coroutine dispatcher idle) | `min(concurrency, coreCount)` | 0 |
| Heap memory | ~100 KB (engine objects) | ~10 MB (1K coroutines + contexts) | ~0 (state in DB) |
| DB connections | 0 (HikariCP idle) | `min(concurrency, poolSize)` | 0 |
| Coroutines active | 1 (dispatch loop) | `min(concurrency, 1000)` | 0 |

**Key advantage**: Between DAG steps, the workflow consumes ZERO application resources. The state exists only as rows in PostgreSQL (or in the in-memory store during tests). This is the Hatchet model.

---

## 5. Option B: Non-Coroutine DAG Engine

### 5.1 Architecture Overview

Uses `CompletableFuture`, a fixed-size `ExecutorService`, and callback-driven resolution. No Kotlin coroutine dependency.

```kotlin
class ThreadPoolDagRunner(
    private val workflowRunRepo: WorkflowRunRepository,
    private val taskRepo: TaskRepository,
    private val queueRepo: ReadyQueueRepository,
    private val eventRepo: EventRepository,
    private val taskExecutor: BlockingTaskExecutor,
    private val clock: Clock = Clock.systemUTC(),
    private val executor: ExecutorService = Executors.newFixedThreadPool(10),
    private val pollIntervalMs: Long = 100,
) : WorkflowEngine {

    private val scheduler = Executors.newSingleThreadScheduledExecutor()

    override fun trigger(
        workflow: WorkflowDefinition,
        input: Any?,
        workflowRunId: UUID,
    ): UUID {
        // 1. Create workflow run + tasks (same as coroutine version, but blocking)
        createWorkflowRun(workflowRunId, workflow, input)
        createTasks(workflowRunId, workflow)
        enqueueRootTasks(workflowRunId, workflow)

        // 2. Start polling loop
        startPollingLoop(workflowRunId, workflow)

        return workflowRunId
    }

    private fun startPollingLoop(workflowRunId: UUID, workflow: WorkflowDefinition) {
        scheduler.scheduleAtFixedRate({
            try {
                val claimed = queueRepo.claimBlocking(10)
                for (item in claimed) {
                    executor.submit {
                        executeAndResolveBlocking(workflowRunId, item.taskName, workflow)
                    }
                }

                // Check completion
                if (claimed.isEmpty()) {
                    val tasks = taskRepo.findAllByWorkflowRunIdBlocking(workflowRunId)
                    if (tasks.all { it.status in terminalStates }) {
                        val anyFailed = tasks.any { it.status == TaskState.FAILED }
                        val finalStatus = if (anyFailed) RunStatus.FAILED else RunStatus.COMPLETED
                        workflowRunRepo.updateStatusBlocking(workflowRunId, finalStatus, Instant.now(clock))
                        // Cancel the polling loop
                    }
                }
            } catch (e: Exception) {
                // Log and continue
            }
        }, 0, pollIntervalMs, TimeUnit.MILLISECONDS)
    }

    private fun executeAndResolveBlocking(
        workflowRunId: UUID,
        taskName: String,
        workflow: WorkflowDefinition,
    ) {
        val taskDef = workflow.tasks.first { it.name == taskName }

        taskRepo.updateStatusBlocking(workflowRunId, taskName, TaskState.RUNNING)

        try {
            val parentOutputs = buildParentOutputs(workflowRunId, taskDef)
            val context = BlockingTaskContextImpl(workflowRunId, taskName, parentOutputs)
            val result = taskExecutor.execute(taskDef, context)
            val outputJson = Json.encodeToString(serializer(), result)

            taskRepo.updateStatusBlocking(workflowRunId, taskName, TaskState.COMPLETED, output = outputJson)

            // Resolve children
            val readyChildren = taskRepo.decrementPendingParentsBlocking(workflowRunId, taskName)
            if (readyChildren.isNotEmpty()) {
                queueRepo.enqueueAllBlocking(readyChildren.map { child ->
                    QueueItem(workflowRunId, child.taskName, child.priority, Instant.now(clock))
                })
            }

        } catch (e: Exception) {
            handleFailureBlocking(workflowRunId, taskName, taskDef, e)
        }
    }
}
```

### 5.2 Blocking Repository Interfaces

The non-coroutine version needs blocking variants of the repository interfaces:

```kotlin
// Option 1: Separate blocking interfaces
interface BlockingTaskRepository {
    fun createAll(tasks: List<TaskRecord>): Unit
    fun findByName(workflowRunId: UUID, taskName: String): TaskRecord?
    fun updateStatus(workflowRunId: UUID, taskName: String, status: TaskState, ...): Unit
    fun decrementPendingParents(workflowRunId: UUID, parentTaskName: String): List<TaskRecord>
}

// Option 2: Single interface with both (for shared in-memory impl)
// The suspend functions are trivially wrappable with runBlocking for non-coroutine use
```

### 5.3 Task Executor (Blocking)

```kotlin
interface BlockingTaskExecutor {
    fun <I, O> execute(taskDef: TaskDefinition<I, O>, context: BlockingTaskContext<I>): O
}

interface BlockingTaskContext<I> {
    val input: I
    val workflowRunId: UUID
    val taskName: String
    val retryCount: Int
    fun <O> output(parent: TaskDefinition<*, O>): O
}
```

### 5.4 Resource Characteristics

| Resource | At Rest | 1,000 queued tasks | 1,000 between steps |
|---|---|---|---|
| OS threads | 11 (executor pool + scheduler) | 10 (fixed pool) + 1 scheduler | 11 (idle but allocated) |
| Heap memory | ~2 MB (thread stacks) | ~10 MB (thread stacks + task data) | ~2 MB (idle threads) |
| DB connections | 0 (idle) | `min(10, poolSize)` | 0 |

**Key difference from coroutine version**: The thread pool is allocated upfront (10 threads) even when idle. With coroutines, threads are shared across the entire application and only used when actively executing.

### 5.5 Time Control in Tests (Non-Coroutine)

Without coroutines, time control uses `java.time.Clock`:

```kotlin
class FakeClock(
    private var now: Instant,
    private val zone: ZoneId = ZoneOffset.UTC,
) : Clock() {
    override fun instant(): Instant = now
    override fun withZone(zone: ZoneId): Clock = FakeClock(now, zone)
    override fun getZone(): ZoneId = zone

    fun advance(duration: Duration) {
        now = now.plus(duration)
    }
}

// For retry backoff in tests, replace Thread.sleep with a clock-aware wait:
class TestableRetryPolicy(private val clock: Clock) {
    fun waitForBackoff(delayMs: Long) {
        // In tests: no-op (FakeClock advances instantly)
        // In production: Thread.sleep(delayMs)
    }
}
```

**Limitation**: `FakeClock` doesn't automatically trigger scheduled tasks. You must manually pump the event loop in tests. This makes test code more verbose than the coroutine version where `advanceTimeBy()` runs all pending coroutines.

---

## 6. Shared PostgreSQL Schema

Both options use the same database schema:

```sql
-- ═══════════════════════════════════════════════════════════
-- Workflow Runs: One row per triggered workflow
-- ═══════════════════════════════════════════════════════════
CREATE TABLE workflow_runs (
    id                  UUID PRIMARY KEY,
    workflow_name       TEXT NOT NULL,
    status              TEXT NOT NULL DEFAULT 'RUNNING',
        -- RUNNING | COMPLETED | FAILED | CANCELLED
    input               JSONB NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at        TIMESTAMPTZ
);

CREATE INDEX idx_workflow_runs_status ON workflow_runs (status)
    WHERE status = 'RUNNING';

-- ═══════════════════════════════════════════════════════════
-- Tasks: One row per task in a DAG. The core state table.
-- ═══════════════════════════════════════════════════════════
CREATE TABLE tasks (
    workflow_run_id     UUID NOT NULL REFERENCES workflow_runs(id),
    task_name           TEXT NOT NULL,
    status              TEXT NOT NULL DEFAULT 'PENDING',
        -- PENDING | QUEUED | RUNNING | COMPLETED | FAILED | CANCELLED | SKIPPED
    parent_names        TEXT[] NOT NULL DEFAULT '{}',
    pending_parent_count INT NOT NULL DEFAULT 0,
    input               JSONB,
    output              JSONB,
    error               TEXT,
    retry_count         INT NOT NULL DEFAULT 0,
    max_retries         INT NOT NULL DEFAULT 0,
    priority            INT NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at          TIMESTAMPTZ,
    completed_at        TIMESTAMPTZ,
    PRIMARY KEY (workflow_run_id, task_name)
);

CREATE INDEX idx_tasks_pending ON tasks (workflow_run_id, status)
    WHERE status IN ('PENDING', 'QUEUED', 'RUNNING');

-- ═══════════════════════════════════════════════════════════
-- Ready Queue: Tasks that are ready for dispatch.
-- Kept separate from tasks table for fast claiming.
-- ═══════════════════════════════════════════════════════════
CREATE TABLE ready_queue (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    workflow_run_id     UUID NOT NULL,
    task_name           TEXT NOT NULL,
    priority            INT NOT NULL DEFAULT 0,
    enqueued_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (workflow_run_id, task_name) REFERENCES tasks(workflow_run_id, task_name)
);

CREATE INDEX idx_ready_queue_dispatch ON ready_queue (priority DESC, id ASC);

-- ═══════════════════════════════════════════════════════════
-- Task Events: Append-only log for observability.
-- ═══════════════════════════════════════════════════════════
CREATE TABLE task_events (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY,
    workflow_run_id     UUID NOT NULL,
    task_name           TEXT NOT NULL,
    event_type          TEXT NOT NULL,
        -- QUEUED | STARTED | COMPLETED | FAILED | RETRYING | CANCELLED | SKIPPED
    data                JSONB,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (workflow_run_id, task_name, id)
);

-- ═══════════════════════════════════════════════════════════
-- Key Operations
-- ═══════════════════════════════════════════════════════════

-- Claim tasks from ready queue (SKIP LOCKED):
-- WITH claimed AS (
--     SELECT id, workflow_run_id, task_name
--     FROM ready_queue
--     ORDER BY priority DESC, id ASC
--     FOR UPDATE SKIP LOCKED
--     LIMIT $1
-- )
-- DELETE FROM ready_queue
-- USING claimed
-- WHERE ready_queue.id = claimed.id
-- RETURNING claimed.*;

-- Decrement pending parents and return newly ready tasks:
-- UPDATE tasks
-- SET pending_parent_count = pending_parent_count - 1
-- WHERE workflow_run_id = $1
--   AND $2 = ANY(parent_names)
--   AND status = 'PENDING'
-- RETURNING *;
-- (Then, for rows where pending_parent_count = 0, update status to QUEUED and insert into ready_queue)
```

### 6.1 The Fan-In Mechanism: `pending_parent_count`

This is simpler than Hatchet's match condition system but achieves the same result:

```
DAG: A → C, B → C  (C depends on both A and B)

1. Tasks created:
   A: pending_parent_count = 0, status = QUEUED
   B: pending_parent_count = 0, status = QUEUED
   C: pending_parent_count = 2, status = PENDING

2. A completes:
   UPDATE tasks SET pending_parent_count = pending_parent_count - 1
   WHERE workflow_run_id = $1 AND 'A' = ANY(parent_names) AND status = 'PENDING'
   → C.pending_parent_count = 1 (not yet ready)

3. B completes:
   UPDATE tasks SET pending_parent_count = pending_parent_count - 1
   WHERE workflow_run_id = $1 AND 'B' = ANY(parent_names) AND status = 'PENDING'
   → C.pending_parent_count = 0 → QUEUED → inserted into ready_queue
```

The `pending_parent_count` decrement is atomic (single UPDATE). Concurrent parent completions are safe because PostgreSQL serializes writes to the same row. The `RETURNING *` clause lets us immediately identify newly ready tasks.

---

## 7. In-Memory Testing Architecture

### 7.1 Design Principle

Every external dependency is behind an interface. Tests inject in-memory implementations:

```
Production:                          Test:
───────────────                      ─────
PostgresWorkflowRunRepository        InMemoryWorkflowRunRepository
PostgresTaskRepository               InMemoryTaskRepository
PostgresReadyQueueRepository         InMemoryReadyQueueRepository
PostgresEventRepository              InMemoryEventRepository
Clock.systemUTC()                    VirtualClock(testScheduler)
Dispatchers.Default                  StandardTestDispatcher(testScheduler)
```

### 7.2 In-Memory Repository Implementations

```kotlin
class InMemoryTaskRepository : TaskRepository {
    private val tasks = ConcurrentHashMap<Pair<UUID, String>, TaskRecord>()

    override suspend fun createAll(tasks: List<TaskRecord>) {
        for (task in tasks) {
            this.tasks[task.workflowRunId to task.taskName] = task
        }
    }

    override suspend fun findByName(workflowRunId: UUID, taskName: String): TaskRecord? =
        tasks[workflowRunId to taskName]

    override suspend fun findAllByWorkflowRunId(workflowRunId: UUID): List<TaskRecord> =
        tasks.values.filter { it.workflowRunId == workflowRunId }

    override suspend fun updateStatus(
        workflowRunId: UUID,
        taskName: String,
        status: TaskState,
        output: String?,
        error: String?,
        retryCount: Int?,
    ) {
        tasks.compute(workflowRunId to taskName) { _, existing ->
            existing?.copy(
                status = status,
                output = output ?: existing.output,
                error = error ?: existing.error,
                retryCount = retryCount ?: existing.retryCount,
            )
        }
    }

    override suspend fun decrementPendingParents(
        workflowRunId: UUID,
        parentTaskName: String,
    ): List<TaskRecord> {
        val readyTasks = mutableListOf<TaskRecord>()
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
        return readyTasks
    }

    fun reset() = tasks.clear()
}

class InMemoryReadyQueueRepository : ReadyQueueRepository {
    private val queue = PriorityBlockingQueue<QueueItem>(
        100,
        compareByDescending<QueueItem> { it.priority }.thenBy { it.enqueuedAt }
    )

    override suspend fun enqueue(item: QueueItem) { queue.add(item) }

    override suspend fun enqueueAll(items: List<QueueItem>) { queue.addAll(items) }

    override suspend fun claim(limit: Int): List<QueueItem> {
        val claimed = mutableListOf<QueueItem>()
        repeat(limit) {
            queue.poll()?.let { claimed.add(it) } ?: return claimed
        }
        return claimed
    }

    fun reset() = queue.clear()
}

class InMemoryWorkflowRunRepository : WorkflowRunRepository {
    private val runs = ConcurrentHashMap<UUID, WorkflowRunRecord>()

    override suspend fun create(run: WorkflowRunRecord) { runs[run.id] = run }
    override suspend fun findById(id: UUID): WorkflowRunRecord? = runs[id]
    override suspend fun updateStatus(id: UUID, status: RunStatus, completedAt: Instant?) {
        runs.compute(id) { _, existing ->
            existing?.copy(status = status, completedAt = completedAt)
        }
    }

    fun reset() = runs.clear()
}

class InMemoryEventRepository : EventRepository {
    private val events = mutableListOf<TaskEvent>()
    private var nextId = 1L

    override suspend fun append(event: TaskEvent) {
        synchronized(events) {
            events.add(event.copy(id = nextId++))
        }
    }

    override suspend fun findByWorkflowRunId(workflowRunId: UUID): List<TaskEvent> =
        synchronized(events) { events.filter { it.workflowRunId == workflowRunId } }

    fun reset() = synchronized(events) { events.clear(); nextId = 1L }
}
```

### 7.3 VirtualClock Bridge (Coroutine Option)

Bridges `kotlinx-coroutines-test` virtual time to `java.time.Clock`:

```kotlin
class VirtualClock(
    private val testScheduler: TestCoroutineScheduler,
    private val baseInstant: Instant = Instant.parse("2026-01-01T00:00:00Z"),
    private val zone: ZoneId = ZoneOffset.UTC,
) : Clock() {
    override fun instant(): Instant =
        baseInstant.plusMillis(testScheduler.currentTime)

    override fun withZone(zone: ZoneId): Clock =
        VirtualClock(testScheduler, baseInstant, zone)

    override fun getZone(): ZoneId = zone
}
```

### 7.4 Test Harness

```kotlin
class TestWorkflowEngine(
    private val testScope: TestScope,
) {
    val workflowRunRepo = InMemoryWorkflowRunRepository()
    val taskRepo = InMemoryTaskRepository()
    val queueRepo = InMemoryReadyQueueRepository()
    val eventRepo = InMemoryEventRepository()
    val clock = VirtualClock(testScope.testScheduler)

    private val engine = CoroutineDagRunner(
        workflowRunRepo = workflowRunRepo,
        taskRepo = taskRepo,
        queueRepo = queueRepo,
        eventRepo = eventRepo,
        taskExecutor = DefaultTaskExecutor(),
        clock = clock,
        dispatcher = StandardTestDispatcher(testScope.testScheduler),
    )

    suspend fun <I> trigger(workflow: WorkflowDefinition, input: I): UUID =
        engine.trigger(workflow, input)

    suspend fun awaitCompletion(workflowRunId: UUID): WorkflowRunResult =
        engine.awaitCompletion(workflowRunId)

    fun advanceTime(duration: Duration) =
        testScope.testScheduler.advanceTimeBy(duration.toMillis())

    fun advanceUntilIdle() =
        testScope.testScheduler.advanceUntilIdle()

    fun reset() {
        workflowRunRepo.reset()
        taskRepo.reset()
        queueRepo.reset()
        eventRepo.reset()
    }
}
```

### 7.5 Example Acceptance Tests

```kotlin
class WorkflowAcceptanceTest {

    @Test
    fun `linear DAG executes in order`() = runTest {
        val engine = TestWorkflowEngine(this)

        val wf = workflow("linear") {
            val a = task<Unit, String>("step-a") { "result-a" }
            val b = task<Unit, String>("step-b", dependsOn(a)) {
                "result-b-${it.output(a)}"
            }
            val c = task<Unit, String>("step-c", dependsOn(b)) {
                "result-c-${it.output(b)}"
            }
        }

        val runId = engine.trigger(wf, Unit)
        engine.advanceUntilIdle()

        val status = engine.workflowRunRepo.findById(runId)!!
        assertEquals(RunStatus.COMPLETED, status.status)

        val taskC = engine.taskRepo.findByName(runId, "step-c")!!
        assertEquals(""""result-c-result-b-result-a"""", taskC.output)
    }

    @Test
    fun `fan-out fan-in DAG executes correctly`() = runTest {
        val engine = TestWorkflowEngine(this)
        val executionOrder = mutableListOf<String>()

        val wf = workflow("diamond") {
            val a = task<Unit, Int>("a") { executionOrder.add("a"); 1 }
            val b = task<Unit, Int>("b", dependsOn(a)) { executionOrder.add("b"); 2 }
            val c = task<Unit, Int>("c", dependsOn(a)) { executionOrder.add("c"); 3 }
            val d = task<Unit, Int>("d", dependsOn(b, c)) {
                executionOrder.add("d")
                it.output(b) + it.output(c) // 2 + 3 = 5
            }
        }

        val runId = engine.trigger(wf, Unit)
        engine.advanceUntilIdle()

        // a must be first, d must be last, b and c can be in either order
        assertEquals("a", executionOrder.first())
        assertEquals("d", executionOrder.last())
        assertTrue(executionOrder.indexOf("b") < executionOrder.indexOf("d"))
        assertTrue(executionOrder.indexOf("c") < executionOrder.indexOf("d"))

        val taskD = engine.taskRepo.findByName(runId, "d")!!
        assertEquals("5", taskD.output)
    }

    @Test
    fun `task retries on transient failure`() = runTest {
        val engine = TestWorkflowEngine(this)
        var attempts = 0

        val wf = workflow("retry-test") {
            task<Unit, String>("flaky", retryPolicy = RetryPolicy(maxRetries = 2)) {
                attempts++
                if (attempts < 3) throw RuntimeException("Transient")
                "success"
            }
        }

        val runId = engine.trigger(wf, Unit)
        engine.advanceUntilIdle()

        assertEquals(3, attempts)
        val status = engine.workflowRunRepo.findById(runId)!!
        assertEquals(RunStatus.COMPLETED, status.status)
    }

    @Test
    fun `task output is cached and not re-executed on recovery`() = runTest {
        val engine = TestWorkflowEngine(this)
        var step1Count = 0

        val wf = workflow("recovery-test") {
            val a = task<Unit, String>("step-1") { step1Count++; "cached" }
            task<Unit, String>("step-2", dependsOn(a)) {
                throw RuntimeException("Crash!")
            }
        }

        val runId = engine.trigger(wf, Unit)
        engine.advanceUntilIdle()

        assertEquals(1, step1Count)
        // step-1 output is persisted
        val step1 = engine.taskRepo.findByName(runId, "step-1")!!
        assertEquals(TaskState.COMPLETED, step1.status)
        assertNotNull(step1.output)
    }

    @Test
    fun `workflow completes in milliseconds with virtual time`() = runTest {
        val engine = TestWorkflowEngine(this)
        val start = System.nanoTime()

        val wf = workflow("fast-test") {
            val a = task<Unit, String>("a") { "hello" }
            val b = task<Unit, String>("b") { "world" }
            task<Unit, String>("c", dependsOn(a, b)) {
                "${it.output(a)} ${it.output(b)}"
            }
        }

        engine.trigger(wf, Unit)
        engine.advanceUntilIdle()

        val wallTimeMs = (System.nanoTime() - start) / 1_000_000
        assertTrue(wallTimeMs < 500, "Expected fast test, took ${wallTimeMs}ms")
    }
}
```

### 7.6 Testing Pyramid

```
                    ┌──────────────────────┐
                    │   Integration Tests   │  Real PostgreSQL (Testcontainers)
                    │   (~5 tests)          │  Verify: SKIP LOCKED, atomic decrement,
                    │                       │          JSONB queries, index usage
                    ├───────────────────────┤
                    │   Acceptance Tests     │  In-memory repos + virtual time
                    │   (~50 tests)          │  Verify: DAG resolution, fan-out/in,
                    │                       │          retry, failure propagation,
                    │                       │          idempotency, recovery
                    ├───────────────────────┤
                    │   Unit Tests           │  Isolated components
                    │   (~100 tests)         │  Verify: serialization, retry policy,
                    │                       │          DAG builder, queue ordering
                    └───────────────────────┘
```

---

## 8. Resource Budget Analysis

### 8.1 PostgreSQL Connection Pool

Using HikariCP with the formula `(core_count * 2) + effective_spindle_count`:

| Deployment | Cores | Pool Size | Rationale |
|---|---|---|---|
| Development | 2 | 3 | Minimum viable: 1 for poll, 1 for task, 1 for DAG ops |
| Production (small) | 4 | 9 | `(4 * 2) + 1 = 9` |
| Production (large) | 8 | 17 | `(8 * 2) + 1 = 17` |

### 8.2 Thread Budget (Option A: Coroutines)

| Component | Threads Used | Notes |
|---|---|---|
| Dispatchers.Default | `coreCount` (shared) | Shared with entire app |
| Dispatchers.IO | Up to 64 (shared) | For blocking JDBC calls |
| HikariCP | 0 dedicated | Uses calling thread |
| Total exclusive | **0** | All shared with the application |

### 8.3 Thread Budget (Option B: Thread Pool)

| Component | Threads Used | Notes |
|---|---|---|
| Task executor pool | 10 (dedicated) | Fixed, always allocated |
| Polling scheduler | 1 (dedicated) | Always running |
| HikariCP | 0 dedicated | Uses calling thread |
| Total exclusive | **11** | Dedicated to the engine |

### 8.4 Memory Budget

| Component | Option A | Option B |
|---|---|---|
| Engine objects | ~100 KB | ~100 KB |
| Thread stacks (idle) | 0 | ~11 MB (11 threads × 1 MB) |
| Per-active-task overhead | ~5 KB (coroutine) | ~1 MB (thread stack) |
| 100 concurrent tasks | ~500 KB | Bounded by pool (10) |
| 1,000 tasks between steps | ~0 | ~0 (state in DB) |

---

## 9. Comparison Matrix

| Dimension | Option A: Coroutines | Option B: Thread Pool |
|---|---|---|
| **Runtime dependency** | `kotlinx-coroutines` | None beyond stdlib |
| **Thread usage** | Shared, dynamic | Dedicated, fixed |
| **Memory per idle workflow** | 0 (state in DB) | 0 (state in DB) |
| **Memory per active task** | ~5 KB (coroutine) | ~1 MB (thread stack) |
| **Max concurrent tasks** | Configurable semaphore, can be very high | Fixed by pool size |
| **Test time control** | `advanceTimeBy()`, `advanceUntilIdle()` — automatic | `FakeClock.advance()` — manual, more verbose |
| **Retry backoff in tests** | `delay()` responds to virtual time | Must mock `Thread.sleep()` or use callback |
| **Code complexity** | Lower (structured concurrency) | Higher (callbacks, futures, manual coordination) |
| **Cancellation** | Native (structured concurrency) | Must implement manually (Future.cancel) |
| **Error propagation** | `try/catch` with suspending functions | Callback-based, Future.exceptionally |
| **Integration with Spring** | Works via `suspend fun` controllers | Standard blocking |
| **Integration with db-scheduler** | Possible but redundant | Natural fit (same threading model) |
| **Learning curve** | Requires coroutine knowledge | Standard Java patterns |
| **Debugging** | Coroutine stack traces (improving) | Standard stack traces |

---

## 10. Recommendation

### Primary Recommendation: Option A (Coroutine-Based)

The coroutine-based approach is recommended for the following reasons:

1. **Resource efficiency**: Zero dedicated threads. In a Hatchet-style DAG engine, workflows spend most of their time between tasks (in the database). Coroutines cost nothing when suspended. The thread pool approach wastes 11 threads sitting idle.

2. **Test ergonomics**: `kotlinx-coroutines-test` provides `advanceTimeBy()` and `advanceUntilIdle()` which automatically advance virtual time through `delay()` calls (used for retry backoff). The non-coroutine version requires manual clock pumping and cannot automatically trigger scheduled retries.

3. **Natural cancellation**: Structured concurrency means cancelling a workflow automatically cancels all in-flight tasks. The thread pool version needs manual `Future.cancel()` propagation.

4. **Simpler code**: The DAG runner is ~150 lines with coroutines vs ~250 lines without. Error handling with `try/catch` is cleaner than `CompletableFuture.exceptionally()`.

5. **Future extensibility**: Adding durable sleep, inter-workflow messaging, or child workflow spawning maps naturally to coroutine primitives (`delay()`, `Channel`, `async/await`).

### When to Choose Option B (Non-Coroutine)

- The team has no coroutine experience and wants standard Java patterns
- The engine must integrate with a framework that doesn't support suspend functions
- Debugging coroutine stack traces is a concern in production
- The project already uses db-scheduler and wants to minimize new dependencies

### Implementation Order

1. **Define the interfaces** (WorkflowEngine, repositories, TaskExecutor)
2. **Write in-memory implementations** (the test doubles)
3. **Write acceptance tests** against the in-memory engine
4. **Implement the DAG runner** (coroutine-based)
5. **Write PostgreSQL repository implementations**
6. **Write integration tests** with Testcontainers
7. **Add the workflow DSL** (syntactic sugar)
8. **Add observability** (event log queries, status API)

### Dependency List

```kotlin
// build.gradle.kts
dependencies {
    // Core
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // PostgreSQL
    implementation("org.postgresql:postgresql:42.7.4")
    implementation("com.zaxxer:HikariCP:6.2.1")

    // Testing
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("io.mockk:mockk:1.13.13")

    // Integration testing (optional)
    testImplementation("org.testcontainers:postgresql:1.20.4")
}
```

---

## Sources

### Hatchet
- [Hatchet v1-core.sql Schema](https://github.com/hatchet-dev/hatchet/blob/main/sql/schema/v1-core.sql)
- [Hatchet Architecture](https://docs.hatchet.run/home/architecture)
- [Declarative Workflow Design (DAGs)](https://docs.hatchet.run/home/dags)
- [Durable Execution](https://docs.hatchet.run/home/durable-execution)
- [Concurrency Control](https://docs.hatchet.run/home/concurrency)
- [On-Failure Tasks](https://docs.hatchet.run/home/on-failure-tasks)
- [Conditional Workflows](https://docs.hatchet.run/home/conditional-workflows)
- [Multi-Tenant Queues Blog](https://hatchet.run/blog/multi-tenant-queues)
- [HN Discussion: Hatchet v1](https://news.ycombinator.com/item?id=43572733)
- [Go Package Docs (repository/v1)](https://pkg.go.dev/github.com/hatchet-dev/hatchet/pkg/repository/v1)

### db-scheduler
- [kagkarlsson/db-scheduler GitHub](https://github.com/kagkarlsson/db-scheduler)
- [JobChainingConfiguration Example](https://github.com/kagkarlsson/db-scheduler/blob/master/examples/spring-boot-example/src/main/java/com/github/kagkarlsson/examples/boot/config/JobChainingConfiguration.java)
- [TransactionallyStagedJobConfiguration Example](https://github.com/kagkarlsson/db-scheduler/blob/master/examples/spring-boot-example/src/main/java/com/github/kagkarlsson/examples/boot/config/TransactionallyStagedJobConfiguration.java)
- [ParallellJobConfiguration Example](https://github.com/kagkarlsson/db-scheduler/blob/master/examples/spring-boot-example/src/main/java/com/github/kagkarlsson/examples/boot/config/ParallellJobConfiguration.java)
- [Multi-step job Issue #245](https://github.com/kagkarlsson/db-scheduler/issues/245)
- [osoykan/db-scheduler-additions (Kotlin)](https://github.com/osoykan/db-scheduler-additions)
- [KotlinSerializer Example](https://github.com/kagkarlsson/db-scheduler/blob/master/examples/features/src/main/java/com/github/kagkarlsson/examples/kotlin/KotlinSerializer.kt)

### Kotlin Coroutines & Testing
- [kotlinx-coroutines-test API](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-test/)
- [TestCoroutineScheduler API](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-test/kotlinx.coroutines.test/-test-coroutine-scheduler/)
- [Kotlin Coroutines: Dispatchers](https://kt.academy/article/cc-dispatchers)
- [Inside Kotlin Coroutines: State Machines](https://proandroiddev.com/inside-kotlin-coroutines-state-machines-continuations-and-structured-concurrency-b8d3d4e48e62)
- [Dagupan: Kotlin DAG Scheduler](https://github.com/diarmuidkeane/dagupan)

### Temporal Testing (Reference)
- [TestWorkflowEnvironment Javadoc](https://www.javadoc.io/doc/io.temporal/temporal-testing/latest/io/temporal/testing/TestWorkflowEnvironment.html)
- [Temporal Java Testing Docs](https://docs.temporal.io/develop/java/testing-suite)

### DBOS (Reference)
- [DBOS Testing](https://docs.dbos.dev/python/tutorials/testing)
- [DBOS Java SDK](https://github.com/dbos-inc/dbos-transact-java)

### Restate Testing (Reference)
- [Restate Java/Kotlin SDK Testing](https://docs.restate.dev/develop/java/testing)

### Database Patterns
- [PostgreSQL SKIP LOCKED Analysis](https://www.inferable.ai/blog/posts/postgres-skip-locked)
- [HikariCP Pool Sizing](https://github.com/brettwooldridge/HikariCP/wiki/About-Pool-Sizing)
- [kotlinx.serialization Polymorphism](https://github.com/Kotlin/kotlinx.serialization/blob/master/docs/polymorphism.md)

### Previous Research (This Project)
- [Durable Execution Research Report](./2026-02-11-093500-durable-execution-research.md)
- [Workflow Suspension Internals](./2026-02-11-workflow-suspension-internals.md)
