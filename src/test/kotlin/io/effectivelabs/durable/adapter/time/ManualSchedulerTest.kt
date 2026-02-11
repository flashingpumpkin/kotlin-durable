package io.effectivelabs.durable.adapter.time

import kotlin.test.Test
import kotlin.test.assertEquals
import java.time.Duration

class ManualSchedulerTest {

    @Test
    fun `scheduled task does not execute before its time`() {
        val clock = FakeClock()
        val scheduler = ManualScheduler(clock)
        var executed = false

        scheduler.scheduleOnce("test", delayMs = 200) { executed = true }

        clock.advance(Duration.ofMillis(100))
        scheduler.tick()

        assertEquals(false, executed)
    }

    @Test
    fun `scheduled task executes when time is reached`() {
        val clock = FakeClock()
        val scheduler = ManualScheduler(clock)
        var executed = false

        scheduler.scheduleOnce("test", delayMs = 200) { executed = true }

        clock.advance(Duration.ofMillis(200))
        scheduler.tick()

        assertEquals(true, executed)
    }

    @Test
    fun `scheduled task executes when time is exceeded`() {
        val clock = FakeClock()
        val scheduler = ManualScheduler(clock)
        var executed = false

        scheduler.scheduleOnce("test", delayMs = 200) { executed = true }

        clock.advance(Duration.ofMillis(500))
        scheduler.tick()

        assertEquals(true, executed)
    }

    @Test
    fun `repeating task re-schedules after execution`() {
        val clock = FakeClock()
        val scheduler = ManualScheduler(clock)
        var count = 0

        scheduler.scheduleRepeating("test", intervalMs = 100) { count++ }

        clock.advance(Duration.ofMillis(100))
        scheduler.tick()
        assertEquals(1, count)

        clock.advance(Duration.ofMillis(100))
        scheduler.tick()
        assertEquals(2, count)

        clock.advance(Duration.ofMillis(100))
        scheduler.tick()
        assertEquals(3, count)
    }

    @Test
    fun `tick with nothing due does nothing`() {
        val clock = FakeClock()
        val scheduler = ManualScheduler(clock)
        var executed = false

        scheduler.scheduleOnce("test", delayMs = 1000) { executed = true }
        scheduler.tick()

        assertEquals(false, executed)
    }

    @Test
    fun `shutdown prevents further execution`() {
        val clock = FakeClock()
        val scheduler = ManualScheduler(clock)
        var executed = false

        scheduler.scheduleOnce("test", delayMs = 100) { executed = true }
        scheduler.shutdown()

        clock.advance(Duration.ofMillis(200))
        scheduler.tick()

        assertEquals(false, executed)
    }

    @Test
    fun `multiple tasks execute in order when all due`() {
        val clock = FakeClock()
        val scheduler = ManualScheduler(clock)
        val order = mutableListOf<String>()

        scheduler.scheduleOnce("first", delayMs = 100) { order.add("first") }
        scheduler.scheduleOnce("second", delayMs = 200) { order.add("second") }

        clock.advance(Duration.ofMillis(300))
        scheduler.tick()

        assertEquals(listOf("first", "second"), order)
    }
}
