# How Workflow Engines Implement Suspension: A Deep Technical Analysis

> **Purpose**: This document answers a specific question: when you start 1,000 workflows that each call a step which completes at some point in the future, how do workflow engines avoid consuming 1,000 threads/connections/system resources while waiting? The answer varies radically across engines and runtimes.

---

## Table of Contents

1. [The Core Problem](#1-the-core-problem)
2. [Three Suspension Architectures](#2-three-suspension-architectures)
3. [Runtime Suspension Primitives](#3-runtime-suspension-primitives)
4. [Restate: Connection-Level Suspension](#4-restate-connection-level-suspension)
5. [DBOS: Thread-Blocking with Durable Checkpoints](#5-dbos-thread-blocking-with-durable-checkpoints)
6. [Hatchet: No Suspension Needed (DAG Decomposition)](#6-hatchet-no-suspension-needed)
7. [Side-by-Side Comparison](#7-side-by-side-comparison)
8. [Implications for a Kotlin Engine](#8-implications-for-a-kotlin-engine)

---

## 1. The Core Problem

Consider this workflow:

```kotlin
suspend fun processOrder(ctx: WorkflowContext, order: Order): Receipt {
    val validated = ctx.step("validate") { validate(order) }    // 10ms
    val payment = ctx.step("charge") { chargeCard(order) }      // 2 seconds
    ctx.sleep(Duration.ofHours(24))                              // 24 HOURS
    val shipped = ctx.step("ship") { shipOrder(order) }         // 5 seconds
    return Receipt(payment, shipped)
}
```

Now start 1,000 of these. After a few seconds, all 1,000 are sleeping for 24 hours. The question is: **what resources does each sleeping workflow consume?**

| Resource | Worst Case (1 thread/workflow) | Best Case (true suspension) |
|----------|-------------------------------|---------------------------|
| OS threads | 1,000 | 0 |
| Memory (stack) | ~8 GB virtual, ~64 MB resident | ~5-10 MB heap total |
| Kernel structs | 1,000 `task_struct` (~10 KB each) | 0 |
| DB connections | 0 (sleep is local) | 0 |

The difference between "worst case" and "best case" is not theoretical. **Real engines span this entire spectrum.**

---

## 2. Three Suspension Architectures

The frameworks studied use fundamentally different approaches:

```
                    ┌──────────────────────────────────────────┐
                    │           WORKFLOW STARTS                │
                    │    step("validate") completes             │
                    │    step("charge") completes               │
                    │    sleep(24h) reached...                  │
                    └──────────────┬───────────────────────────┘
                                   │
            ┌──────────────────────┼──────────────────────┐
            ▼                      ▼                      ▼
   ┌─────────────────┐  ┌──────────────────┐  ┌─────────────────┐
   │    RESTATE       │  │      DBOS        │  │    HATCHET      │
   │                  │  │                  │  │                 │
   │ SDK tells server │  │ Thread calls     │  │ N/A — workflow  │
   │ "I'm suspending" │  │ time.sleep() or  │  │ is a DAG, not  │
   │                  │  │ setTimeout()     │  │ a function.     │
   │ HTTP/2 stream    │  │                  │  │                 │
   │ CLOSES           │  │ Thread BLOCKED   │  │ Each step is    │
   │                  │  │ (Python/Java)    │  │ dispatched      │
   │ Worker resources │  │ or Promise       │  │ independently.  │
   │ FREED            │  │ PENDING          │  │                 │
   │                  │  │ (TypeScript)     │  │ No thread held  │
   │ Server holds     │  │                  │  │ between steps.  │
   │ state in journal │  │ Checkpoint in PG │  │                 │
   │                  │  │ ensures recovery │  │ State lives in  │
   │ On wake: replay  │  │ On wake: thread  │  │ PostgreSQL.     │
   │ from journal     │  │ resumes normally │  │                 │
   └─────────────────┘  └──────────────────┘  └─────────────────┘
```

---

## 3. Runtime Suspension Primitives

Before examining each engine, we need to understand how the underlying runtimes handle "waiting without blocking."

### 3.1 Python: Threads vs asyncio

**Threads (DBOS Python default):**

When Python calls `time.sleep(86400)`, it executes the `nanosleep()` syscall. The kernel puts the thread into `TASK_INTERRUPTIBLE` state using its internal timer wheel. The GIL is released during sleep, so other threads can run. But the thread's stack memory (~8 MiB virtual, ~64-256 KiB resident) remains allocated.

```python
# What DBOS Python actually does for sleep:
def sleep(self, workflow_id, step_id, seconds):
    end_time = time.time() + seconds
    self.record_operation_result(workflow_id, step_id, end_time)  # checkpoint to PG
    duration = max(0, end_time - time.time())
    time.sleep(duration)  # BLOCKS THE OS THREAD
```

**asyncio (Python coroutines):**

```python
# What asyncio.sleep() actually does (CPython source):
async def sleep(delay):
    loop = events.get_running_loop()
    future = loop.create_future()
    h = loop.call_later(delay, futures._set_result_unless_cancelled, future, True)
    # call_later inserts a TimerHandle into a heapq min-heap
    # The event loop's _run_once() calculates:
    #   poll_timeout = min(nearest_timer - now, max_timeout)
    # Then calls selector.select(poll_timeout) which blocks in epoll_wait/kqueue
    # When timer fires: future.set_result(True) -> schedules task.__step()
    return await future

# Future.__await__ — the magic bridge between await and the event loop:
def __await__(self):
    if not self.done():
        self._asyncio_future_blocking = True
        yield self    # <-- THIS is the actual suspension point
    return self.result()
```

The `yield self` is the critical line. It yields the Future object back up through the coroutine chain to `Task.__step()`, which registers a callback on the Future and returns. The event loop thread is now free to run other tasks. When the timer fires, `Task.__step()` calls `coroutine.send(result)` to resume execution.

**Memory per suspended Python coroutine:** ~2-3 KiB (coroutine frame + Future + TimerHandle).
**Memory per sleeping Python thread:** ~64-256 KiB resident (thread stack + kernel structs).

### 3.2 Kotlin: Coroutines and State Machines

The Kotlin compiler transforms every `suspend` function into a state machine. This is the key:

```kotlin
// Source code:
suspend fun workflow() {
    val a = step1()     // suspension point 0
    val b = step2(a)    // suspension point 1
    return step3(b)     // suspension point 2
}

// What the compiler generates (conceptual):
class WorkflowStateMachine(completion: Continuation<Any?>) : ContinuationImpl(completion) {
    var label = 0
    var a: Any? = null
    var b: Any? = null

    override fun invokeSuspend(result: Result<Any?>): Any? = when (label) {
        0 -> { label = 1; step1(this) }      // this = continuation
        1 -> { a = result.getOrThrow(); label = 2; step2(a, this) }
        2 -> { b = result.getOrThrow(); step3(b, this) }
        else -> error("Invalid state")
    }
}
```

When `step1()` suspends, it returns the sentinel value `COROUTINE_SUSPENDED`. This propagates up the call stack — every caller checks for this value and returns immediately if it sees it. The thread is freed. The state machine object (with `label=1` and all local variables saved as fields) sits on the heap, waiting.

**`delay()` — how it actually works internally:**

```kotlin
// From kotlinx.coroutines Delay.kt:
public suspend fun delay(timeMillis: Long) {
    if (timeMillis <= 0) return
    return suspendCancellableCoroutine { cont: CancellableContinuation<Unit> ->
        cont.context.delay.scheduleResumeAfterDelay(timeMillis, cont)
        // Inserts a DelayedResumeTask into a ThreadSafeHeap (binary min-heap)
        // The dispatcher's event loop checks this heap each iteration
        // When expired: calls cont.resume(Unit) which dispatches the
        // continuation back to the work-stealing thread pool
    }
}
```

The coroutine dispatcher (`Dispatchers.Default`) is a work-stealing thread pool with `N` threads (where N = number of CPU cores). When a coroutine delays, it is **not in any thread's work queue** — it's just a heap object referenced by a timer. When the timer fires, the continuation is added to a worker thread's queue and executed.

**Memory per suspended Kotlin coroutine:** ~5-10 KiB (state machine object + Job + context).
**1,000 sleeping coroutines: ~5-10 MB. 100,000: ~500 MB-1 GB. Feasible.**

### 3.3 TypeScript/Node.js: Event Loop and Timer Heap

Node.js is single-threaded. There is no thread to "block" — suspension is the only option:

```typescript
// What happens when you do:
await new Promise(resolve => setTimeout(resolve, 86400000));

// 1. new Promise(executor) — V8 allocates JSPromise on heap (~80-120 bytes)
//    executor runs synchronously, calling setTimeout

// 2. setTimeout(resolve, ms) — Node.js calls uv_timer_start() in libuv
//    libuv inserts a uv_timer_t into the timer min-heap
//    O(log n) insertion, zero file descriptors created

// 3. await — the async function is compiled to a state machine (like Kotlin)
//    V8 suspends the function, returns to the event loop

// 4. The event loop (libuv):
//    uv__run_timers():
//      while (heap_min(timer_heap) && handle->timeout <= loop->time):
//          fire callback
//    Calculate poll_timeout = min(next_timer - now, ...)
//    uv__io_poll(loop, poll_timeout)  // epoll_wait/kqueue blocks here
//    // When timer fires: resolve() called -> Promise fulfilled
//    // V8 schedules microtask -> async function resumes
```

**Memory per pending Promise + timer:** ~400-700 bytes.
**1,000 sleeping workflows: ~0.4-0.7 MB. Trivial.**

### 3.4 Summary: What "Sleep" Costs Across Runtimes

| Runtime | Mechanism | Resources While Sleeping | 1,000 Concurrent Sleepers |
|---------|-----------|------------------------|--------------------------|
| **Python threads** | `time.sleep()` → kernel `nanosleep()` | 1 OS thread (~64 KiB resident) | ~64 MB, 1000 kernel threads |
| **Python asyncio** | `asyncio.sleep()` → `heapq` timer + `epoll` | 1 Future + 1 TimerHandle (~3 KiB) | ~3 MB, 0 threads |
| **Kotlin coroutines** | `delay()` → `ThreadSafeHeap` + dispatcher | 1 state machine + DelayedTask (~5-10 KiB) | ~5-10 MB, 0 threads |
| **Java virtual threads** | `Thread.sleep()` → unmounted from carrier | 1 virtual thread stack (~5-10 KiB) | ~5-10 MB, 0 OS threads |
| **Java platform threads** | `Thread.sleep()` → kernel `nanosleep()` | 1 OS thread (~30-50 KiB) | ~30-50 MB, 1000 kernel threads |
| **Node.js** | `setTimeout()` → libuv timer heap + `epoll` | 1 Promise + 1 Timeout (~400-700 bytes) | ~0.5 MB, 0 threads |

---

## 4. Restate: Connection-Level Suspension

Restate has the most sophisticated suspension model because the Restate **server** takes ownership of invocation state, allowing the **worker** to completely release all resources.

### 4.1 The Mechanism

When a Restate handler reaches a point where it must wait (a sleep, a service call, an awakeable), the following sequence occurs:

```
     Worker (your code)                    Restate Server
     ──────────────────                    ──────────────
  1. Handler calls ctx.sleep(24h)
  2. SDK writes SleepEntry to journal  →   Server receives SleepEntry
  3. SDK sends "suspension" message    →   Server records suspension
  4. HTTP/2 stream CLOSES              →   Server sets timer internally
  5. Worker memory FREED                   Invocation state: SUSPENDED
                                           (stored in Bifrost log + RocksDB)
     ... 24 hours pass ...

                                      6.  Timer fires in server
                                      7.  Server finds target deployment
  8. NEW HTTP/2 request arrives    ←   8.  Server dispatches replay request
  9. SDK re-executes handler               with full journal
     from the top
 10. For each journaled entry:
     return cached result (no exec)
 11. Reaches un-journaled point
 12. Execution continues normally
```

The critical insight: **between steps 5 and 8, the worker holds ZERO resources for this invocation.** No thread, no memory, no connection. The invocation exists only as state in the Restate server's storage.

### 4.2 Kotlin SDK Implementation

The Restate Kotlin SDK bridges Kotlin coroutines to the Restate protocol:

```kotlin
// From restatedev/sdk-java — ContextImpl.kt (simplified)
// Every operation follows this pattern:

override suspend fun sleep(duration: Duration) {
    // handlerContext.timer() returns a CompletableFuture
    // .await() is from kotlinx.coroutines.future — it suspends the coroutine
    // without blocking a thread
    SingleDurableFutureImpl(handlerContext.timer(duration).await())
        .await()
}

override suspend fun <T : Any> get(key: StateKey<T>): T? =
    resolveSerde<T?>(key.serdeInfo()).let { serde ->
        SingleDurableFutureImpl(handlerContext.get(key.name()).await())
            .simpleMap { it.getOrNull()?.let { serde.deserialize(it) } }
    }.await()
```

The pattern: Java `HandlerContext` returns `CompletableFuture`. The `.await()` extension (from `kotlinx.coroutines.future`) converts these to Kotlin coroutine suspension points. When the future hasn't completed yet, the coroutine suspends, the thread is released back to the Vert.x event loop.

Under the hood, `HandlerContext` is a **state machine** that interacts with the Restate server via HTTP/2 bidirectional streaming. When the SDK determines that no more progress can be made without external input:

1. It sends a `SuspensionMessage` listing the journal entry indices it's waiting on
2. The server acknowledges and the HTTP/2 stream closes
3. The worker's Kotlin coroutine is cancelled (no leak)

```kotlin
// From HandlerRunner.kt — how the handler lifecycle is managed:
class HandlerRunner<CTX, REQ, RES> {
    fun run(ctx: HandlerContext, cancelSignal: AtomicReference<Runnable>): CompletableFuture<ByteArray> {
        val scope = CoroutineScope(
            coroutineContext + RestateContextElement(ctx) + otelContext
        )
        val job = scope.launch {
            val result = handler(ctx as CTX, request)  // suspend function
            completableFuture.complete(serialize(result))
        }
        // Cancellation hook — when invocation stream closes:
        cancelSignal.set(Runnable { job.cancel() })
        return completableFuture
    }
}
```

### 4.3 TypeScript SDK Implementation

```typescript
// Restate TypeScript SDK — the handler execution model:

// When your handler does:
const result = await ctx.run("step1", async () => {
    return await fetch("https://api.example.com/data");
});

// Internally, ctx.run():
// 1. Checks the journal — if entry exists, return cached result
// 2. If not: execute the closure
// 3. Write result to journal (send to server via HTTP/2 stream)
// 4. Return result

// When your handler does:
await ctx.sleep(Duration.ofHours(24));

// Internally, ctx.sleep():
// 1. Checks journal — if sleep entry exists AND completed, return immediately
// 2. If not: write SleepEntry to journal
// 3. The SDK sees it cannot make progress
// 4. Sends SuspensionMessage to server
// 5. The Promise returned by the handler NEVER resolves on this attempt
// 6. The HTTP connection closes
// 7. Node.js garbage-collects all handler state

// 24 hours later: the server sends a NEW request with the full journal
// The handler re-executes, replays all cached entries, and continues
```

### 4.4 Resource Cost of 1,000 Suspended Restate Invocations

On the **worker side**: literally zero. No threads, no memory, no timers. The handler function does not exist in memory.

On the **Restate server side**: each suspended invocation is a row in `sys_invocation` (status=`suspended`) plus its journal entries in `sys_journal`. This is just database storage — RocksDB key-value pairs totaling perhaps 1-10 KiB per invocation depending on journal size.

---

## 5. DBOS: Thread-Blocking with Durable Checkpoints

DBOS takes a fundamentally different approach. Workflows are ordinary functions running in your process. The "durability" comes from checkpointing to PostgreSQL, not from sophisticated thread management.

### 5.1 Python SDK: `time.sleep()` Blocks the Thread

```python
# The actual DBOS Python sleep implementation (from _sys_db.py):

def sleep(self, workflow_id, function_id, seconds):
    # Step 1: Check if this sleep was already recorded (recovery path)
    cached = self.check_operation_execution(workflow_id, function_id)
    if cached is not None:
        end_time = cached
        duration = max(0, end_time - time.time())  # only sleep remaining time
    else:
        # Step 2: Record the wake-up time in PostgreSQL
        end_time = time.time() + seconds
        self.record_operation_result(workflow_id, function_id, end_time)
        duration = seconds

    # Step 3: BLOCK THE THREAD
    if not self.skip_sleep:
        time.sleep(duration)  # <-- This is it. A real OS thread is blocked.
```

**What happens with 1,000 sleeping workflows:**

DBOS's Python thread pool defaults to `sys.maxsize` (effectively unlimited):

```python
# From _dbos.py:
max_executor_threads = (
    self._config.get("runtimeConfig", {}).get("max_executor_threads")
    or sys.maxsize
)
self._executor_field = ThreadPoolExecutor(max_workers=max_executor_threads)
```

So 1,000 sleeping workflows = 1,000 OS threads, each consuming ~64-256 KiB of resident memory, all blocked in `nanosleep()`. The GIL is released during sleep so there's no CPU contention, but the memory cost is real.

### 5.2 TypeScript SDK: `setTimeout()` — The Efficient Path

```typescript
// DBOS TypeScript sleep (from system_database.ts):

async durableSleepms(workflowID, functionID, durationMS) {
    // Record wake-up time in PostgreSQL
    await this.recordOperationResult(workflowID, functionID, endTimeMs);

    // Use cancellable setTimeout — does NOT block the event loop
    const { promise, cancel: timeoutCancel } = cancellableSleep(remainingDuration);

    // Race between cancellation and the sleep timeout
    await Promise.race([cancelPromise, promise]);
}

// cancellableSleep implementation (from utils.ts):
function cancellableSleep(ms: number) {
    let timeoutId;
    const promise = new Promise<void>((resolve) => {
        timeoutId = setTimeout(() => resolve(), ms);
    });
    const cancel = () => { clearTimeout(timeoutId); };
    return { promise, cancel };
}
```

1,000 sleeping workflows = 1,000 `setTimeout` handles in libuv's timer heap. Cost: ~0.5 MB total. The event loop is completely free.

### 5.3 Java SDK: `Thread.sleep()` — Saved by Virtual Threads

```java
// DBOS Java sleep (from StepsDAO.java, simplified):

public static void sleep(Connection conn, String workflowID, int functionID, Duration duration) {
    long endTime = System.currentTimeMillis() + duration.toMillis();

    // Check if already recorded (recovery path)
    Long cachedEndTime = checkOperationExecution(conn, workflowID, functionID);
    if (cachedEndTime != null) {
        endTime = cachedEndTime;
    } else {
        recordOperationResult(conn, workflowID, functionID, endTime);
    }

    long remaining = Math.max(0, endTime - System.currentTimeMillis());
    Thread.sleep(remaining);  // blocks the current thread
}
```

The Java SDK uses virtual threads (Java 21+) by default:

```java
// From DBOSExecutor.java:
// Virtual thread executor when available (Java 21+)
// Fallback: fixed pool of Runtime.getRuntime().availableProcessors() * 50 threads
```

With virtual threads: `Thread.sleep()` **unmounts** the virtual thread from its carrier thread. The carrier thread (OS thread) is freed to run other virtual threads. 1,000 sleeping workflows = 1,000 virtual threads (heap objects, ~5-10 KiB each), consuming ~0 OS threads.

Without virtual threads (Java < 21): 1,000 sleeping workflows = 1,000 blocked OS threads. On an 8-core machine with the default pool of 400 threads (`8 * 50`), the 401st workflow would queue.

### 5.4 The `recv()` Pattern — Waiting for Messages

DBOS workflows can wait for inter-workflow messages:

```python
# Python — blocks a thread on a Condition variable:
def recv(self, workflow_id, topic, timeout):
    condition = threading.Condition()
    self.notifications_map.set((workflow_id, topic), condition)
    condition.acquire()
    condition.wait(timeout=timeout)  # BLOCKS the thread
    # Woken by: notification_listener (PG LISTEN/NOTIFY or 1s polling)
```

```typescript
// TypeScript — non-blocking Promise.race:
async recv(workflowID, topic, timeoutSeconds) {
    while (true) {
        const messagePromise = new Promise(resolve => {
            this.notificationsMap.registerCallback(payload, resolve);
        });
        const rows = await this.pool.query(
            'SELECT message FROM notifications WHERE destination_uuid=$1 AND topic=$2',
            [workflowID, topic]
        );
        if (rows.length > 0) break;
        // Non-blocking wait with timeout
        const { promise: timeout } = cancellableSleep(10_000); // 10s poll
        await Promise.race([messagePromise, timeout]);
    }
}
```

All DBOS SDKs use PostgreSQL `LISTEN/NOTIFY` for low-latency notification delivery, with polling as a fallback (1s in Python/Java, 10s in TypeScript).

### 5.5 The Recovery Model — Why Checkpointing Matters

The thread blocking isn't a bug — it's a deliberate simplicity tradeoff. DBOS's value isn't efficient suspension; it's **crash recovery**:

```
Normal execution:                    After crash + restart:
─────────────────                    ─────────────────────
1. Start workflow                    1. Query: SELECT * FROM workflow_status
2. step("validate") → run,              WHERE status = 'PENDING'
   write result to PG               2. For each pending workflow:
3. step("charge") → run,                Re-invoke with stored inputs
   write result to PG               3. step("validate") → found in PG,
4. sleep(24h) → write end_time          return cached result (NO re-execution)
   to PG, then time.sleep()         4. step("charge") → found in PG,
5. step("ship") → run,                  return cached result
   write result to PG               5. sleep(24h) → found in PG,
                                        calculate remaining: 23h 14m
                                        time.sleep(remaining)
                                     6. step("ship") → NOT in PG,
                                        execute normally
```

### 5.6 How DBOS Mitigates Thread Exhaustion

Rather than sophisticated suspension, DBOS uses **queue concurrency limits**:

```python
queue = Queue(
    "order_processing",
    concurrency=10,              # Max 10 running globally
    worker_concurrency=5,        # Max 5 per process
)
```

This caps how many workflows execute simultaneously, preventing thread exhaustion at the cost of queueing excess workflows.

---

## 6. Hatchet: No Suspension Needed

Hatchet sidesteps the suspension problem entirely through its DAG architecture.

### 6.1 Workers Are Stateless Task Executors

A Hatchet workflow is not a function that runs for the workflow's lifetime. It's a **DAG declaration** that the engine evaluates:

```python
@hatchet.workflow()
class OrderWorkflow:
    @hatchet.task()
    def validate(self, input, ctx):
        return {"valid": True}

    @hatchet.task(parents=["validate"])
    def charge(self, input, ctx):
        result = ctx.task_output("validate")
        return {"charged": True}

    @hatchet.task(parents=["charge"])
    def ship(self, input, ctx):
        result = ctx.task_output("charge")
        return {"shipped": True}
```

The worker never sees "the workflow." It receives individual tasks:

```
1. Engine dispatches "validate" to Worker A via gRPC
2. Worker A executes validate(), returns result, FREES THE SLOT
3. Engine stores result in PostgreSQL
4. Engine checks DAG: "charge" parents satisfied → dispatch
5. Engine dispatches "charge" to Worker B via gRPC (could be ANY worker)
6. Worker B executes, returns result, FREES THE SLOT
7. ... repeat for "ship"
```

**Between steps, no worker holds any workflow state.** The workflow "lives" as rows in PostgreSQL (`v1_task`, `v1_dag`, `v1_payload`).

### 6.2 gRPC Push-Based Dispatch

Workers connect to the engine via bidirectional gRPC streams:

```python
# Worker SDK (simplified from runner.py):
class Runner:
    def __init__(self, slots=10):
        self.thread_pool = ThreadPoolExecutor(max_workers=slots)
        self.tasks = {}  # active task tracking

    async def handle_action(self, action):
        if action.type == START_STEP_RUN:
            func = self.action_registry[action.action_id]
            context = Context(action)
            # Execute in thread pool
            task = asyncio.create_task(
                asyncio.get_event_loop().run_in_executor(
                    self.thread_pool, func, context
                )
            )
            self.tasks[action.task_id] = task

        # On completion: send result back to engine via gRPC
        # StepActionEvent(type=STEP_EVENT_TYPE_COMPLETED, payload=result)
```

The engine pushes tasks to workers. Workers execute them and return results. Workers have a fixed number of "slots" — when all slots are busy, the engine queues tasks server-side.

### 6.3 Durable Sleep in Hatchet

For sleep, Hatchet tracks the timer server-side in PostgreSQL:

```python
# Durable task (procedural orchestration):
@workflow.durable_task()
async def orchestrator(input, ctx: DurableContext):
    result_a = await task_a.aio_run(input)            # Blocks on gRPC stream
    await ctx.aio_sleep_for(timedelta(hours=24))      # Sleep registered in PG
    result_b = await task_b.aio_run(result_a)          # Blocks on gRPC stream
    return result_b
```

When `aio_sleep_for()` is called:

1. The `DurableContext` sends a `SleepCondition` to the engine via `RegisterDurableEvent` gRPC
2. The engine stores the timer in PostgreSQL
3. The worker thread blocks on a `ListenForDurableEvent` gRPC stream
4. Server-side, `process_sleeps.go` runs periodically, checking for expired timers
5. When the timer expires, the engine signals the gRPC stream, and the worker resumes

**Critical detail:** Durable tasks run on a **separate slot pool** (`durable_slots`), not the main task slots. This prevents sleeping orchestrators from starving actual work. For DAG-based workflows, sleep isn't even needed between tasks — the engine handles timing.

### 6.4 Resource Cost of 1,000 In-Flight Hatchet Workflows

**With DAG workflows (recommended):**
- At any given moment, only the currently-runnable tasks consume worker slots
- If 1,000 workflows are all between steps, the cost is **0 worker resources** — just rows in PostgreSQL
- When a step becomes ready, it's dispatched to any available worker slot

**With durable orchestrator tasks:**
- Each sleeping orchestrator holds a durable slot (thread blocked on gRPC stream)
- With `durable_slots=100`, only 100 orchestrators run simultaneously; the rest queue
- Each blocked thread consumes minimal CPU but does consume stack memory

---

## 7. Side-by-Side Comparison

### 7.1 What Resources Does a Sleeping Workflow Consume?

| Engine | Runtime | Sleeping Mechanism | Worker Resources | Server/DB Resources |
|--------|---------|-------------------|-----------------|-------------------|
| **Restate** | Kotlin | Server-side timer, SDK suspends | **Zero** — HTTP/2 stream closed, coroutine GC'd | ~1-10 KiB in RocksDB (journal entries) |
| **Restate** | TypeScript | Server-side timer, SDK suspends | **Zero** — connection closed, handler GC'd | ~1-10 KiB in RocksDB |
| **DBOS** | TypeScript | `setTimeout()` in Node.js | ~400-700 bytes (Promise + timer) | ~1 KiB PG row (checkpoint) |
| **DBOS** | Java 21+ | `Thread.sleep()` on virtual thread | ~5-10 KiB (virtual thread stack) | ~1 KiB PG row |
| **DBOS** | Python | `time.sleep()` on OS thread | **~64-256 KiB** (OS thread) | ~1 KiB PG row |
| **DBOS** | Java <21 | `Thread.sleep()` on platform thread | **~30-50 KiB** (OS thread) | ~1 KiB PG row |
| **Hatchet** | DAG mode | N/A — no workflow function running | **Zero** — workflow is just PG rows | ~5-20 KiB in PG (task + DAG rows) |
| **Hatchet** | Durable mode | gRPC stream wait | ~1 thread blocked on gRPC | ~5-20 KiB in PG + server timer |

### 7.2 The 1,000 Workflow Benchmark

| Engine + Runtime | Worker Memory | Worker Threads Consumed | Can Handle It? |
|-----------------|--------------|------------------------|----------------|
| Restate (any SDK) | 0 | 0 | Trivially |
| DBOS TypeScript | ~0.5 MB | 0 | Trivially |
| DBOS Java 21+ | ~5-10 MB | 0 OS threads | Easily |
| DBOS Python | ~64-256 MB | 1,000 OS threads | Yes, but strained |
| DBOS Java <21 | ~30-50 MB | 1,000 OS threads | Needs pool ≥ 1,000 |
| Hatchet DAGs | 0 | 0 | Trivially |
| Hatchet Durable | ~64-256 MB | Up to `durable_slots` | Limited by config |

### 7.3 How Each Engine Handles Recovery After Crash

| Engine | What Happens | Sleep Resumption |
|--------|-------------|-----------------|
| **Restate** | Server still has the timer; re-dispatches to any available worker when timer fires. Handler replays from journal. | Exact remaining time (server tracks it) |
| **DBOS** | On restart, queries `workflow_status` for `PENDING` rows. Re-invokes each workflow. At each step, returns cached result from `operation_outputs`. | Remaining time = `stored_end_time - now()` |
| **Hatchet** | Engine re-dispatches tasks to any available worker. For durable tasks, replays the durable event log. | Server-side timer still running in PG; fires naturally |

---

## 8. Implications for a Kotlin Engine

### 8.1 Kotlin Coroutines Are the Ideal Primitive

Kotlin coroutines provide a natural mapping to durable workflow suspension:

```kotlin
// A durable step using Kotlin's native suspension:
suspend fun <T> WorkflowContext.step(name: String, block: suspend () -> T): T {
    // Check if this step was already checkpointed
    val cached = journal.lookup(workflowId, stepIndex)
    if (cached != null) {
        stepIndex++
        return deserialize(cached)  // skip-ahead (DBOS model)
    }

    // Execute the step
    val result = block()

    // Checkpoint to PostgreSQL
    journal.record(workflowId, stepIndex, serialize(result))
    stepIndex++
    return result
}

// A durable sleep — truly suspends the coroutine, no thread consumed:
suspend fun WorkflowContext.sleep(duration: Duration) {
    step("sleep") {
        val endTime = Instant.now() + duration
        endTime  // checkpoint the wake-up time
    }
    // Now delay — this is the Kotlin coroutine delay, not Thread.sleep
    val endTime = /* read from checkpoint */
    val remaining = Duration.between(Instant.now(), endTime)
    if (remaining > Duration.ZERO) {
        delay(remaining.toMillis())  // suspends coroutine, frees thread
    }
}
```

With this design, 1,000 sleeping workflows:
- Consume ~5-10 MB of heap (1,000 state machine objects)
- Consume 0 OS threads
- Have their wake-up times checkpointed in PostgreSQL for crash recovery
- On recovery: re-execute workflow, skip-ahead through cached steps, `delay()` for remaining sleep time

### 8.2 The Restate Model (Full Suspension) vs DBOS Model (In-Process)

For a Kotlin library-embedded engine (no external server), you can't do Restate-style full suspension (there's no server to hold the timer). But Kotlin coroutines give you something almost as good:

| Aspect | Restate (full suspension) | Kotlin Coroutine (in-process) |
|--------|--------------------------|-------------------------------|
| Worker memory while sleeping | 0 | ~5-10 KiB per workflow |
| Worker threads while sleeping | 0 | 0 |
| Recovery after crash | Automatic (server has timer) | Re-execute + skip-ahead |
| Timer accuracy after crash | Exact (server-side) | Exact (checkpoint has end time) |
| Maximum sleeping workflows | Unbounded (server-limited) | ~100K-1M per process (heap-limited) |

The difference is marginal. For a library approach, Kotlin coroutines are the right choice.

### 8.3 The Key Design Decision: Don't Use `Thread.sleep()`

The DBOS Python/Java lesson is clear: if your `sleep()` implementation calls `Thread.sleep()` or `time.sleep()`, you're consuming an OS resource per sleeping workflow. In Kotlin:

```kotlin
// WRONG — blocks an OS thread:
fun sleep(duration: Duration) {
    Thread.sleep(duration.toMillis())
}

// WRONG — blocks a Dispatchers.IO thread:
suspend fun sleep(duration: Duration) = withContext(Dispatchers.IO) {
    Thread.sleep(duration.toMillis())
}

// RIGHT — suspends the coroutine, frees the thread:
suspend fun sleep(duration: Duration) {
    delay(duration.toMillis())
}
```

The same applies to `recv()` — use `suspendCancellableCoroutine` with a callback, not `Condition.await()`.

### 8.4 Recommended Architecture

```
                    Workflow Coroutine
                    ─────────────────
                    step("validate") ──→ checkpoint to PG, execute
                    step("charge")   ──→ checkpoint to PG, execute
                    sleep(24h)       ──→ checkpoint endTime to PG
                                         delay(remaining) ← coroutine suspends
                                         thread freed to thread pool
                                         state machine lives on heap

                    ... 24 hours ...

                    DelayedResumeTask fires
                    coroutine dispatched to thread pool
                    step("ship")     ──→ checkpoint to PG, execute
                    workflow complete ──→ update status in PG

Recovery:
    SELECT * FROM workflow_status WHERE status = 'PENDING'
    For each: re-invoke workflow function
              step("validate") → return cached (no exec)
              step("charge")   → return cached (no exec)
              sleep(24h)       → read endTime, delay(remaining)
              step("ship")     → execute normally
```

This gives you:
- **DBOS's simplicity**: library-embedded, PostgreSQL-only, checkpoint skip-ahead
- **Restate's efficiency**: near-zero resource consumption for sleeping workflows
- **Kotlin-native ergonomics**: `suspend fun`, structured concurrency, cancellation

---

## Sources

### Restate
- [Restate Architecture](https://docs.restate.dev/references/architecture)
- [Restate Kotlin SDK — Context API](https://restatedev.github.io/sdk-java/ktdocs/sdk-api-kotlin/dev.restate.sdk.kotlin/-context/)
- [Restate SDK-Java Source (ContextImpl.kt)](https://github.com/restatedev/sdk-java/blob/main/sdk-api-kotlin/src/main/kotlin/dev/restate/sdk/kotlin/ContextImpl.kt)
- [Restate SDK-Java Source (HandlerRunner.kt)](https://github.com/restatedev/sdk-java/blob/main/sdk-api-kotlin/src/main/kotlin/dev/restate/sdk/kotlin/HandlerRunner.kt)
- [Restate SDK-Java Source (futures.kt)](https://github.com/restatedev/sdk-java/blob/main/sdk-api-kotlin/src/main/kotlin/dev/restate/sdk/kotlin/futures.kt)
- [Building a Durable Execution Engine from First Principles](https://www.restate.dev/blog/building-a-modern-durable-execution-engine-from-first-principles)

### DBOS
- [DBOS Python SDK Source (_sys_db.py)](https://github.com/dbos-inc/dbos-transact-py/blob/main/dbos/_sys_db.py)
- [DBOS Python SDK Source (_dbos.py)](https://github.com/dbos-inc/dbos-transact-py/blob/main/dbos/_dbos.py)
- [DBOS TypeScript SDK Source (system_database.ts)](https://github.com/dbos-inc/dbos-transact-ts/blob/main/src/system_database.ts)
- [DBOS Java SDK Source](https://github.com/dbos-inc/dbos-transact-java)
- [DBOS Workflow Communication](https://docs.dbos.dev/python/tutorials/workflow-communication)
- [Why Postgres for Durable Execution](https://www.dbos.dev/blog/why-postgres-durable-execution)

### Hatchet
- [Hatchet Architecture](https://docs.hatchet.run/home/architecture)
- [Hatchet Durable Execution](https://docs.hatchet.run/home/durable-execution)
- [Hatchet Durable Sleep](https://docs.hatchet.run/home/durable-sleep)
- [Hatchet DAG Workflows](https://docs.hatchet.run/home/dags)
- [Hatchet Workers](https://docs.hatchet.run/home/workers)
- [Hatchet GitHub (Python SDK runner.py)](https://github.com/hatchet-dev/hatchet/blob/main/sdks/python/hatchet_sdk/worker/runner/runner.py)

### Runtime Internals
- [Python behind the scenes #12: how async/await works](https://tenthousandmeters.com/blog/python-behind-the-scenes-12-how-asyncawait-works-in-python/)
- [CPython asyncio futures.py source](https://github.com/python/cpython/blob/main/Lib/asyncio/futures.py)
- [CPython asyncio tasks.py source](https://github.com/python/cpython/blob/main/Lib/asyncio/tasks.py)
- [Kotlin Coroutines: How does suspension work?](https://kt.academy/article/cc-suspension)
- [Kotlin Coroutines: Dispatchers](https://kt.academy/article/cc-dispatchers)
- [Kotlin KEEP Coroutines Proposal](https://github.com/Kotlin/KEEP/blob/master/proposals/coroutines.md)
- [kotlinx.coroutines Delay.kt source](https://github.com/Kotlin/kotlinx.coroutines/blob/master/kotlinx-coroutines-core/common/src/Delay.kt)
- [Suspending functions, coroutines and state machines (Pedro Felix)](https://labs.pedrofelix.org/guides/kotlin/coroutines/coroutines-and-state-machines)
- [Inside Kotlin Coroutines: State Machines and Structured Concurrency](https://proandroiddev.com/inside-kotlin-coroutines-state-machines-continuations-and-structured-concurrency-b8d3d4e48e62)
- [Kotlin Coroutines vs Threads Memory Benchmark](https://www.techyourchance.com/kotlin-coroutines-vs-threads-memory-benchmark/)
- [Node.js Event Loop, Timers, and process.nextTick()](https://nodejs.org/en/learn/asynchronous-work/event-loop-timers-and-nexttick)
- [libuv Design Overview](https://docs.libuv.org/en/v1.x/design.html)
- [libuv timer.c source](https://github.com/libuv/libuv/blob/v1.x/src/timer.c)
- [V8: Faster async functions and promises](https://v8.dev/blog/fast-async)
- [Your Node is Leaking Memory? setTimeout Could be the Reason](https://lucumr.pocoo.org/2024/6/5/node-timeout/)
