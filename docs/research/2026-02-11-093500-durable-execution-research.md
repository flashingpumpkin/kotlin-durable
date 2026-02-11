# Durable Execution: Comprehensive Technical Research Report

> **Purpose**: This document provides a deep technical reference for building a durable workflow engine for Kotlin backends. It covers four production frameworks (DBOS, Restate, Hatchet, db-scheduler), their architectural choices, and the foundational distributed systems patterns they employ.

---

## Table of Contents

1. [Executive Summary & Taxonomy](#1-executive-summary--taxonomy)
2. [DBOS — PostgreSQL-Native Lightweight Durable Execution](#2-dbos)
3. [Restate — Journal-Based Durable Execution Engine](#3-restate)
4. [Hatchet — DAG-Based Task Orchestration Platform](#4-hatchet)
5. [db-scheduler — Java Persistent Task Scheduler](#5-db-scheduler)
6. [Cross-Cutting Analysis: Core Primitives](#6-cross-cutting-analysis-core-primitives)
7. [Foundational Distributed Systems Patterns](#7-foundational-distributed-systems-patterns)
8. [Kotlin-Specific Considerations](#8-kotlin-specific-considerations)
9. [Architectural Decision Matrix](#9-architectural-decision-matrix)

---

## 1. Executive Summary & Taxonomy

Durable execution engines guarantee that a program's progress survives process crashes, network failures, and restarts. They achieve this through **checkpointing** (persisting intermediate results) and **replay** (recovering from the last checkpoint). The four frameworks studied represent three distinct architectural approaches:

| Approach | Framework | Key Idea |
|----------|-----------|----------|
| **Library-embedded, DB-centric** | DBOS | Lightweight library that piggybacks workflow checkpoints on PostgreSQL transactions |
| **External server, journal-based** | Restate | Dedicated Rust server that maintains an append-only journal and replays handler code |
| **External server, DAG-based** | Hatchet | Control plane that orchestrates a DAG of tasks dispatched to workers via gRPC |
| **Library-embedded, single-table** | db-scheduler | Minimal scheduler using one database table for coordination (not full durable execution) |

### The Spectrum of Durable Execution

```
Simple Scheduling ◄─────────────────────────────────────────► Full Durable Execution

db-scheduler          Hatchet              DBOS               Restate / Temporal
(persistent tasks,    (DAG orchestration,  (checkpoint-based,  (journal replay,
 single table,         at-least-once,       exactly-once DB    deterministic replay,
 no replay)            result caching)      transactions)      full event sourcing)
```

---

## 2. DBOS

### 2.1 Core Architecture

DBOS is an **embedded library** (not an external server) that makes ordinary functions durable by persisting their execution state to PostgreSQL. The design philosophy is "lightweight durable execution" — workflows and steps are ordinary functions annotated with decorators/annotations, running in your application process.

**Two-Database Architecture:**
- **Application database**: Your business data
- **System database** (`<app>_dbos_sys`): Workflow execution state in a `dbos` schema

**Overhead per step**: ~1ms (a single PostgreSQL write). Two additional writes per workflow (start + end). This yields a claimed **25x performance advantage** over queue-based systems like AWS Step Functions (~100ms per step).

### 2.2 Workflow Primitives

| Primitive | Semantics | Guarantee |
|-----------|-----------|-----------|
| **Workflow** | Deterministic orchestration function that composes steps. Must not perform I/O directly. | Runs to completion exactly once (given the same workflow ID). |
| **Step** | Non-deterministic function (API calls, file I/O, timestamps). | At-least-once. Once output is checkpointed, never re-executed. |
| **Transaction** | Specialized step for database operations. Executes in a single DB transaction. | **Exactly-once** via piggyback checkpoint. |

**Determinism constraints on workflows** (same as Temporal/Durable Functions):
- No direct I/O, random numbers, current time, environment variables
- No non-deterministic concurrency
- All side effects must go through steps or transactions

### 2.3 State Management & Schema

All tables reside in the `dbos` schema of the system database:

**`dbos.workflow_status`** — One row per workflow execution:
```
workflow_uuid          UUID (PK)
status                 ENUM: PENDING | SUCCESS | ERROR | ENQUEUED | CANCELLED | MAX_RECOVERY_ATTEMPTS_EXCEEDED
name                   TEXT (workflow function name)
inputs                 JSONB (serialized workflow inputs)
output                 JSONB (serialized result, set on completion)
error                  JSONB (if failed)
application_version    TEXT (code version tag)
executor_id            TEXT (which process owns this)
queue_name             TEXT (if enqueued)
priority               INT (1 = highest, up to 2^31)
created_at, updated_at TIMESTAMP
recovery_attempts      INT
```

**`dbos.operation_outputs`** — One row per completed step:
```
workflow_uuid          UUID (FK)
function_id            INT (step index within the workflow, starting at 0)
function_name          TEXT
output                 JSONB
error                  JSONB
child_workflow_id      UUID (if this step started a child workflow)
started_at_epoch_ms    BIGINT
completed_at_epoch_ms  BIGINT
```

**`dbos.notifications`** — Inter-workflow messaging (send/recv):
```
destination_uuid, topic, message, message_uuid, created_at_epoch_ms
```

**`dbos.workflow_events`** — Pub/sub events (set_event/get_event):
```
workflow_uuid, key, value
```

### 2.4 Execution & Recovery Flow

**Normal execution:**
1. Workflow starts → row inserted into `workflow_status` with `PENDING` + serialized `inputs`
2. Step N executes → row inserted into `operation_outputs` with `function_id=N` + serialized `output`
3. Next step reads previous step's return value via normal function composition (in-memory)
4. Workflow completes → `workflow_status` updated with `output` and `status=SUCCESS`

**Recovery (crash + restart):**
1. On startup, DBOS queries `workflow_status` for `status = 'PENDING'` rows matching the current `executor_id`
2. Re-invokes each workflow function with its checkpointed `inputs`
3. Before each step, checks `operation_outputs` for `(workflow_uuid, function_id)`
4. If found → returns cached output **without executing**
5. If not found → executes step normally, checkpoints output
6. Net effect: workflow resumes from the first uncheckpointed step

### 2.5 The Piggyback Checkpoint (Exactly-Once for DB Transactions)

The critical innovation in DBOS. For steps that perform database operations:

1. Open a PostgreSQL transaction
2. Execute business logic (e.g., `INSERT INTO orders ...`)
3. Within the **same transaction**, write the step's completion record to a `transaction_completion` table
4. Commit atomically

If the transaction commits, both the business change and the checkpoint are persisted. If it rolls back, neither is. On recovery, DBOS checks the completion table before re-executing — if a record exists, the step is skipped.

Two implementation strategies:
- **Optimistic**: Execute first, roll back on checkpoint write conflict
- **Pessimistic**: Query checkpoint table before executing

### 2.6 Queues

PostgreSQL-backed durable queues using `SELECT FOR UPDATE SKIP LOCKED`:

```python
queue = Queue(
    "order_processing",
    concurrency=10,              # Max concurrent globally
    worker_concurrency=5,        # Max concurrent per process
    limiter={"limit": 50, "period": 30},  # Rate limit
    priority_enabled=True,
    partition_queue=True,         # Per-partition flow control
)
```

Features: global concurrency limits, per-process limits, rate limiting, priority ordering, deduplication via `deduplication_id`, partitioned queues for per-entity concurrency.

### 2.7 Versioning

Two strategies:

**Patching** — Conditional code paths for old vs new workflows:
```java
if (DBOS.patch("use-baz")) {
    DBOS.runStep(() -> baz(), "baz");  // New workflows
} else {
    DBOS.runStep(() -> foo(), "foo");  // Replayed old workflows
}
```
`DBOS.patch()` returns `true` for new executions, `false` for replays. Uses `operation_outputs` markers.

**Application Version Tagging** — Blue-green deployment. Each workflow tagged with `application_version`. On recovery, only matching-version workflows are recovered. Drain old version, then decommission.

### 2.8 Error Handling & Retries

```python
@DBOS.step(retries_allowed=True, interval_seconds=1.0, max_attempts=10, backoff_rate=2.0)
def unreliable_call(): ...
```

- Exponential backoff with configurable `backoff_rate` and `max_attempts`
- `DBOSMaxStepRetriesExceeded` thrown when exhausted
- Workflow-level timeout: cancels workflow and all children
- Uncaught workflow exceptions → `ERROR` status, no automatic recovery (assumed to be a bug)
- `maxRecoveryAttempts` prevents infinite crash loops

### 2.9 Inter-Workflow Communication

- **Send/Recv**: Durable message passing via `dbos.notifications` table
- **Events**: Pub/sub via `dbos.workflow_events` (set_event/get_event)
- **Streams**: Ordered data streaming via `dbos.streams`
- **Workflow forking**: Restart a workflow from a specific step — copies inputs + steps 0..N from the original

### 2.10 API Design (Multi-Language)

| Language | Workflow Definition | Step Definition | Notes |
|----------|-------------------|-----------------|-------|
| Python | `@DBOS.workflow()` decorator | `@DBOS.step()` / `@DBOS.transaction()` | Pydantic-compatible |
| TypeScript | `@DBOS.workflow()` decorator | `@DBOS.step()` / `@dataSource.transaction()` | Knex/Drizzle datasources |
| Go | `dbos.RegisterWorkflow()` | `dbos.RunAsStep()` | Context-based, no decorators |
| Java | `@Workflow` annotation | `DBOS.runStep(lambda, "name")` | Spring-compatible |

### 2.11 Performance

| Metric | Value |
|--------|-------|
| Step overhead | ~1ms (1 PG write) |
| Throughput ceiling | 10K–18K steps/sec per PG instance |
| Infrastructure | PostgreSQL only |
| Code overhead vs Temporal | ~10x less code |

---

## 3. Restate

### 3.1 Core Architecture

Restate is a **dedicated server** (written in Rust) that acts as a durable execution engine. Unlike DBOS's library approach, Restate is an external process that your services communicate with via HTTP or gRPC. It uses a **journal-based replay** model.

**Key architectural components:**
- **Restate Server**: Single binary with embedded storage (Bifrost replicated log + RocksDB)
- **Services**: Your application code, registered with the server via a discovery protocol
- **Journal**: Append-only log of all side effects for each invocation

**Execution model**: Push-based (not polling). When an invocation arrives, Restate immediately dispatches it to a service endpoint. Sub-millisecond dispatch latency.

### 3.2 Service Types

| Type | Key Characteristics |
|------|-------------------|
| **Service** | Stateless handlers. No ordering guarantees. Concurrent execution. |
| **Virtual Object** | Keyed entity with durable K/V state. **Single-writer per key** (inbox queue ensures serialized access). |
| **Workflow** | Special Virtual Object with a `run` handler (executes once per ID) plus shared handlers for interaction. Has durable promises. |

### 3.3 Journaling & Replay

Every side effect in a handler is recorded as a **journal entry**:
- Service calls (request/response)
- State reads/writes (K/V operations)
- Sleep timers
- Awakeable resolutions
- Promise completions

**Replay on failure:**
1. Restate detects handler failure (process crash, timeout, etc.)
2. Dispatches a new invocation attempt to any available service endpoint
3. The SDK re-executes the handler code from the top
4. For each side effect, the SDK checks the journal:
   - If a completed entry exists → return cached result (no re-execution)
   - If no entry exists → execute the side effect, append to journal
5. Handler resumes from the first un-journaled side effect

**Suspension**: When a handler awaits an external result (another service call, an awakeable, a sleep timer), the handler **suspends**. The SDK notifies the server, which frees the worker connection. When the result arrives, Restate re-dispatches the handler for replay.

### 3.4 Invocation Lifecycle States

```
pending → ready → running → suspended → running → completed
                     ↓                       ↑
                backing-off ─────────────────┘
```

| State | Meaning |
|-------|---------|
| `pending` | Enqueued in a Virtual Object's inbox, waiting for its turn |
| `ready` | Ready to be dispatched, but not yet running |
| `running` | Actively executing on a service endpoint |
| `backing-off` | Failed, waiting for retry backoff to elapse |
| `suspended` | Waiting on external input (call, awakeable, sleep, promise) |
| `completed` | Finished (retained only for idempotent invocations) |

### 3.5 State Management

**K/V State** (Virtual Objects only):
- Per-key state accessible via `ctx.get("key")` / `ctx.set("key", value)`
- State reads/writes are journal entries — deterministic on replay
- Stored in RocksDB within the Restate server

**SQL Introspection Tables** (via DataFusion):

| Table | Purpose |
|-------|---------|
| `sys_invocation` | 42-column invocation tracking (status, timing, retries, caller info, deployment) |
| `sys_journal` | Journal entries per invocation (index, entry_type, completed, invoked_target) |
| `sys_inbox` | Virtual Object inbox queue (service_name, service_key, sequence_number) |
| `sys_promise` | Workflow promise state (completed, success_value, failure) |
| `sys_idempotency` | Idempotency key → invocation mapping |
| `sys_service` | Service registry (name, revision, type, deployment_id) |
| `sys_deployment` | Deployment endpoints (URL/Lambda ARN, protocol version range) |
| `state` | Virtual Object K/V state (service_name, service_key, key, value) |

### 3.6 Versioning

Restate uses a **deployment-based** versioning model:

1. Register a new deployment with updated service code
2. Restate discovers the new service endpoints and bumps the service `revision`
3. **In-flight invocations** remain pinned to their original deployment (`pinned_deployment_id`)
4. New invocations use the latest deployment
5. Old deployments can be removed once all pinned invocations complete

This avoids the code-level versioning complexity of Temporal's `getVersion()` pattern.

### 3.7 Error Handling & Retries

```kotlin
// Kotlin SDK
@Handler
suspend fun myHandler(ctx: Context, request: Request): Response {
    // Retries are automatic with exponential backoff
    val result = ctx.runBlock { callExternalApi() }
    return result
}
```

- **Retryable errors**: Automatically retried with exponential backoff (configurable per service)
- **Terminal errors**: `TerminalError` — stops retries immediately, fails the invocation
- Retry configuration: `initialDelayMs`, `maxDelayMs`, `factor`, `maxAttempts`, `maxDuration`
- Invocations that exhaust retries enter a `backing-off` → eventually `failed` state

### 3.8 Communication Patterns

| Pattern | API | Behavior |
|---------|-----|----------|
| Request-Response | `ctx.call(service, handler, request)` | Durable RPC. Suspends caller until callee completes. |
| Fire-and-Forget | `ctx.send(service, handler, request)` | Guaranteed delivery, no result awaited. |
| Delayed Call | `ctx.send(service, handler, request, delay=30s)` | Schedule for future execution. |
| Awakeable | `ctx.awakeable()` → external signal → `ctx.resolveAwakeable(id, value)` | Pause handler until external system signals completion. |
| Durable Promise | `ctx.promise("key").awaiting()` / `ctx.promise("key").resolve(value)` | Cross-handler coordination within a workflow. |

### 3.9 Wire Protocol

- Services communicate with Restate over **HTTP/2** with bidirectional streaming
- Protocol versions are negotiated during deployment discovery
- The SDK and server exchange journal entries as a stream of binary-encoded messages
- Lambda deployments use a request-response protocol (no streaming)

### 3.10 Kotlin SDK API

```kotlin
@Service
class PaymentService {

    @Handler
    suspend fun process(ctx: Context, request: PaymentRequest): PaymentResult {
        // Durable side effect
        val authorized = ctx.runBlock { paymentGateway.authorize(request) }

        // Durable service call
        val receipt = ReceiptServiceClient.fromContext(ctx)
            .generate(GenerateRequest(request.orderId, authorized.transactionId))
            .await()

        // Durable sleep
        ctx.sleep(Duration.ofDays(30))

        return PaymentResult(receipt.id)
    }
}

@VirtualObject
class ShoppingCart {

    @Handler
    suspend fun addItem(ctx: ObjectContext, item: Item) {
        val cart = ctx.get(CART_STATE) ?: emptyList()
        ctx.set(CART_STATE, cart + item)
    }

    @Handler
    suspend fun checkout(ctx: ObjectContext): Order {
        val cart = ctx.get(CART_STATE) ?: throw TerminalError("Empty cart")
        val order = ctx.runBlock { orderService.create(cart) }
        ctx.clear(CART_STATE)
        return order
    }

    companion object {
        private val CART_STATE = StateKey.of<List<Item>>("cart")
    }
}
```

---

## 4. Hatchet

### 4.1 Core Architecture

Hatchet is an **external orchestration platform** with three components:
- **Engine** (control plane): Schedules tasks, manages queues, enforces concurrency/rate limits, handles retries
- **API Server**: REST endpoints for triggering workflows and querying state
- **Workers**: Your application code, connecting to the engine via **bidirectional gRPC**

Storage: **PostgreSQL only** (sole required dependency). All state changes happen within PostgreSQL transactions.

### 4.2 Workflow & Task Primitives

**Task**: Atomic unit of work. Receives typed input, returns typed output.

**Workflow**: Composition of tasks into a **DAG** (Directed Acyclic Graph).

Two orchestration approaches:

**Declarative DAG** (recommended):
```python
@workflow.task(parents=[step1, step2])
def step3(input, ctx):
    result1 = ctx.task_output(step1)
    result2 = ctx.task_output(step2)
    return RandomSum(sum=result1.value + result2.value)
```

**Procedural child spawning** (dynamic):
```python
result = await ctx.aio_run(child_task, input)
```

### 4.3 Database Schema (v1)

Core tables (all in PostgreSQL, using time-based daily rolling partitions):

| Table | Purpose |
|-------|---------|
| `v1_task` | Partitioned task table with execution state, retry logic, concurrency strategy |
| `v1_queue` | Tenant-specific queues with last activity tracking |
| `v1_queue_item` | Tasks ready for execution with scheduling and priority |
| `v1_task_runtime` | Worker assignment and timeout tracking per retry |
| `v1_retry_queue_item` | Failed tasks scheduled for retry with backoff |
| `v1_dag` | DAG definitions representing workflow execution branches |
| `v1_dag_to_task` | DAG → task relationships |
| `v1_payload` | Task inputs/outputs. Supports `INLINE` (Postgres JSONB) and `EXTERNAL` storage |
| `v1_task_event` | Partitioned event log (COMPLETED, FAILED, CANCELLED, SIGNAL) |
| `v1_concurrency_slot` | Slots tracking task progress through concurrency queues |
| `v1_idempotency_key` | Request deduplication with expiration |
| `v1_durable_sleep` | Persistent sleep timers |
| `v1_log_line` | Task execution logs |
| `v1_lookup_table` | Fast external ID resolution |

**Concurrency strategies**: `GROUP_ROUND_ROBIN`, `CANCEL_IN_PROGRESS`, `CANCEL_NEWEST`

### 4.4 Queue Implementation

Hatchet uses a **block-based sequencing algorithm** instead of naive `SELECT FOR UPDATE SKIP LOCKED`:

- BIGINT address space partitioned into contiguous blocks (default: 1,048,576 per block)
- Each group (tenant/user/custom key) maintains a pointer tracking its last assigned block
- Task ID formula: `groupId + blockLength * blockPointer`
- Guarantees round-robin fairness across tenants
- Reads remain **O(1)** regardless of queue backlog

### 4.5 Versioning

DAG-based design simplifies versioning:
- Workflow shape is declared ahead of time → no replay determinism issues
- In-flight workflows continue with their original definition
- New runs use the updated definition
- Workers register task definitions on startup; the engine tracks which version each workflow belongs to

### 4.6 Error Handling

```python
@hatchet.task(retries=6, backoff_factor=2.0, backoff_max_seconds=10)
def my_task(input, ctx): ...

@my_workflow.on_failure_task()
def handle_failure(input, ctx):
    errors = ctx.task_run_errors()
    # Cleanup, alert, compensate
```

- `retries` with exponential backoff (`backoff_factor`, `backoff_max_seconds`)
- `NonRetryableException` → skip all retries
- `on_failure_task` → runs when any task in the workflow fails (after retries exhausted)
- Scheduling timeout (default 5min) and execution timeout (default 60s)
- Timeout refresh mid-execution: `ctx.refreshTimeout("15s")`

### 4.7 Concurrency & Rate Limiting

```python
@hatchet.task(concurrency=[
    ConcurrencyExpression(
        expression="input.user_id",   # CEL expression for grouping
        max_runs=5,
        limit_strategy=ConcurrencyLimitStrategy.GROUP_ROUND_ROBIN
    )
])
```

- **Static rate limits**: `hatchet.rate_limits.put("api-calls", 100, MINUTE)`
- **Dynamic rate limits**: CEL-based per-key limits
- **Sticky workers**: `SOFT` (prefer same worker) or `HARD` (require same worker)
- **Worker affinity**: Label-based routing with comparators (GREATER_THAN, LESS_THAN, etc.)

### 4.8 Delivery Guarantee

**At-least-once**. Tasks should be designed to be idempotent. Each durable subtask receives an idempotency key (workflow instance ID + subtask index) for "dispatch exactly once" semantics.

---

## 5. db-scheduler

### 5.1 Core Architecture

db-scheduler is a **persistent, cluster-friendly task scheduler** for Java. Not a full durable execution engine — it provides persistent scheduling with cluster coordination via a **single database table**. Created as a simpler alternative to Quartz (11 tables → 1 table).

**Execution model**: Poll-execute loop
1. Polling thread checks the database for due executions at configurable interval (default 10s)
2. Due executions dispatched to a thread pool (default 10 threads)
3. `CompletionHandler` determines next state (remove, reschedule, replace)
4. Housekeeper thread handles heartbeats and dead execution detection

### 5.2 Database Schema

**Single table** — `scheduled_tasks`:
```sql
CREATE TABLE scheduled_tasks (
  task_name          TEXT                     NOT NULL,
  task_instance      TEXT                     NOT NULL,
  task_data          BYTEA,
  execution_time     TIMESTAMP WITH TIME ZONE NOT NULL,
  picked             BOOLEAN                  NOT NULL,
  picked_by          TEXT,
  last_success       TIMESTAMP WITH TIME ZONE,
  last_failure       TIMESTAMP WITH TIME ZONE,
  consecutive_failures INT,
  last_heartbeat     TIMESTAMP WITH TIME ZONE,
  version            BIGINT                   NOT NULL,
  priority           SMALLINT,
  PRIMARY KEY (task_name, task_instance)
);
```

Indexes: `execution_time`, `last_heartbeat`, `(priority DESC, execution_time ASC)`.

### 5.3 Cluster Coordination

**Strategy 1: fetch-and-lock-on-execute** (default, all databases):
1. `SELECT` due executions
2. Optimistic lock via `UPDATE ... SET picked=true, version=version+1 WHERE version=?`
3. If 0 rows affected → another instance claimed it, skip

**Strategy 2: lock-and-fetch** (PostgreSQL, MySQL 8+):
1. `SELECT ... FOR UPDATE SKIP LOCKED` — atomically fetch AND lock rows
2. No contention between instances
3. ~50% less database overhead

### 5.4 Task Types

| Type | Behavior |
|------|----------|
| `RecurringTask` | Runs on a fixed schedule (FixedDelay, Daily, Cron). Auto-created on startup. |
| `RecurringTaskWithPersistentSchedule` | Per-instance schedules stored in `task_data` |
| `OneTimeTask` | Executes once at specified time, then deleted |
| `Custom` | Full control over `CompletionHandler` return |

### 5.5 Error Handling

| Handler | Behavior |
|---------|----------|
| `OnFailureRetryLater` | Fixed delay retry (default: 5min for one-time tasks) |
| `ExponentialBackoffFailureHandler` | `delay × 1.5^consecutiveFailures` |
| `MaxRetriesFailureHandler` | Wraps another handler with a retry limit |
| `OnFailureReschedule` | Use the task's Schedule for retry timing |

**Dead execution detection**: Housekeeper updates `last_heartbeat` every 5min. Executions with stale heartbeats (>30min by default) are revived.

### 5.6 Key Pattern: Transactionally Staged Jobs

```java
@Transactional
public void createUser(UserRequest request) {
    User user = userRepository.save(new User(request));
    // This INSERT into scheduled_tasks is part of the SAME transaction
    schedulerClient.schedule(
        SEND_WELCOME_EMAIL.instanceWithId(user.getId())
            .data(new EmailData(user.getEmail()))
            .scheduledTo(Instant.now()));
}
```

If the transaction commits, the email task becomes visible. If it rolls back, the task never exists. This is the **transactional outbox pattern**.

### 5.7 Performance

| Configuration | Strategy | Throughput |
|--------------|----------|------------|
| 4-core PostgreSQL | fetch-and-lock | ~2,000 exec/s |
| 8-core PostgreSQL | fetch-and-lock | ~4,000 exec/s |
| 4-8 core PostgreSQL | lock-and-fetch (SKIP LOCKED) | ~10,000 exec/s |

---

## 6. Cross-Cutting Analysis: Core Primitives

### 6.1 Execution Models Compared

| Dimension | DBOS | Restate | Hatchet | db-scheduler |
|-----------|------|---------|---------|-------------|
| **Topology** | Library in your process | External server | External server + workers | Library in your process |
| **Storage** | PostgreSQL | Embedded (Bifrost + RocksDB) | PostgreSQL | PostgreSQL (any RDBMS) |
| **Replay model** | Checkpoint skip-ahead | Full journal replay | No replay (DAG orchestration) | No replay (re-execute) |
| **Delivery** | At-least-once (exactly-once for DB txns) | At-least-once (effectively exactly-once via journal) | At-least-once | At-least-once |
| **Worker dispatch** | Polling (SKIP LOCKED) | Push (HTTP/2 streaming) | Push (bidirectional gRPC) | Polling (optimistic lock or SKIP LOCKED) |
| **Latency per step** | ~1ms | Sub-ms (in-process journal) | ~25ms P95 (network hop) | 10s polling interval |
| **Throughput** | 10-18K steps/s | High (embedded I/O) | 5K+ tasks/s | 2-10K exec/s |
| **Determinism required** | Yes (workflows) | Yes (handlers) | No (DAG-based) | No |

### 6.2 State Persistence Compared

| Framework | State Location | Schema | Serialization |
|-----------|---------------|--------|---------------|
| DBOS | PostgreSQL `dbos.*` tables | `workflow_status` + `operation_outputs` + `notifications` + `events` | JSON |
| Restate | Embedded RocksDB + Bifrost log | `sys_invocation` + `sys_journal` + `state` + `sys_promise` | Binary + UTF-8 |
| Hatchet | PostgreSQL `v1_*` tables | `v1_task` + `v1_payload` + `v1_dag` + `v1_task_event` | JSONB (inline) or external |
| db-scheduler | PostgreSQL/MySQL `scheduled_tasks` | Single table with `task_data` column | Java Serialization, Gson, Jackson |

### 6.3 Versioning Compared

| Framework | Strategy | Granularity |
|-----------|----------|-------------|
| DBOS | Patching (conditional code paths) + application version tags | Per-workflow |
| Restate | Deployment-based (new deployments get new revision, old invocations pinned) | Per-deployment |
| Hatchet | Worker re-registration (new definitions on startup, in-flight uses original) | Per-workflow definition |
| db-scheduler | No versioning (tasks are simple, no replay) | N/A |

### 6.4 Error Handling Compared

| Framework | Retry Config | Backoff | Terminal Errors | Compensation |
|-----------|-------------|---------|-----------------|--------------|
| DBOS | `max_attempts`, `interval_seconds`, `backoff_rate` | Exponential | `DBOSMaxStepRetriesExceeded` | Manual (workflow code) |
| Restate | `maxAttempts`, `initialDelayMs`, `maxDelayMs`, `factor` | Exponential | `TerminalError` class | Saga pattern in handler code |
| Hatchet | `retries`, `backoff_factor`, `backoff_max_seconds` | Exponential | `NonRetryableException` | `on_failure_task` handler |
| db-scheduler | `MaxRetriesFailureHandler`, `ExponentialBackoffFailureHandler` | Exponential (`delay × 1.5^n`) | `executionOperations.stop()` | N/A |

---

## 7. Foundational Distributed Systems Patterns

### 7.1 Journal-Based Replay (Event Sourcing for Execution)

Used by: **Restate, Temporal, Azure Durable Functions**

Every side effect is recorded as a journal/event entry. On recovery, the framework re-executes the handler code from the top, but returns cached results for already-journaled entries.

**Critical constraint**: Handler code must be **deterministic**. Forbidden operations:
- `Date.now()`, `Math.random()`, `UUID.randomUUID()`
- Direct I/O (HTTP calls, file access, database queries)
- Thread-local state, static mutable variables
- Environment variable reads (may change between replays)

All non-deterministic operations must go through framework-provided APIs (`ctx.runBlock()`, `ctx.sleep()`, etc.) that record results in the journal.

### 7.2 Checkpoint Skip-Ahead (DBOS Model)

Used by: **DBOS**

Instead of replaying the full event history, DBOS re-executes the workflow function and checks for cached step results before each step. If found, returns the cached result. If not found, executes normally.

**Advantages**: No determinism constraints on individual steps (only the workflow function). Simpler mental model.
**Disadvantages**: Must re-execute the workflow function from the top (though this is fast since steps return cached results).

### 7.3 DAG Orchestration (Hatchet Model)

Used by: **Hatchet, Airflow, Prefect**

The workflow is declared as a graph of tasks with explicit dependencies. The engine resolves dependencies and dispatches tasks as they become ready. No replay or determinism requirements.

**Advantages**: No determinism constraints at all. Natural parallelism. Easy versioning.
**Disadvantages**: Less flexible than procedural code. Fan-out/fan-in patterns require explicit DAG construction.

### 7.4 Database-as-Queue (SELECT FOR UPDATE SKIP LOCKED)

Used by: **DBOS, Hatchet, db-scheduler**

```sql
BEGIN;
SELECT * FROM tasks
WHERE status = 'PENDING' AND execution_time <= now()
ORDER BY priority, execution_time
FOR UPDATE SKIP LOCKED
LIMIT 10;
-- Process tasks
UPDATE tasks SET status = 'RUNNING' WHERE id IN (...);
COMMIT;
```

**Properties**:
- No contention: workers skip already-locked rows
- Atomic: lock and read in one operation
- ACID: transactional consistency with business data
- Performance: PostgreSQL handles tens of thousands of claims/second

**Limitation**: Polling-based latency. Mitigated by:
- Reducing poll interval (at cost of more DB queries)
- Combining with `LISTEN/NOTIFY` for push notifications (thundering herd at 64+ listeners)
- Hatchet's block-based sequencing algorithm for O(1) reads

### 7.5 Transactional Outbox Pattern

Used by: **db-scheduler (transactionally staged jobs), DBOS (piggyback checkpoint)**

Ensure that a business operation and its resulting event/task are committed atomically:

```
BEGIN TRANSACTION;
  INSERT INTO orders (id, ...) VALUES (...);
  INSERT INTO outbox_events (event_type, payload) VALUES ('ORDER_CREATED', ...);
COMMIT;
```

A separate process polls the outbox table and publishes events. Alternatively (DBOS model), the outbox IS the task queue — scheduled_tasks/workflow_status doubles as the outbox.

### 7.6 Optimistic Concurrency Control

Used by: **db-scheduler (version column), Hatchet (workflow state updates)**

```sql
UPDATE workflow_tasks
SET status = 'RUNNING', claimed_by = 'worker-42', version = version + 1
WHERE id = ? AND version = ?;
-- If 0 rows affected → another worker claimed it
```

Best for: State updates where read-modify-write atomicity is needed.
For task claiming: **SKIP LOCKED is preferred** — eliminates retries entirely.

### 7.7 Saga Pattern

For workflows that span multiple services/databases where a single transaction is impossible:

**Orchestration saga** (centralized coordinator):
```
1. Reserve inventory → success
2. Charge payment → success
3. Ship order → FAILURE
   → Compensate: Refund payment
   → Compensate: Release inventory
```

**Implementation in durable execution**:
```kotlin
suspend fun orderSaga(ctx: Context, order: Order) {
    val reservation = ctx.runBlock { inventoryService.reserve(order) }
    try {
        val payment = ctx.runBlock { paymentService.charge(order) }
        try {
            ctx.runBlock { shippingService.ship(order) }
        } catch (e: TerminalError) {
            ctx.runBlock { paymentService.refund(payment) }
            throw e
        }
    } catch (e: TerminalError) {
        ctx.runBlock { inventoryService.release(reservation) }
        throw e
    }
}
```

### 7.8 Idempotency Strategies

| Strategy | Mechanism | Storage |
|----------|-----------|---------|
| Workflow ID as idempotency key | Same ID → same execution (DBOS, Restate) | One row per workflow |
| Step index as dedup key | `workflow_id + step_index` uniquely identifies each step | One row per step output |
| Idempotency key header | Client-provided key, stored in `sys_idempotency` (Restate) | TTL-based retention |
| Content hash | Hash of (method + path + body) for API deduplication | Alongside idempotency key |

### 7.9 Failure Domains

| Domain | Detection | Recovery |
|--------|-----------|----------|
| **Process crash** | Heartbeat timeout / lease expiry | Re-dispatch to another worker, replay from journal/checkpoint |
| **Network partition** | Heartbeat timeout | Fencing tokens prevent split-brain; partitioned worker's lease expires |
| **Database failure** | Connection error | All durable operations pause; resume when DB recovers |
| **Poison pill task** | Retry limit exhaustion | Move to FAILED state / dead letter queue |
| **Timeout** | Schedule-to-start, start-to-close, heartbeat timeouts | Cancel + retry or fail depending on policy |

---

## 8. Kotlin-Specific Considerations

### 8.1 Kotlin Coroutines as a Durable Execution Primitive

The Kotlin compiler transforms every `suspend` function into a **state machine** via Continuation-Passing Style (CPS). Each suspension point becomes a state label:

```kotlin
// Source
suspend fun workflow() {
    val a = step1()     // suspension point 0
    val b = step2(a)    // suspension point 1
    return step3(b)     // suspension point 2
}

// Compiler generates (conceptual):
class WorkflowStateMachine : ContinuationImpl {
    var label = 0
    var a: Any? = null; var b: Any? = null

    fun invokeSuspend(result: Result<Any?>): Any? = when (label) {
        0 -> { label = 1; step1(this) }   // suspend at step1
        1 -> { a = result; label = 2; step2(a, this) }
        2 -> { b = result; label = 3; step3(b, this) }
        3 -> result
        else -> error("Invalid state")
    }
}
```

### 8.2 Mapping Suspension to Checkpointing

A durable execution engine for Kotlin can exploit this architecture:

1. **Suspension = Checkpoint Boundary**: Each `suspend` call is a natural checkpoint. Intercept the suspension, persist the step result.

2. **Resume = Skip-ahead**: On recovery, deserialize the step results and call `resumeWith(result)` to advance through the state machine without re-executing side effects.

3. **Custom CoroutineContext**: Provide a `DurableCoroutineDispatcher` that intercepts suspension points and performs journaling.

4. **`suspendCancellableCoroutine` as the Primitive**:

```kotlin
suspend fun <T> durableStep(stepId: String, block: suspend () -> T): T =
    suspendCancellableCoroutine { cont ->
        val cached = journal.lookup(stepId)
        if (cached != null) {
            cont.resume(cached as T)
        } else {
            scope.launch {
                val result = block()
                journal.record(stepId, result)
                cont.resume(result)
            }
        }
    }
```

### 8.3 Key Design Considerations for Kotlin

- **Serialization**: Kotlin continuations are NOT natively serializable. Use a journal/replay approach that re-executes from the top, using stored results to fast-forward (like DBOS/Restate).
- **Structured Concurrency**: Parent-child coroutine relationships map naturally to parent-child workflow relationships.
- **Cancellation**: `CancellableContinuation.invokeOnCancellation` provides hooks for cleanup on workflow cancellation.
- **Restate already has a Kotlin SDK** — study its API design for idiomatic patterns.
- **db-scheduler works directly in Kotlin** and has a community library (db-scheduler-additions) with coroutine support.

### 8.4 API Design Sketch for a Kotlin Durable Workflow Engine

```kotlin
// Workflow definition
@DurableWorkflow
suspend fun processOrder(ctx: WorkflowContext, order: Order): Receipt {
    // Durable step — result checkpointed to PostgreSQL
    val validated = ctx.step("validate") { validateOrder(order) }

    // Durable DB transaction — exactly-once via piggyback checkpoint
    val saved = ctx.transaction("save") { tx ->
        tx.execute("INSERT INTO orders ...")
        SavedOrder(id = generatedId)
    }

    // Durable sleep — survives restarts
    ctx.sleep(Duration.ofHours(1))

    // Durable service call
    val shipped = ctx.step("ship") { shippingService.ship(saved) }

    return Receipt(saved.id, shipped.trackingNumber)
}

// Queue-based execution
val orderQueue = DurableQueue("orders", concurrency = 10)
orderQueue.enqueue(::processOrder, order)

// Idempotent execution
ctx.withWorkflowId("order-${order.id}") {
    processOrder(ctx, order)
}
```

---

## 9. Architectural Decision Matrix

### For a Kotlin Durable Workflow Engine, the key decisions are:

| Decision | Option A | Option B | Option C | Recommendation |
|----------|----------|----------|----------|----------------|
| **Topology** | Library (DBOS model) | External server (Restate model) | Hybrid | **Library** — simplest for Kotlin backends, no extra infra |
| **Storage** | PostgreSQL only | Embedded (RocksDB) | Pluggable | **PostgreSQL** — teams already have it, SQL observability, ACID |
| **Replay model** | Checkpoint skip-ahead | Full journal replay | DAG orchestration | **Checkpoint skip-ahead** — simpler, works with Kotlin coroutines |
| **Exactly-once** | Piggyback checkpoint (same DB) | Journal deduplication | At-least-once only | **Piggyback** for DB ops, at-least-once for external |
| **Queue impl** | SKIP LOCKED | LISTEN/NOTIFY + polling | External (Redis/Kafka) | **SKIP LOCKED** — proven, no extra infra |
| **Worker dispatch** | Polling | Push (WebSocket/gRPC) | Hybrid | **Polling with immediate execution** — simplest for library model |
| **Serialization** | JSON (kotlinx.serialization) | Protobuf | Java Serialization | **JSON** via kotlinx.serialization — Kotlin-native, readable |
| **Versioning** | Patching + version tags | Deployment-based | None | **Patching + version tags** — proven by DBOS |
| **Concurrency control** | SKIP LOCKED for claiming | Optimistic (version column) | Both | **Both** — SKIP LOCKED for task claiming, OCC for state updates |

### Recommended Primitives to Implement

1. **Workflow** — Deterministic orchestration function (`suspend fun`)
2. **Step** — Checkpointed side effect (`ctx.step("name") { ... }`)
3. **Transaction** — Exactly-once DB operation (`ctx.transaction("name") { tx -> ... }`)
4. **Queue** — Durable task queue with concurrency/rate limits
5. **Sleep** — Durable timer that survives restarts
6. **Send/Recv** — Inter-workflow messaging
7. **Events** — Workflow pub/sub (set_event/get_event)
8. **Idempotency** — Workflow ID as natural idempotency key

### Minimum Viable Schema

```sql
CREATE TABLE workflow_status (
    workflow_id         UUID PRIMARY KEY,
    status              TEXT NOT NULL,  -- PENDING, SUCCESS, ERROR, ENQUEUED, CANCELLED
    workflow_name       TEXT NOT NULL,
    inputs              JSONB,
    output              JSONB,
    error               JSONB,
    application_version TEXT,
    executor_id         TEXT,
    queue_name          TEXT,
    priority            INT DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    recovery_attempts   INT DEFAULT 0
);

CREATE TABLE step_outputs (
    workflow_id     UUID NOT NULL REFERENCES workflow_status(workflow_id),
    step_index      INT NOT NULL,
    step_name       TEXT NOT NULL,
    output          JSONB,
    error           JSONB,
    completed_at    TIMESTAMPTZ,
    PRIMARY KEY (workflow_id, step_index)
);

CREATE TABLE transaction_completions (
    workflow_id     UUID NOT NULL,
    step_index      INT NOT NULL,
    completed_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (workflow_id, step_index)
);

CREATE TABLE notifications (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    destination_id  UUID NOT NULL,
    topic           TEXT,
    message         JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE workflow_events (
    workflow_id     UUID NOT NULL,
    key             TEXT NOT NULL,
    value           JSONB,
    PRIMARY KEY (workflow_id, key)
);

-- Indexes for queue operations
CREATE INDEX idx_workflow_queue ON workflow_status (queue_name, priority, created_at)
    WHERE status = 'ENQUEUED';
CREATE INDEX idx_workflow_recovery ON workflow_status (executor_id, status)
    WHERE status = 'PENDING';
CREATE INDEX idx_workflow_updated ON workflow_status (updated_at);
```

---

## Sources

### DBOS
- [DBOS Architecture Documentation](https://docs.dbos.dev/architecture)
- [DBOS System Tables](https://docs.dbos.dev/explanations/system-tables)
- [Why Postgres for Durable Execution](https://www.dbos.dev/blog/why-postgres-durable-execution)
- [Why Workflows Should Be Postgres Rows](https://www.dbos.dev/blog/why-workflows-should-be-postgres-rows)
- [Handling Failures with Forks](https://www.dbos.dev/blog/handling-failures-workflow-forks)
- [Durable Queues](https://www.dbos.dev/blog/durable-queues)
- [Why Lightweight Durable Execution](https://www.dbos.dev/blog/what-is-lightweight-durable-execution)
- [DBOS vs Temporal](https://www.dbos.dev/compare/compare-dbos-vs-temporal-dbos)
- [DBOS Python SDK](https://github.com/dbos-inc/dbos-transact-py)
- [DBOS Java SDK](https://github.com/dbos-inc/dbos-transact-java)

### Restate
- [Restate Architecture](https://docs.restate.dev/references/architecture)
- [Building a Durable Execution Engine from First Principles](https://www.restate.dev/blog/building-a-modern-durable-execution-engine-from-first-principles)
- [Anatomy of a Durable Execution Stack](https://restate.dev/blog/the-anatomy-of-a-durable-execution-stack-from-first-principles/)
- [SQL Introspection API](https://docs.restate.dev/references/sql-introspection)
- [Error Handling](https://docs.restate.dev/guides/error-handling)
- [Versioning](https://docs.restate.dev/operate/versioning/)
- [Java/Kotlin SDK](https://docs.restate.dev/services/invocation/clients/java-sdk)
- [Distributed Restate](https://www.restate.dev/blog/distributed-restate-a-first-look)

### Hatchet
- [Hatchet Architecture](https://docs.hatchet.run/home/architecture)
- [Declarative DAGs](https://docs.hatchet.run/home/dags)
- [Durable Execution](https://docs.hatchet.run/home/durable-execution)
- [Multi-tenant Queues in Postgres](https://hatchet.run/blog/multi-tenant-queues)
- [Postgres Partitioning](https://hatchet.run/blog/postgres-partitioning)
- [Postgres Events Table](https://hatchet.run/blog/postgres-events-table)
- [V1 Core Schema](https://github.com/hatchet-dev/hatchet/blob/main/sql/schema/v1-core.sql)
- [Guarantees & Tradeoffs](https://docs.hatchet.run/home/guarantees-and-tradeoffs)

### db-scheduler
- [db-scheduler GitHub](https://github.com/kagkarlsson/db-scheduler)
- [Transactionally Staged Jobs](https://blogg.bekk.no/transactionally-staged-jobs-with-db-scheduler-and-spring-7c3a609c132b)
- [Task Schedulers in Java (Foojay)](https://foojay.io/today/task-schedulers-in-java-modern-alternatives-to-quartz-scheduler/)
- [db-scheduler-ui](https://github.com/bekk/db-scheduler-ui)
- [db-scheduler-additions (Kotlin)](https://github.com/osoykan/db-scheduler-additions)

### Foundational Patterns
- [Demystifying Determinism in Durable Execution — Jack Vanlightly](https://jack-vanlightly.com/blog/2025/11/24/demystifying-determinism-in-durable-execution)
- [The Durable Function Tree — Jack Vanlightly](https://jack-vanlightly.com/blog/2025/12/4/the-durable-function-tree-part-1)
- [Saga Pattern — microservices.io](https://microservices.io/patterns/data/saga.html)
- [Transactional Outbox — microservices.io](https://microservices.io/patterns/data/transactional-outbox.html)
- [On Idempotency Keys — Gunnar Morling](https://www.morling.dev/blog/on-idempotency-keys/)
- [Durable Functions Code Constraints — Azure](https://learn.microsoft.com/en-us/azure/azure-functions/durable/durable-functions-code-constraints)
- [Temporal Architecture — DeepWiki](https://deepwiki.com/temporalio/temporal/1.1-architecture)
- [SKIP LOCKED — Inferable](https://www.inferable.ai/blog/posts/postgres-skip-locked)
- [Inside Kotlin Coroutines: State Machines — ProAndroidDev](https://proandroiddev.com/inside-kotlin-coroutines-state-machines-continuations-and-structured-concurrency-b8d3d4e48e62)
- [Distributed Async Await — Resonate HQ](https://docs.resonatehq.io/concepts/distributed-async-await)
