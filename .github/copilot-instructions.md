# Copilot Instructions for kotlin-durable

## Project Overview

**kotlin-durable** is a durable task execution engine for orchestrating distributed workflows in Kotlin.
It executes workflows as directed acyclic graphs (DAGs) with reliability guarantees including persistence,
automatic retries, skip conditions, and failure handlers.

- **Group**: `io.effectivelabs`
- **Language**: Kotlin 2.1.10 on JVM 21
- **Build**: Gradle with Kotlin DSL (`build.gradle.kts`)

## Architecture

This project follows **Hexagonal Architecture** with clear layer separation:

| Layer | Package | Purpose |
|-------|---------|---------|
| Domain Model | `domain/model` | Core entities: `WorkflowDefinition`, `TaskRecord`, `WorkflowRunRecord`, `TaskEvent`, `RetryPolicy`, `SkipCondition` |
| Ports | `domain/port` | Interfaces/contracts: `DurableTaskEngine`, `Workflow`, `WorkflowBuilder`, `TaskRepository`, `Clock`, `Scheduler`, `LeaderElection` |
| Services | `domain/service` | Business logic: `DagResolver`, `RetryDelayCalculator`, `WorkflowCompletionChecker`, `SkipEvaluator` |
| Application | `application` | Orchestration: `DagTaskEngine`, `TaskExecutor`, `TaskPoller`, `WorkflowHandle`, `StepContextImpl` |
| Adapters | `adapter` | Implementations: in-memory repositories, `FakeClock`, `ManualScheduler` |
| DSL | `dsl` | Workflow builder DSL |

## Coding Conventions

- Use **idiomatic Kotlin**: data classes, sealed classes, extension functions, coroutines where appropriate
- Follow the hexagonal architecture boundaries — domain code must not depend on adapter/application code
- New repository implementations go in `adapter/`; interfaces go in `domain/port/`
- Prefer **immutable data** (val over var); use copy() for state transitions
- Use the existing `TaskEvent` pattern for tracking state changes

## Testing

- Tests live under `src/test/kotlin/`
- Use `kotlin.test` assertions (`assertEquals`, `assertTrue`, etc.)
- Use `TestEngineBuilder` to set up the in-memory engine for integration/acceptance tests
- Use `FakeClock` and `ManualScheduler` for deterministic time-based tests
- Unit test individual domain services independently
- Run tests with: `./gradlew test`

## Build & Lint

```bash
# Build the project
./gradlew build

# Run tests only
./gradlew test

# Check for compilation errors
./gradlew compileKotlin compileTestKotlin
```

## Key Patterns

- **Workflow DSL**: `engine.workflow<InputType>("name") { step("stepName") { ... } }`
- **Dependency injection**: The `DagTaskEngine` is composed with repository and service dependencies
- **Queue-based execution**: Tasks move through `ReadyQueueRepository` for scheduling
- **Event sourcing**: All state changes are captured as `TaskEvent` records
- **Idempotency**: State-based checks prevent duplicate task processing
- **Multi-tenancy**: Workflow runs accept a `tenantId` parameter

## Adding New Features

1. Define new interfaces in `domain/port/` if adding an abstraction
2. Add domain models in `domain/model/`
3. Implement business logic in `domain/service/`
4. Wire up in `application/` (e.g. `DagTaskEngine`)
5. Provide in-memory implementations in `adapter/inmemory/` for testing
6. Write acceptance tests using `TestEngineBuilder`
