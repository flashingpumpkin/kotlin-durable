# In-Memory Testing Approaches for Durable Workflow Engines

> **Purpose**: This document provides a comprehensive reference for designing acceptance tests for a Kotlin durable workflow engine without requiring real infrastructure (PostgreSQL, message brokers, etc.). It covers how existing engines handle testing, in-memory double patterns, time control, acceptance test patterns, and interface design for testability.

---

## Table of Contents

1. [How Existing Workflow Engines Handle Testing](#1-how-existing-workflow-engines-handle-testing)
2. [In-Memory Double Patterns](#2-in-memory-double-patterns)
3. [Time Control in Tests](#3-time-control-in-tests)
4. [Acceptance Test Patterns for Workflows](#4-acceptance-test-patterns-for-workflows)
5. [Interface Design for Testability](#5-interface-design-for-testability)
6. [Recommended Architecture for a Kotlin Engine Test Harness](#6-recommended-architecture-for-a-kotlin-engine-test-harness)

---

## 1. How Existing Workflow Engines Handle Testing

### 1.1 Temporal: TestWorkflowEnvironment

Temporal provides the most mature testing framework among workflow engines. The `TestWorkflowEnvironment` is an in-memory implementation of the Temporal service with automatic time skipping.

**Architecture:**

The test environment consists of three components:
1. **In-memory Temporal service** -- a lightweight server (compiled from the Java SDK via GraalVM) that maintains an internal clock independent of `System.currentTimeMillis()`
2. **TestWorkflowExtension** (JUnit 5) / **TestWorkflowRule** (JUnit 4) -- manages the test environment lifecycle and worker registration
3. **TestActivityEnvironment** -- isolated activity testing with mocked context, heartbeat listening, and cancellation

**Time Skipping Mechanism:**

The time-skipping server maintains a virtual clock. When the only pending operations are timers (no activities or Nexus operations are executing), the server automatically advances its internal clock to the next timer expiration. This means:

- `Workflow.sleep(Duration.ofDays(30))` completes instantly in tests
- Timer-based conditional timeouts resolve immediately
- Activities running in real time block time advancement (preventing false fast-forwards during real work)

**Setup Pattern (JUnit 5):**

```java
@RegisterExtension
public static final TestWorkflowExtension testWorkflowExtension =
    TestWorkflowExtension.newBuilder()
        .setWorkflowTypes(OrderWorkflowImpl.class)
        .setActivityImplementations(new OrderActivitiesImpl())
        .build();

@Test
public void testOrderWorkflow(
        TestWorkflowEnvironment testEnv,
        WorkflowClient client,
        Worker worker) {
    // Start workflow
    OrderWorkflow workflow = client.newWorkflowStub(
        OrderWorkflow.class,
        WorkflowOptions.newBuilder()
            .setTaskQueue(testWorkflowExtension.getTaskQueue())
            .build());

    // Execute and verify
    OrderResult result = workflow.processOrder(new Order("item-1", 100));
    assertEquals("SHIPPED", result.getStatus());
}
```

**Activity Mocking with Mockito:**

```java
@Test
public void testWithMockedActivities(
        TestWorkflowEnvironment testEnv, Worker worker) {
    // Mock the activity interface
    OrderActivities activities = mock(OrderActivities.class);
    when(activities.chargeCard(any()))
        .thenReturn(new PaymentResult("txn-123", true));
    when(activities.shipOrder(any()))
        .thenReturn(new ShipmentResult("track-456"));

    // Register mocked activities
    worker.registerActivitiesImplementations(activities);
    testEnv.start();

    // Execute workflow -- activities use mocked implementations
    WorkflowClient client = testEnv.getWorkflowClient();
    OrderWorkflow workflow = client.newWorkflowStub(OrderWorkflow.class, options);
    OrderResult result = workflow.processOrder(order);

    // Verify both the result and the activity invocations
    assertEquals("SHIPPED", result.getStatus());
    verify(activities).chargeCard(any());
    verify(activities).shipOrder(any());
}
```

**Replay Testing (Determinism Validation):**

```java
// Validate that current workflow code is compatible with historical executions
@Test
public void testWorkflowReplay() throws Exception {
    File historyFile = new File("src/test/resources/order_workflow_history.json");
    WorkflowReplayer.replayWorkflowExecution(historyFile, OrderWorkflowImpl.class);
    // Throws if the workflow code has non-deterministic changes
}
```

**Key Insight:** Temporal's approach separates concerns cleanly: the test environment is a real (but lightweight) server, activities are mockable via standard mocking frameworks, and time is controlled by the server. The downside is the test server binary dependency.

### 1.2 DBOS: SQLite-Backed Test Mode with Sleep Skipping

DBOS takes a simpler approach: workflows, steps, and transactions are ordinary functions that can be tested with standard testing frameworks.

**Test Setup Pattern (Python, applicable concepts to Java):**

```python
import pytest
from dbos import DBOS, DBOSConfig

@pytest.fixture()
def dbos_test_env():
    DBOS.destroy()  # Clean up any existing instance
    config = DBOSConfig(
        name="test-app",
        # Use SQLite instead of PostgreSQL for tests
        system_database_url="sqlite:///test_workflows.sqlite",
    )
    DBOS(config=config)
    DBOS.reset_system_database()  # Clean slate
    DBOS.launch()
    yield
    DBOS.destroy()

def test_order_workflow(dbos_test_env):
    result = process_order(Order("item-1", 100))
    assert result.status == "SHIPPED"
```

**Sleep Skipping:**

DBOS has a `skip_sleep` flag in its system database implementation. When `skip_sleep=True`, the `time.sleep()` call is bypassed entirely while the checkpoint (wake-up time) is still recorded to the database:

```python
# From DBOS _sys_db.py (simplified):
def sleep(self, workflow_id, function_id, seconds):
    end_time = time.time() + seconds
    self.record_operation_result(workflow_id, function_id, end_time)
    if not self.skip_sleep:       # <-- Test mode: skip the actual sleep
        duration = max(0, end_time - time.time())
        time.sleep(duration)
```

This means tests can verify sleep checkpointing without waiting for real time to pass.

**Activity/Step Mocking:**

DBOS supports standard Python mocking:

```python
from unittest.mock import patch

def test_workflow_with_mocked_step(dbos_test_env):
    with patch("myapp.charge_card") as mock_charge:
        mock_charge.return_value = PaymentResult("txn-123", True)
        result = process_order(Order("item-1", 100))
        mock_charge.assert_called_once()
```

**DBOS Java SDK Testing:**

The Java SDK follows the same pattern. Workflows are annotated with `@Workflow`, steps use `DBOS.runStep()`, and testing uses standard JUnit. PostgreSQL is required (no SQLite fallback in Java), but you can use an embedded PostgreSQL or Testcontainers:

```java
// Java DBOS test pattern (conceptual)
@BeforeEach
void setup() {
    DBOS.destroy();
    DBOS.configure(new DBOSConfig()
        .systemDatabaseUrl(embeddedPostgres.getJdbcUrl()));
    DBOS.resetSystemDatabase();
    DBOS.launch();
}

@Test
void testWorkflow() {
    OrderResult result = processOrder(new Order("item-1", 100));
    assertEquals("SHIPPED", result.getStatus());
}
```

**Key Insight:** DBOS's simplicity is its strength -- workflows are just functions, so testing is just calling functions. The `skip_sleep` flag is elegant: it decouples time behavior from correctness verification.

### 1.3 Restate: Testcontainers-Based Integration Testing

Restate takes a different approach: rather than providing in-memory doubles, it runs the real Restate server in a Docker container via Testcontainers.

**Java/Kotlin SDK Testing Setup:**

```java
// Dependency: dev.restate:sdk-testing:2.5.0

@RestateTest
class OrderServiceTest {
    // Service to test -- annotated with @BindService
    @BindService
    OrderService service = new OrderService();

    @Test
    void testProcessOrder(@RestateClient Client ingressClient) {
        var client = OrderServiceClient.fromClient(ingressClient);
        var result = client.processOrder(new Order("item-1", 100));
        assertEquals("SHIPPED", result.getStatus());
    }
}
```

**Kotlin Variant:**

```kotlin
@RestateTest
class OrderServiceTest {
    @BindService
    val service = OrderService()

    @Test
    fun testProcessOrder(@RestateClient ingressClient: Client) = runTest {
        val client = OrderServiceClient.fromClient(ingressClient)
        val result = client.processOrder(Order("item-1", 100))
        assertEquals("SHIPPED", result.status)
    }
}
```

**State Inspection:**

Restate's test environment allows direct state inspection for Virtual Objects:

```typescript
// TypeScript example (same concept applies to Java)
const state = restateTestEnvironment.stateOf(router, "myKey");
expect(await state.getAll()).toStrictEqual({});
await state.set("count", 123);
expect(await state.get("count")).toBe(123);
```

**Limitations:**
- Requires Docker (not purely in-memory)
- Test startup takes 5-20 seconds (container initialization)
- No built-in time skipping -- sleeps run in real time (limiting tests of long-running workflows)
- Shared server instance across test methods in a class

**Key Insight:** Restate prioritizes production fidelity over speed. The trade-off is slower tests but higher confidence. For a library-embedded engine (like what we're building), we can do better with in-memory doubles.

### 1.4 Hatchet: Minimal Test Tooling

Hatchet's testing story is the least developed among the engines studied. Since tasks and workflows are "simple functions," the recommended approach is to test them directly:

```python
# Hatchet tasks are regular functions, testable directly
def test_validate_task():
    result = validate({"order_id": "123", "amount": 100}, mock_ctx)
    assert result["valid"] is True

def test_charge_task():
    result = charge({"order_id": "123", "card": "tok_visa"}, mock_ctx)
    assert result["charged"] is True
```

For DAG-level integration testing, Hatchet relies on running the full engine (typically via Docker Compose). There are no published in-memory test doubles or time-skipping utilities.

**Load Testing:**

Hatchet publishes a load testing container (`ghcr.io/hatchet-dev/hatchet/hatchet-loadtest`) for benchmarking, but this is for performance testing, not functional acceptance testing.

**Key Insight:** Hatchet's DAG model makes individual task testing easy (they're just functions), but testing workflow composition (DAG ordering, fan-out/fan-in) requires the real engine. This is a gap we should fill in our design.

---

## 2. In-Memory Double Patterns

### 2.1 In-Memory Workflow State Store (HashMap-Based)

The core pattern: define a repository interface for workflow state, provide a PostgreSQL implementation for production and a `ConcurrentHashMap`-based implementation for tests.

```kotlin
// Repository interface
interface WorkflowStateStore {
    suspend fun createWorkflow(workflow: WorkflowRecord): Unit
    suspend fun getWorkflow(workflowId: UUID): WorkflowRecord?
    suspend fun updateWorkflowStatus(workflowId: UUID, status: WorkflowStatus): Unit
    suspend fun findPendingWorkflows(executorId: String): List<WorkflowRecord>

    suspend fun saveStepOutput(workflowId: UUID, stepIndex: Int, stepName: String, output: Any?): Unit
    suspend fun getStepOutput(workflowId: UUID, stepIndex: Int): StepOutputRecord?

    suspend fun saveTransactionCompletion(workflowId: UUID, stepIndex: Int): Unit
    suspend fun isTransactionCompleted(workflowId: UUID, stepIndex: Int): Boolean
}

// In-memory implementation for testing
class InMemoryWorkflowStateStore : WorkflowStateStore {
    private val workflows = ConcurrentHashMap<UUID, WorkflowRecord>()
    private val stepOutputs = ConcurrentHashMap<StepKey, StepOutputRecord>()
    private val transactionCompletions = ConcurrentHashMap<StepKey, Boolean>()

    data class StepKey(val workflowId: UUID, val stepIndex: Int)

    override suspend fun createWorkflow(workflow: WorkflowRecord) {
        workflows[workflow.workflowId] = workflow
    }

    override suspend fun getWorkflow(workflowId: UUID): WorkflowRecord? {
        return workflows[workflowId]
    }

    override suspend fun updateWorkflowStatus(workflowId: UUID, status: WorkflowStatus) {
        workflows.computeIfPresent(workflowId) { _, existing ->
            existing.copy(status = status, updatedAt = Instant.now())
        }
    }

    override suspend fun findPendingWorkflows(executorId: String): List<WorkflowRecord> {
        return workflows.values.filter {
            it.status == WorkflowStatus.PENDING && it.executorId == executorId
        }
    }

    override suspend fun saveStepOutput(
        workflowId: UUID, stepIndex: Int, stepName: String, output: Any?
    ) {
        stepOutputs[StepKey(workflowId, stepIndex)] = StepOutputRecord(
            workflowId = workflowId,
            stepIndex = stepIndex,
            stepName = stepName,
            output = output,
            completedAt = Instant.now()
        )
    }

    override suspend fun getStepOutput(workflowId: UUID, stepIndex: Int): StepOutputRecord? {
        return stepOutputs[StepKey(workflowId, stepIndex)]
    }

    override suspend fun saveTransactionCompletion(workflowId: UUID, stepIndex: Int) {
        transactionCompletions[StepKey(workflowId, stepIndex)] = true
    }

    override suspend fun isTransactionCompleted(workflowId: UUID, stepIndex: Int): Boolean {
        return transactionCompletions[StepKey(workflowId, stepIndex)] ?: false
    }

    // Test utility methods
    fun reset() {
        workflows.clear()
        stepOutputs.clear()
        transactionCompletions.clear()
    }

    fun allWorkflows(): List<WorkflowRecord> = workflows.values.toList()
    fun allStepOutputs(): List<StepOutputRecord> = stepOutputs.values.toList()
}
```

### 2.2 In-Memory SKIP LOCKED Semantics

`SELECT FOR UPDATE SKIP LOCKED` is the key mechanism for concurrent task claiming. Implementing it in-memory requires a lock tracking mechanism:

```kotlin
class InMemoryTaskQueue<T>(
    private val comparator: Comparator<T> = Comparator { _, _ -> 0 }
) {
    private val items = mutableListOf<T>()
    private val lockedItems = ConcurrentHashMap.newKeySet<T>()
    private val mutex = Mutex()  // Kotlin coroutine mutex

    /**
     * Add an item to the queue.
     */
    suspend fun enqueue(item: T) {
        mutex.withLock {
            items.add(item)
            items.sortWith(comparator)
        }
    }

    /**
     * Claim up to [limit] items that are not currently locked by another consumer.
     * This mimics SELECT ... FOR UPDATE SKIP LOCKED LIMIT N.
     *
     * Returns the claimed items. The caller MUST call [release] or [complete]
     * when done processing each item.
     */
    suspend fun claimBatch(limit: Int, predicate: (T) -> Boolean = { true }): List<T> {
        mutex.withLock {
            val claimed = mutableListOf<T>()
            for (item in items) {
                if (claimed.size >= limit) break
                if (!lockedItems.contains(item) && predicate(item)) {
                    lockedItems.add(item)
                    claimed.add(item)
                }
            }
            return claimed
        }
    }

    /**
     * Release the lock on an item without removing it (like rolling back a transaction).
     */
    suspend fun release(item: T) {
        lockedItems.remove(item)
    }

    /**
     * Complete processing of an item: unlock and remove from queue.
     */
    suspend fun complete(item: T) {
        mutex.withLock {
            lockedItems.remove(item)
            items.remove(item)
        }
    }

    /**
     * Return an item to the queue with modified state (for rescheduling).
     */
    suspend fun reschedule(oldItem: T, newItem: T) {
        mutex.withLock {
            lockedItems.remove(oldItem)
            items.remove(oldItem)
            items.add(newItem)
            items.sortWith(comparator)
        }
    }

    // Test utilities
    fun size(): Int = items.size
    fun lockedCount(): Int = lockedItems.size
    fun peek(): List<T> = items.toList()
}
```

**Concurrent Claiming Test:**

```kotlin
@Test
fun `two consumers claim different items`() = runTest {
    val queue = InMemoryTaskQueue<Task>(compareBy { it.priority })

    // Enqueue 4 tasks
    repeat(4) { queue.enqueue(Task(id = it, priority = it)) }

    // Consumer A claims first 2
    val batchA = queue.claimBatch(2)
    assertEquals(2, batchA.size)

    // Consumer B claims next 2 (skipping A's locked items)
    val batchB = queue.claimBatch(2)
    assertEquals(2, batchB.size)

    // No overlap
    assertTrue(batchA.intersect(batchB.toSet()).isEmpty())

    // No more available
    val batchC = queue.claimBatch(2)
    assertTrue(batchC.isEmpty())
}
```

### 2.3 In-Memory Event/Notification Store

```kotlin
class InMemoryNotificationStore {
    private val notifications = ConcurrentHashMap<UUID, MutableList<NotificationRecord>>()
    private val events = ConcurrentHashMap<EventKey, Any?>()
    private val notificationListeners = ConcurrentHashMap<ListenerKey, CompletableDeferred<NotificationRecord>>()

    data class EventKey(val workflowId: UUID, val key: String)
    data class ListenerKey(val destinationId: UUID, val topic: String)

    /**
     * Send a notification to a workflow (like DBOS send/recv or Restate awakeables).
     */
    suspend fun sendNotification(destinationId: UUID, topic: String, message: Any?) {
        val record = NotificationRecord(
            destinationId = destinationId,
            topic = topic,
            message = message,
            createdAt = Instant.now()
        )
        notifications.getOrPut(destinationId) { mutableListOf() }.add(record)

        // Wake up any listener waiting for this notification
        val listenerKey = ListenerKey(destinationId, topic)
        notificationListeners.remove(listenerKey)?.complete(record)
    }

    /**
     * Receive a notification, suspending until one arrives or timeout.
     * This replaces DBOS's LISTEN/NOTIFY + polling approach.
     */
    suspend fun receiveNotification(
        destinationId: UUID,
        topic: String,
        timeout: Duration
    ): NotificationRecord? {
        // Check if already received
        val existing = notifications[destinationId]?.firstOrNull { it.topic == topic }
        if (existing != null) return existing

        // Wait for notification
        val deferred = CompletableDeferred<NotificationRecord>()
        val listenerKey = ListenerKey(destinationId, topic)
        notificationListeners[listenerKey] = deferred

        return withTimeoutOrNull(timeout.toMillis()) {
            deferred.await()
        }
    }

    /**
     * Set an event (like DBOS set_event or Restate durable promises).
     */
    suspend fun setEvent(workflowId: UUID, key: String, value: Any?) {
        events[EventKey(workflowId, key)] = value
    }

    /**
     * Get an event value.
     */
    suspend fun getEvent(workflowId: UUID, key: String): Any? {
        return events[EventKey(workflowId, key)]
    }

    fun reset() {
        notifications.clear()
        events.clear()
        notificationListeners.values.forEach { it.cancel() }
        notificationListeners.clear()
    }
}
```

### 2.4 Thread Safety Considerations

For in-memory stores used in concurrent tests:

| Data Structure | Thread Safety | Use Case |
|---------------|--------------|----------|
| `ConcurrentHashMap` | Lock-free reads, segment-locked writes | Primary state storage |
| `Mutex` (kotlinx.coroutines) | Coroutine-friendly mutual exclusion | Protecting multi-step operations (claim + lock) |
| `ConcurrentHashMap.newKeySet()` | Thread-safe `Set` backed by CHM | Lock tracking |
| `CompletableDeferred` | Thread-safe single-shot completion | Notification wake-up |
| `MutableStateFlow` / `Channel` | Coroutine-safe communication | Event streaming |

**Important:** Use `kotlinx.coroutines.sync.Mutex` instead of `java.util.concurrent.locks.ReentrantLock` in coroutine code. The Kotlin mutex suspends rather than blocking the thread, which is critical for virtual time to work correctly in tests.

---

## 3. Time Control in Tests

### 3.1 kotlinx-coroutines-test: Virtual Time

The `kotlinx-coroutines-test` module provides a virtual time system for testing coroutine-based code.

**Core Components:**

- **`TestCoroutineScheduler`** -- the shared source of virtual time, maintains a `currentTime` counter and a priority queue of scheduled tasks
- **`StandardTestDispatcher`** -- a coroutine dispatcher paired with a `TestCoroutineScheduler` that prevents coroutine execution until virtual time advances
- **`UnconfinedTestDispatcher`** -- executes coroutines eagerly before the first delay point
- **`TestScope`** -- a `CoroutineScope` with a built-in `TestCoroutineScheduler`, provides `currentTime` and time advancement methods
- **`runTest`** -- the entry point for coroutine tests, automatically creates a `TestScope` with virtual time

**How Virtual Time Works:**

When a coroutine calls `delay()`, the `StandardTestDispatcher` checks if its dispatcher implements the `Delay` interface (it does). Instead of waiting in real time, it inserts a `DelayedResumeTask` into the scheduler's priority queue keyed by `currentTime + delayMillis`. The coroutine suspends. No real time passes until:

1. `advanceTimeBy(millis)` -- advances virtual clock by the specified amount, executing all tasks scheduled within that window
2. `advanceUntilIdle()` -- advances virtual clock to the point where all pending coroutines complete
3. `runCurrent()` -- executes all tasks scheduled at the current virtual time (no advancement)

**Basic Example:**

```kotlin
@Test
fun `delay is instant in virtual time`() = runTest {
    assertEquals(0, currentTime)
    delay(1_000)
    assertEquals(1_000, currentTime)

    // Multiple concurrent delays
    coroutineScope {
        launch { delay(1_000) }
        launch { delay(2_000) }
        launch { delay(3_000) }
    }
    assertEquals(4_000, currentTime)  // 1000 + max(1000, 2000, 3000)
}
```

**Testing Concurrent Operations:**

```kotlin
@Test
fun `concurrent steps complete in parallel time`() = runTest {
    val repo = FakeUserRepository()  // each call delays 1000ms

    val result = coroutineScope {
        val name = async { repo.getName() }       // delay(1000)
        val friends = async { repo.getFriends() }  // delay(1000)
        val profile = async { repo.getProfile() }  // delay(1000)
        User(name.await(), friends.await(), profile.await())
    }

    assertEquals("Ben", result.name)
    assertEquals(1_000, currentTime)  // All ran concurrently!
}
```

**Manual Time Advancement:**

```kotlin
@Test
fun `advance time step by step`() = runTest {
    var counter = 0
    backgroundScope.launch {
        while (true) {
            delay(1_000)
            counter++
        }
    }

    advanceTimeBy(1_001)  // Advance just past 1000ms
    assertEquals(1, counter)

    advanceTimeBy(2_000)  // Advance another 2000ms (total: 3001)
    assertEquals(3, counter)
}
```

**Important Subtlety with `advanceTimeBy`:**

`advanceTimeBy(delayTimeMillis)` advances time by the specified amount but does NOT execute tasks scheduled at exactly `currentTime + delayTimeMillis`. To execute those, call `runCurrent()` afterward:

```kotlin
advanceTimeBy(1000)  // Advances to time=1000 but does NOT run tasks at t=1000
runCurrent()         // NOW runs tasks scheduled at t=1000
```

### 3.2 Java Clock Abstraction

For non-coroutine code (e.g., timestamp generation, deadline checking), use Java's `Clock` abstraction:

```kotlin
// Interface for time-dependent components
class WorkflowEngine(
    private val clock: Clock = Clock.systemUTC(),
    private val stateStore: WorkflowStateStore,
) {
    fun createWorkflow(workflowId: UUID, name: String): WorkflowRecord {
        return WorkflowRecord(
            workflowId = workflowId,
            status = WorkflowStatus.PENDING,
            createdAt = Instant.now(clock),  // Uses injected clock
            updatedAt = Instant.now(clock),
        )
    }

    fun isWorkflowTimedOut(workflow: WorkflowRecord, timeout: Duration): Boolean {
        return Duration.between(workflow.createdAt, Instant.now(clock)) > timeout
    }
}

// In tests: use a fixed or offset clock
@Test
fun `workflow times out after deadline`() {
    val fixedClock = Clock.fixed(
        Instant.parse("2026-01-01T00:00:00Z"),
        ZoneOffset.UTC
    )
    val engine = WorkflowEngine(clock = fixedClock, stateStore = inMemoryStore)

    val workflow = engine.createWorkflow(UUID.randomUUID(), "test")

    // Advance clock by 2 hours
    val advancedClock = Clock.offset(fixedClock, Duration.ofHours(2))
    val engineLater = engine.copy(clock = advancedClock)

    assertTrue(engineLater.isWorkflowTimedOut(workflow, Duration.ofHours(1)))
    assertFalse(engineLater.isWorkflowTimedOut(workflow, Duration.ofHours(3)))
}
```

**Combining Clock and Virtual Time:**

For a durable workflow engine, you need both:
- **Virtual coroutine time** for `delay()`-based sleeps (via `TestCoroutineScheduler`)
- **Clock** for timestamps written to the state store

The solution is a `TestClock` that bridges both:

```kotlin
/**
 * A Clock that is driven by the TestCoroutineScheduler's virtual time.
 * Timestamps in the state store will match the virtual time in tests.
 */
class VirtualClock(
    private val scheduler: TestCoroutineScheduler,
    private val baseInstant: Instant = Instant.parse("2026-01-01T00:00:00Z"),
    private val zone: ZoneId = ZoneOffset.UTC
) : Clock() {

    override fun getZone(): ZoneId = zone
    override fun withZone(zone: ZoneId): Clock = VirtualClock(scheduler, baseInstant, zone)

    override fun instant(): Instant {
        return baseInstant.plusMillis(scheduler.currentTime)
    }
}

// Usage in tests:
@Test
fun `sleep checkpoints correct timestamp`() = runTest {
    val clock = VirtualClock(testScheduler)
    val engine = WorkflowEngine(clock = clock, stateStore = inMemoryStore)

    // At virtual time 0
    val workflow = engine.startWorkflow(::myWorkflow, input)

    advanceTimeBy(Duration.ofHours(1).toMillis())

    // The sleep checkpoint should reflect 1 hour of virtual time
    val stepOutput = inMemoryStore.getStepOutput(workflow.id, 0)
    assertEquals(
        Instant.parse("2026-01-01T01:00:00Z"),
        stepOutput?.completedAt
    )
}
```

### 3.3 Fast-Forwarding Through Durable Sleeps

There are three strategies for handling durable sleeps in tests:

**Strategy 1: Skip Sleeps Entirely (DBOS approach)**

Set a flag that bypasses the actual delay while still recording the checkpoint:

```kotlin
class WorkflowContext(
    val skipSleep: Boolean = false,  // Set to true in tests
    // ...
) {
    suspend fun sleep(duration: Duration) {
        val endTime = Instant.now(clock).plus(duration)
        stateStore.saveStepOutput(workflowId, stepIndex++, "sleep", endTime)

        if (!skipSleep) {
            delay(duration.toMillis())  // Real coroutine delay
        }
    }
}
```

Pros: Simplest approach, tests run instantly.
Cons: Does not test the actual suspension/resumption mechanics.

**Strategy 2: Virtual Time Advancement (Temporal approach)**

Use `runTest` with `advanceUntilIdle()` to let virtual time handle delays naturally:

```kotlin
@Test
fun `workflow with 24h sleep completes instantly`() = runTest {
    val engine = TestWorkflowEngine(testScope = this)
    val result = engine.execute(::orderWorkflow, order)

    // advanceUntilIdle() is called automatically by runTest
    assertEquals("SHIPPED", result.status)
    assertEquals(Duration.ofHours(24).toMillis(), currentTime)
}
```

Pros: Tests the actual delay mechanics. Virtual time reflects real workflow behavior.
Cons: Requires all time-dependent code to use the test dispatcher.

**Strategy 3: Explicit Time Control (Most Flexible)**

Give the test explicit control over time advancement:

```kotlin
@Test
fun `workflow suspends at sleep and resumes on advance`() = runTest {
    val engine = TestWorkflowEngine(testScope = this)

    // Start workflow in background
    val handle = backgroundScope.launch {
        engine.execute(::orderWorkflow, order)
    }

    // Advance past the validate and charge steps
    advanceUntilIdle()

    // Verify workflow is suspended at sleep
    val status = engine.getWorkflowStatus(workflowId)
    assertEquals(WorkflowStatus.SLEEPING, status)

    // Advance time past the sleep duration
    advanceTimeBy(Duration.ofHours(24).toMillis())
    runCurrent()

    // Verify workflow completed
    advanceUntilIdle()
    val finalStatus = engine.getWorkflowStatus(workflowId)
    assertEquals(WorkflowStatus.COMPLETED, finalStatus)
}
```

Pros: Can test intermediate states during long-running workflows.
Cons: More verbose test code.

**Recommendation:** Use Strategy 2 (virtual time) as the default for acceptance tests. Use Strategy 3 for tests that need to verify intermediate states. Avoid Strategy 1 unless you need backward compatibility with non-coroutine code.

---

## 4. Acceptance Test Patterns for Workflows

### 4.1 Testing DAG Execution Order

```kotlin
@Test
fun `steps execute in declared order`() = runTest {
    val executionLog = mutableListOf<String>()
    val engine = TestWorkflowEngine(testScope = this)

    suspend fun orderedWorkflow(ctx: WorkflowContext) {
        ctx.step("step-a") { executionLog.add("A") }
        ctx.step("step-b") { executionLog.add("B") }
        ctx.step("step-c") { executionLog.add("C") }
    }

    engine.execute(::orderedWorkflow)

    assertEquals(listOf("A", "B", "C"), executionLog)
}

@Test
fun `steps with dependencies execute after parents`() = runTest {
    val executionLog = Collections.synchronizedList(mutableListOf<String>())
    val completionTimes = ConcurrentHashMap<String, Long>()

    suspend fun dagWorkflow(ctx: WorkflowContext) {
        // A runs first
        ctx.step("A") {
            delay(100)
            executionLog.add("A")
            completionTimes["A"] = currentTime
        }

        // B and C run in parallel after A
        coroutineScope {
            launch {
                ctx.step("B") {
                    delay(200)
                    executionLog.add("B")
                    completionTimes["B"] = currentTime
                }
            }
            launch {
                ctx.step("C") {
                    delay(300)
                    executionLog.add("C")
                    completionTimes["C"] = currentTime
                }
            }
        }

        // D runs after both B and C complete
        ctx.step("D") {
            executionLog.add("D")
            completionTimes["D"] = currentTime
        }
    }

    engine.execute(::dagWorkflow)

    // A completes first
    assertEquals("A", executionLog.first())

    // B and C both started after A (at t=100) and ran concurrently
    assertTrue(completionTimes["B"]!! <= completionTimes["C"]!!)
    assertEquals(100 + 200, completionTimes["B"])  // t=300
    assertEquals(100 + 300, completionTimes["C"])  // t=400

    // D ran after both B and C
    assertEquals("D", executionLog.last())
    assertEquals(400, completionTimes["D"])  // t=400 (after C at t=400)
}
```

### 4.2 Testing Fan-Out / Fan-In

```kotlin
@Test
fun `fan-out processes items in parallel, fan-in aggregates`() = runTest {
    suspend fun fanOutWorkflow(ctx: WorkflowContext, items: List<String>): Int {
        // Fan-out: process each item concurrently
        val results = coroutineScope {
            items.map { item ->
                async {
                    ctx.step("process-$item") {
                        delay(1_000)  // Each takes 1 second
                        item.length
                    }
                }
            }.awaitAll()
        }

        // Fan-in: aggregate results
        return ctx.step("aggregate") {
            results.sum()
        }
    }

    val engine = TestWorkflowEngine(testScope = this)
    val result = engine.execute(::fanOutWorkflow, listOf("hello", "world", "!"))

    // All items processed in parallel -- total time is 1 second, not 3
    assertEquals(1_000, currentTime)
    assertEquals(11, result)  // 5 + 5 + 1
}

@Test
fun `fan-out with dynamic number of items`() = runTest {
    suspend fun dynamicFanOut(ctx: WorkflowContext, n: Int): List<Int> {
        // First step determines work items
        val items = ctx.step("discover") {
            (1..n).toList()
        }

        // Fan-out over discovered items
        return coroutineScope {
            items.map { item ->
                async {
                    ctx.step("process-$item") {
                        delay(100)
                        item * 2
                    }
                }
            }.awaitAll()
        }
    }

    val engine = TestWorkflowEngine(testScope = this)
    val result = engine.execute(::dynamicFanOut, 5)

    assertEquals(listOf(2, 4, 6, 8, 10), result)
}
```

### 4.3 Testing Failure and Retry Behavior

```kotlin
@Test
fun `step retries on transient failure with backoff`() = runTest {
    var attempts = 0
    val attemptTimes = mutableListOf<Long>()

    suspend fun retryWorkflow(ctx: WorkflowContext): String {
        return ctx.step(
            "flaky-step",
            retries = 3,
            backoff = ExponentialBackoff(initialDelay = 1_000, factor = 2.0)
        ) {
            attempts++
            attemptTimes.add(currentTime)
            if (attempts < 3) throw RuntimeException("Transient error")
            "success"
        }
    }

    val engine = TestWorkflowEngine(testScope = this)
    val result = engine.execute(::retryWorkflow)

    assertEquals("success", result)
    assertEquals(3, attempts)

    // Verify exponential backoff timing
    assertEquals(0, attemptTimes[0])      // First attempt at t=0
    assertEquals(1_000, attemptTimes[1])  // Retry 1 after 1s backoff
    assertEquals(3_000, attemptTimes[2])  // Retry 2 after 2s backoff (1s + 2s)
}

@Test
fun `step fails permanently after exhausting retries`() = runTest {
    var attempts = 0

    suspend fun alwaysFailWorkflow(ctx: WorkflowContext): String {
        return ctx.step("always-fails", retries = 2) {
            attempts++
            throw RuntimeException("Permanent error")
        }
    }

    val engine = TestWorkflowEngine(testScope = this)

    val exception = assertThrows<MaxRetriesExceededException> {
        engine.execute(::alwaysFailWorkflow)
    }

    assertEquals(3, attempts)  // Initial + 2 retries
    assertEquals("Permanent error", exception.cause?.message)

    // Workflow should be in ERROR state
    val status = engine.getWorkflowStatus(workflowId)
    assertEquals(WorkflowStatus.ERROR, status)
}

@Test
fun `terminal error skips retries`() = runTest {
    var attempts = 0

    suspend fun terminalErrorWorkflow(ctx: WorkflowContext): String {
        return ctx.step("terminal-fail", retries = 5) {
            attempts++
            throw TerminalError("Validation failed -- do not retry")
        }
    }

    val engine = TestWorkflowEngine(testScope = this)

    assertThrows<TerminalError> {
        engine.execute(::terminalErrorWorkflow)
    }

    assertEquals(1, attempts)  // No retries for terminal errors
}
```

### 4.4 Testing Idempotency

```kotlin
@Test
fun `workflow with same ID returns same result`() = runTest {
    var executionCount = 0

    suspend fun idempotentWorkflow(ctx: WorkflowContext, input: String): String {
        return ctx.step("process") {
            executionCount++
            "result-$input"
        }
    }

    val engine = TestWorkflowEngine(testScope = this)
    val workflowId = UUID.fromString("11111111-1111-1111-1111-111111111111")

    // Execute first time
    val result1 = engine.execute(::idempotentWorkflow, "hello", workflowId = workflowId)
    assertEquals("result-hello", result1)
    assertEquals(1, executionCount)

    // Execute again with same workflow ID
    val result2 = engine.execute(::idempotentWorkflow, "hello", workflowId = workflowId)
    assertEquals("result-hello", result2)
    assertEquals(1, executionCount)  // NOT re-executed
}

@Test
fun `step results are idempotent across recovery`() = runTest {
    val sideEffects = mutableListOf<String>()

    suspend fun workflowWithSideEffects(ctx: WorkflowContext): String {
        val a = ctx.step("step-a") {
            sideEffects.add("A-executed")
            "result-a"
        }
        val b = ctx.step("step-b") {
            sideEffects.add("B-executed")
            "result-b-$a"
        }
        return b
    }

    val engine = TestWorkflowEngine(testScope = this)
    val workflowId = UUID.randomUUID()

    // First execution
    engine.execute(::workflowWithSideEffects, workflowId = workflowId)
    assertEquals(listOf("A-executed", "B-executed"), sideEffects)

    // Simulate recovery: re-execute with same workflow ID
    sideEffects.clear()
    engine.recover(::workflowWithSideEffects, workflowId = workflowId)

    // Steps should NOT re-execute (results returned from checkpoint)
    assertTrue(sideEffects.isEmpty())
}
```

### 4.5 Testing Recovery After Simulated Crash

```kotlin
@Test
fun `workflow recovers from crash and resumes from last checkpoint`() = runTest {
    val executionLog = mutableListOf<String>()
    var shouldCrash = true

    suspend fun crashableWorkflow(ctx: WorkflowContext): String {
        val a = ctx.step("step-1") {
            executionLog.add("step-1")
            "result-1"
        }

        // Simulate crash between steps
        if (shouldCrash) {
            shouldCrash = false
            throw SimulatedCrashException("Process died")
        }

        val b = ctx.step("step-2") {
            executionLog.add("step-2")
            "result-2-$a"
        }

        return b
    }

    val stateStore = InMemoryWorkflowStateStore()
    val engine = TestWorkflowEngine(testScope = this, stateStore = stateStore)
    val workflowId = UUID.randomUUID()

    // First execution -- crashes after step-1
    assertThrows<SimulatedCrashException> {
        engine.execute(::crashableWorkflow, workflowId = workflowId)
    }
    assertEquals(listOf("step-1"), executionLog)

    // Step-1's output is checkpointed
    assertNotNull(stateStore.getStepOutput(workflowId, 0))

    // Recovery -- re-execute the same workflow
    executionLog.clear()
    val result = engine.execute(::crashableWorkflow, workflowId = workflowId)

    // Step-1 was NOT re-executed (returned from checkpoint)
    // Step-2 WAS executed
    assertEquals(listOf("step-2"), executionLog)
    assertEquals("result-2-result-1", result)
}

@Test
fun `workflow recovers mid-sleep and sleeps only remaining time`() = runTest {
    val clock = VirtualClock(testScheduler)
    val stateStore = InMemoryWorkflowStateStore()
    val engine = TestWorkflowEngine(
        testScope = this,
        stateStore = stateStore,
        clock = clock
    )

    suspend fun sleepyWorkflow(ctx: WorkflowContext): String {
        ctx.step("before-sleep") { "pre" }
        ctx.sleep(Duration.ofHours(24))
        return ctx.step("after-sleep") { "post" }
    }

    // Start workflow, advance 12 hours, then "crash"
    val workflowId = UUID.randomUUID()
    val job = backgroundScope.launch {
        engine.execute(::sleepyWorkflow, workflowId = workflowId)
    }

    advanceTimeBy(Duration.ofHours(12).toMillis())
    job.cancel()  // Simulate crash

    // Recovery at t=12h: sleep checkpoint says wake at t=24h
    // Should only sleep 12 more hours
    val result = engine.execute(::sleepyWorkflow, workflowId = workflowId)

    // Total time should be 24h (not 36h)
    assertEquals(Duration.ofHours(24).toMillis(), currentTime)
    assertEquals("post", result)
}
```

### 4.6 End-to-End Workflow Tests Completing in Milliseconds

```kotlin
@Test
fun `complete order workflow executes in virtual milliseconds`() = runTest {
    // Setup in-memory infrastructure
    val stateStore = InMemoryWorkflowStateStore()
    val taskQueue = InMemoryTaskQueue<WorkflowTask>()
    val clock = VirtualClock(testScheduler)

    val engine = TestWorkflowEngine(
        testScope = this,
        stateStore = stateStore,
        taskQueue = taskQueue,
        clock = clock,
    )

    // Define the complete order workflow
    suspend fun orderWorkflow(ctx: WorkflowContext, order: Order): Receipt {
        val validated = ctx.step("validate") { validateOrder(order) }
        val payment = ctx.step("charge") { chargeCard(validated) }
        ctx.sleep(Duration.ofDays(1))  // Wait for fraud check window
        val shipped = ctx.step("ship") { shipOrder(payment) }
        return Receipt(payment.txnId, shipped.trackingNumber)
    }

    // Execute
    val startTime = System.nanoTime()  // Real wall clock for test timing
    val result = engine.execute(::orderWorkflow, Order("item-1", 99))
    val wallTimeMs = (System.nanoTime() - startTime) / 1_000_000

    // Assertions
    assertEquals("txn-123", result.txnId)
    assertEquals("track-456", result.trackingNumber)

    // Virtual time includes the 24h sleep
    assertEquals(Duration.ofDays(1).toMillis(), currentTime)

    // But real wall time is in milliseconds, not hours
    assertTrue(wallTimeMs < 100, "Expected <100ms wall time, got ${wallTimeMs}ms")

    // State store has all checkpoints
    assertEquals(WorkflowStatus.COMPLETED, stateStore.getWorkflow(workflowId)?.status)
    assertEquals(4, stateStore.allStepOutputs().size)  // validate, charge, sleep, ship
}
```

---

## 5. Interface Design for Testability

### 5.1 Repository Pattern for Workflow State Storage

The repository interface should separate concerns cleanly:

```kotlin
/**
 * Core workflow state persistence. Production impl uses PostgreSQL,
 * test impl uses ConcurrentHashMap.
 */
interface WorkflowRepository {
    // Workflow lifecycle
    suspend fun create(record: WorkflowRecord)
    suspend fun findById(id: UUID): WorkflowRecord?
    suspend fun updateStatus(id: UUID, status: WorkflowStatus, output: Any? = null, error: Throwable? = null)
    suspend fun findRecoverable(executorId: String): List<WorkflowRecord>

    // Step checkpointing (the core durability mechanism)
    suspend fun saveStepResult(workflowId: UUID, stepIndex: Int, name: String, result: StepResult)
    suspend fun getStepResult(workflowId: UUID, stepIndex: Int): StepResult?

    // Transaction completion tracking (for piggyback checkpoint)
    suspend fun markTransactionComplete(workflowId: UUID, stepIndex: Int)
    suspend fun isTransactionComplete(workflowId: UUID, stepIndex: Int): Boolean
}

/**
 * Queue operations. Production impl uses SKIP LOCKED,
 * test impl uses in-memory queue with mutex.
 */
interface WorkflowQueue {
    suspend fun enqueue(workflowId: UUID, queueName: String, priority: Int = 0)
    suspend fun dequeue(queueName: String, limit: Int): List<QueuedWorkflow>
    suspend fun acknowledge(workflowId: UUID)
    suspend fun nack(workflowId: UUID, retryAfter: Duration? = null)
}

/**
 * Inter-workflow communication.
 */
interface WorkflowMessaging {
    suspend fun send(destinationId: UUID, topic: String, message: Any?)
    suspend fun receive(workflowId: UUID, topic: String, timeout: Duration): Any?
    suspend fun setEvent(workflowId: UUID, key: String, value: Any?)
    suspend fun getEvent(workflowId: UUID, key: String): Any?
    suspend fun awaitEvent(workflowId: UUID, key: String, timeout: Duration): Any?
}
```

### 5.2 Strategy Pattern for Task Execution

Separate the task execution strategy from the workflow engine:

```kotlin
/**
 * How steps are executed. Production uses the standard coroutine dispatcher.
 * Tests can use a synchronous executor or a controllable one.
 */
interface StepExecutor {
    suspend fun <T> execute(
        workflowId: UUID,
        stepIndex: Int,
        stepName: String,
        retryPolicy: RetryPolicy,
        block: suspend () -> T
    ): T
}

/**
 * Standard implementation: executes steps with retry logic.
 */
class StandardStepExecutor(
    private val repository: WorkflowRepository,
    private val clock: Clock,
) : StepExecutor {
    override suspend fun <T> execute(
        workflowId: UUID,
        stepIndex: Int,
        stepName: String,
        retryPolicy: RetryPolicy,
        block: suspend () -> T
    ): T {
        // Check for cached result (skip-ahead on recovery)
        repository.getStepResult(workflowId, stepIndex)?.let { cached ->
            @Suppress("UNCHECKED_CAST")
            return cached.output as T
        }

        // Execute with retry
        var lastException: Throwable? = null
        for (attempt in 0..retryPolicy.maxRetries) {
            try {
                val result = block()
                repository.saveStepResult(
                    workflowId, stepIndex, stepName,
                    StepResult.Success(result, Instant.now(clock))
                )
                return result
            } catch (e: TerminalError) {
                throw e  // No retries for terminal errors
            } catch (e: Throwable) {
                lastException = e
                if (attempt < retryPolicy.maxRetries) {
                    val backoff = retryPolicy.calculateDelay(attempt)
                    delay(backoff.toMillis())
                }
            }
        }

        throw MaxRetriesExceededException(
            "Step '$stepName' failed after ${retryPolicy.maxRetries + 1} attempts",
            lastException
        )
    }
}
```

### 5.3 Pluggable Scheduler/Poller for Tests

The key architectural decision: in production, the engine polls the database for ready work items. In tests, work items should be executed directly (no polling delay).

```kotlin
/**
 * Scheduler determines how and when workflows are dispatched for execution.
 */
interface WorkflowScheduler {
    /**
     * Submit a workflow for execution.
     */
    suspend fun submit(workflowId: UUID, workflowFn: suspend (WorkflowContext) -> Any?)

    /**
     * Start the scheduler (begins polling/dispatching).
     */
    suspend fun start()

    /**
     * Stop the scheduler gracefully.
     */
    suspend fun stop()
}

/**
 * Production scheduler: polls the database at intervals using SKIP LOCKED.
 */
class PollingScheduler(
    private val repository: WorkflowRepository,
    private val queue: WorkflowQueue,
    private val pollInterval: Duration = Duration.ofSeconds(1),
    private val concurrency: Int = 10,
    private val scope: CoroutineScope,
) : WorkflowScheduler {

    private val semaphore = Semaphore(concurrency)

    override suspend fun submit(workflowId: UUID, workflowFn: suspend (WorkflowContext) -> Any?) {
        queue.enqueue(workflowId, "default")
    }

    override suspend fun start() {
        scope.launch {
            while (isActive) {
                val batch = queue.dequeue("default", concurrency)
                for (item in batch) {
                    semaphore.acquire()
                    launch {
                        try {
                            executeWorkflow(item)
                        } finally {
                            semaphore.release()
                        }
                    }
                }
                delay(pollInterval.toMillis())
            }
        }
    }

    override suspend fun stop() {
        scope.cancel()
    }
}

/**
 * Test scheduler: executes workflows directly and synchronously.
 * No polling, no queue -- just run the function.
 */
class DirectScheduler(
    private val repository: WorkflowRepository,
    private val clock: Clock,
) : WorkflowScheduler {

    override suspend fun submit(workflowId: UUID, workflowFn: suspend (WorkflowContext) -> Any?) {
        val ctx = WorkflowContext(
            workflowId = workflowId,
            repository = repository,
            clock = clock,
        )

        try {
            val result = workflowFn(ctx)
            repository.updateStatus(workflowId, WorkflowStatus.COMPLETED, output = result)
        } catch (e: Throwable) {
            repository.updateStatus(workflowId, WorkflowStatus.ERROR, error = e)
            throw e
        }
    }

    override suspend fun start() { /* No-op for direct execution */ }
    override suspend fun stop() { /* No-op for direct execution */ }
}
```

### 5.4 Complete Test Harness

```kotlin
/**
 * Assembles all in-memory components into a test-ready workflow engine.
 */
class TestWorkflowEngine(
    private val testScope: TestScope,
    val stateStore: InMemoryWorkflowStateStore = InMemoryWorkflowStateStore(),
    val taskQueue: InMemoryTaskQueue<QueuedWorkflow> = InMemoryTaskQueue(),
    val messaging: InMemoryNotificationStore = InMemoryNotificationStore(),
    val clock: Clock = VirtualClock(testScope.testScheduler),
) {
    private val stepExecutor = StandardStepExecutor(stateStore, clock)
    private val scheduler = DirectScheduler(stateStore, clock)

    /**
     * Execute a workflow and return the result.
     * Uses virtual time -- delays complete instantly.
     */
    suspend fun <T> execute(
        workflowFn: suspend (WorkflowContext) -> T,
        input: Any? = null,
        workflowId: UUID = UUID.randomUUID(),
    ): T {
        // Check if workflow already completed (idempotency)
        stateStore.getWorkflow(workflowId)?.let { existing ->
            if (existing.status == WorkflowStatus.COMPLETED) {
                @Suppress("UNCHECKED_CAST")
                return existing.output as T
            }
        }

        // Create workflow record
        stateStore.createWorkflow(WorkflowRecord(
            workflowId = workflowId,
            status = WorkflowStatus.PENDING,
            workflowName = workflowFn.javaClass.simpleName,
            inputs = input,
            createdAt = Instant.now(clock),
            updatedAt = Instant.now(clock),
        ))

        // Build context and execute
        val ctx = WorkflowContext(
            workflowId = workflowId,
            repository = stateStore,
            stepExecutor = stepExecutor,
            messaging = messaging,
            clock = clock,
        )

        val result = workflowFn(ctx)

        stateStore.updateWorkflowStatus(workflowId, WorkflowStatus.COMPLETED)
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    /**
     * Simulate recovery: re-execute a workflow that was previously started.
     * Step results are returned from checkpoints.
     */
    suspend fun <T> recover(
        workflowFn: suspend (WorkflowContext) -> T,
        workflowId: UUID,
    ): T {
        return execute(workflowFn, workflowId = workflowId)
    }

    fun reset() {
        stateStore.reset()
        messaging.reset()
    }
}
```

---

## 6. Recommended Architecture for a Kotlin Engine Test Harness

### 6.1 Summary of Approaches by Engine

| Engine | Test Infrastructure | Time Control | Activity/Step Mocking | Trade-off |
|--------|--------------------|--------------|-----------------------|-----------|
| **Temporal** | In-memory server (GraalVM binary) | Automatic time skipping in server | Mockito on activity interfaces | High fidelity, binary dependency |
| **DBOS** | SQLite (Python) / embedded PG (Java) | `skip_sleep` flag bypasses delays | `unittest.mock.patch` | Simple, real DB still needed |
| **Restate** | Docker container via Testcontainers | None (real time) | Standard mocking on handler deps | Production fidelity, slow startup |
| **Hatchet** | Full engine via Docker Compose | None | Direct function testing | Minimal tooling |

### 6.2 Recommended Design for a Kotlin Engine

Based on the analysis, the recommended test architecture combines the best ideas:

1. **From Temporal**: Automatic virtual time advancement via `kotlinx-coroutines-test`
2. **From DBOS**: In-memory state store with `ConcurrentHashMap`, checkpoint-based recovery testing
3. **From Restate**: State inspection APIs for verifying intermediate workflow state
4. **Novel for Kotlin**: `VirtualClock` that bridges `TestCoroutineScheduler` time with `java.time.Clock` timestamps

```
                    ┌─────────────────────────────────────────┐
                    │           TestWorkflowEngine             │
                    │                                         │
                    │  ┌───────────────────────────────────┐  │
                    │  │    WorkflowRepository (interface)  │  │
                    │  │                                   │  │
                    │  │  Production: PostgresRepository    │  │
                    │  │  Test:       InMemoryRepository    │  │
                    │  └───────────────────────────────────┘  │
                    │                                         │
                    │  ┌───────────────────────────────────┐  │
                    │  │    WorkflowQueue (interface)       │  │
                    │  │                                   │  │
                    │  │  Production: SkipLockedQueue       │  │
                    │  │  Test:       InMemoryTaskQueue     │  │
                    │  └───────────────────────────────────┘  │
                    │                                         │
                    │  ┌───────────────────────────────────┐  │
                    │  │    WorkflowScheduler (interface)   │  │
                    │  │                                   │  │
                    │  │  Production: PollingScheduler      │  │
                    │  │  Test:       DirectScheduler       │  │
                    │  └───────────────────────────────────┘  │
                    │                                         │
                    │  ┌───────────────────────────────────┐  │
                    │  │    Clock (java.time.Clock)         │  │
                    │  │                                   │  │
                    │  │  Production: Clock.systemUTC()     │  │
                    │  │  Test:       VirtualClock          │  │
                    │  └───────────────────────────────────┘  │
                    │                                         │
                    │  ┌───────────────────────────────────┐  │
                    │  │    CoroutineDispatcher             │  │
                    │  │                                   │  │
                    │  │  Production: Dispatchers.Default   │  │
                    │  │  Test:       StandardTestDispatcher│  │
                    │  └───────────────────────────────────┘  │
                    └─────────────────────────────────────────┘
```

### 6.3 Dependency List for Testing

```kotlin
// build.gradle.kts -- test dependencies
dependencies {
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
    testImplementation("io.mockk:mockk:1.13.13")
    testImplementation("org.assertj:assertj-core:3.27.0")

    // Optional: for tests that need a real database
    testImplementation("org.testcontainers:postgresql:1.20.0")
    testImplementation("io.zonky.test:embedded-postgres:2.0.7")
}
```

### 6.4 Testing Pyramid

```
                    ┌──────────────┐
                    │  Integration │  Real PostgreSQL via Testcontainers
                    │   Tests      │  Verify SKIP LOCKED, piggyback checkpoint
                    │  (few)       │  Test real SQL schema and queries
                    ├──────────────┤
                    │  Acceptance  │  In-memory stores + virtual time
                    │   Tests      │  Full workflow execution end-to-end
                    │  (many)      │  DAGs, fan-out/in, retry, recovery
                    ├──────────────┤
                    │  Unit Tests  │  Isolated component tests
                    │  (many)      │  StepExecutor, RetryPolicy, Serialization
                    └──────────────┘
```

The goal is to have the vast majority of tests in the "Acceptance" tier, running entirely in-memory with virtual time, completing in milliseconds. Integration tests with a real database are reserved for verifying PostgreSQL-specific behavior.

---

## Sources

### Temporal
- [TestWorkflowEnvironment Javadoc (temporal-testing 1.32.1)](https://www.javadoc.io/doc/io.temporal/temporal-testing/latest/io/temporal/testing/TestWorkflowEnvironment.html)
- [Temporal Java SDK Testing Documentation](https://docs.temporal.io/develop/java/testing-suite)
- [Temporal Java SDK GitHub](https://github.com/temporalio/sdk-java)
- [Temporal Testing Overview (l-lin)](https://l-lin.github.io/tools/temporal/Temporal-Testing)

### DBOS
- [DBOS Testing Documentation](https://docs.dbos.dev/python/tutorials/testing)
- [How to Test the Reliability of Durable Execution (DBOS Blog)](https://www.dbos.dev/blog/how-to-test-durable-execution)
- [DBOS Java SDK GitHub](https://github.com/dbos-inc/dbos-transact-java)
- [DBOS Python SDK GitHub](https://github.com/dbos-inc/dbos-transact-py)
- [DBOS Java Programming Guide](https://docs.dbos.dev/java/programming-guide)

### Restate
- [Restate Java/Kotlin SDK Testing](https://docs.restate.dev/develop/java/testing)
- [Restate TypeScript Testing](https://docs.restate.dev/develop/ts/testing)
- [RestateTest Annotation Docs](https://docs.restate.dev/ktdocs/sdk-testing/dev.restate.sdk.testing/-restate-test/)
- [Restate SDK-Java GitHub](https://github.com/restatedev/sdk-java)

### Hatchet
- [Hatchet Documentation](https://docs.hatchet.run/home)
- [Hatchet GitHub](https://github.com/hatchet-dev/hatchet)
- [Hatchet V1 SDK Improvements](https://docs.hatchet.run/home/v1-sdk-improvements)

### Kotlin Testing
- [kotlinx-coroutines-test API Reference](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-test/)
- [TestCoroutineScheduler API](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-test/kotlinx.coroutines.test/-test-coroutine-scheduler/)
- [Testing Kotlin Coroutines (Kt. Academy)](https://kt.academy/article/cc-testing)
- [kotlinx-coroutines-test README (GitHub)](https://github.com/Kotlin/kotlinx.coroutines/blob/master/kotlinx-coroutines-test/README.md)
- [Kotest Concurrency Documentation](https://kotest.io/docs/framework/concurrency6.html)
- [How We Test Concurrent Primitives in Kotlin Coroutines (JetBrains)](https://blog.jetbrains.com/kotlin/2021/02/how-we-test-concurrent-primitives-in-kotlin-coroutines/)

### Java Time & Testing
- [Java Clock API (JDK 17)](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/time/Clock.html)
- [Inject Time Dependency Using Java Clock](https://integral-io.github.io/dependency-injection/testing/inject-time-dependency-using-java-clock/)
- [Controlling Time with Java Clock (Mincong Huang)](https://mincong.io/2020/05/24/java-clock/)

### Database Patterns
- [Database Job Queue Using SKIP LOCKED (Vlad Mihalcea)](https://vladmihalcea.com/database-job-queue-skip-locked/)
- [ConcurrentHashMap Guide (Baeldung)](https://www.baeldung.com/java-concurrent-map)
- [Concurrent Queues in Java (Baeldung)](https://www.baeldung.com/java-concurrent-queues)

### Testing Patterns
- [In-Memory Repository based on HashMap for Unit Tests](https://www.ankushchoubey.com/software-blog/in-memory-repositories-unit-test/)
- [Testing Event-Driven Systems (Confluent)](https://www.confluent.io/blog/testing-event-driven-systems/)
- [Retry Pattern (Azure Architecture Center)](https://learn.microsoft.com/en-us/azure/architecture/patterns/retry)
