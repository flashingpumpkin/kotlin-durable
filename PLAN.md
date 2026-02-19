# Exposed + PostgreSQL PoC Plan

## Current State

V1-V4 are complete: domain model, domain services, in-memory adapters, engine core.
All 5 port interfaces have in-memory implementations and passing tests.
The architecture is hexagonal — new Postgres adapters just implement the existing port interfaces.

## Goal

Get the V10 slice (PostgreSQL schema + adapters) working as a PoC using **Exposed ORM**
instead of raw SQL, tested against a real Postgres via Docker Compose + Testcontainers.

## What We're Building

### 1. Docker Compose file
- `docker-compose.yml` with Postgres 16, port 5432, db `durable`, user/pass `durable`/`durable`

### 2. Gradle dependencies
Add to `build.gradle.kts`:
- `org.jetbrains.exposed:exposed-core`
- `org.jetbrains.exposed:exposed-dao`
- `org.jetbrains.exposed:exposed-jdbc`
- `org.jetbrains.exposed:exposed-java-time` (for `Instant` columns)
- `org.postgresql:postgresql` (JDBC driver)
- `testImplementation: org.testcontainers:postgresql` + `org.testcontainers:junit-jupiter`

### 3. Exposed Table Definitions
`adapter/postgres/table/` — Exposed `Table` objects mapping to the schema from the design doc:

| Exposed Table Object | Maps To | Key Columns |
|---|---|---|
| `WorkflowRunsTable` | `workflow_runs` | id (UUID PK), workflow_name, tenant_id, status, input (jsonb as text), created_at, completed_at |
| `TasksTable` | `tasks` | workflow_run_id + task_name (composite PK), status, parent_names (text), pending_parent_count, output (text), error, retry_count, max_retries, created_at, started_at, completed_at |
| `ReadyQueueTable` | `ready_queue` | id (auto-increment BIGINT PK), workflow_run_id, task_name, enqueued_at |
| `TaskEventsTable` | `task_events` | id (auto-increment), workflow_run_id, task_name, event_type, data (text), timestamp |
| `TimersTable` | `durable_timers` | workflow_run_id + task_name (composite PK), tenant_id, wake_at, fired |

### 4. Exposed Repository Implementations
`adapter/postgres/` — 5 repository classes implementing existing port interfaces:

- **`ExposedWorkflowRunRepository`** — implements `WorkflowRunRepository`
  - `create()`: INSERT into workflow_runs
  - `findById()`: SELECT by PK
  - `updateStatus()`: UPDATE status column

- **`ExposedTaskRepository`** — implements `TaskRepository`
  - `createAll()`: batch INSERT
  - `findByName()`: SELECT by composite PK
  - `findAllByWorkflowRunId()`: SELECT WHERE workflow_run_id
  - `updateStatus()`: UPDATE status/output/error
  - `decrementPendingParents()`: UPDATE pending_parent_count - 1 WHERE parent matches, RETURNING ready tasks (pending_parent_count = 0)

- **`ExposedReadyQueueRepository`** — implements `ReadyQueueRepository`
  - `enqueue()` / `enqueueAll()`: INSERT
  - `claim()`: SELECT FOR UPDATE SKIP LOCKED + DELETE (Exposed raw SQL for SKIP LOCKED)

- **`ExposedEventRepository`** — implements `EventRepository`
  - `append()`: INSERT
  - `findByWorkflowRunId()`: SELECT WHERE workflow_run_id ORDER BY id

- **`ExposedTimerRepository`** — implements `TimerRepository`
  - `create()`: INSERT
  - `findExpired()`: SELECT WHERE wake_at <= now AND NOT fired LIMIT n
  - `markFired()`: UPDATE SET fired = true

### 5. Supporting Infrastructure
- **`ExposedIdGenerator`** — implements `IdGenerator` using a DB sequence
- **`SystemClock`** — implements `Clock` port using `java.time.Clock.systemUTC()`
- **`RealScheduler`** — implements `Scheduler` using `ScheduledExecutorService`

### 6. Schema initialization
- Exposed's `SchemaUtils.create()` in test setup (PoC — no migration framework yet)

### 7. Integration Tests (Testcontainers)
`src/test/kotlin/.../adapter/postgres/` — tests against real Postgres:

- **`ExposedWorkflowRunRepositoryTest`** — CRUD operations
- **`ExposedTaskRepositoryTest`** — CRUD + atomic `decrementPendingParents` under concurrency
- **`ExposedReadyQueueRepositoryTest`** — enqueue + SKIP LOCKED claim (concurrent claims, no double-claim)
- **`ExposedEventRepositoryTest`** — append + query
- **`ExposedTimerRepositoryTest`** — create + findExpired + markFired
- **`PostgresLinearDagTest`** — end-to-end: same linear DAG acceptance test (a→b→c) but wired to Postgres adapters instead of in-memory. This proves the engine works against real Postgres.

## What We're NOT Building (out of scope for PoC)
- Fair queuing / block-based IDs (V9)
- Distributed coordination / heartbeat / leader election (V11)
- Flyway/Liquibase migrations
- JSONB serialization (store output as toString() for now)
- Connection pooling (HikariCP) — use Exposed's direct connection for PoC

## Order of Implementation

1. `docker-compose.yml`
2. Update `build.gradle.kts` with dependencies
3. Exposed table definitions (5 files)
4. Repository implementations (5 files) + IdGenerator + SystemClock + RealScheduler
5. Integration tests with Testcontainers (6 test files)
6. Run all tests (existing in-memory + new Postgres) and verify green
