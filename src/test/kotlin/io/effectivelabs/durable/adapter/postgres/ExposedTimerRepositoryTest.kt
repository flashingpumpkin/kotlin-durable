package io.effectivelabs.durable.adapter.postgres

import io.effectivelabs.durable.domain.model.TimerRecord
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExposedTimerRepositoryTest : PostgresTestBase() {

    private val repo = ExposedTimerRepository()

    @BeforeTest
    fun setUp() {
        cleanTables()
    }

    private fun timerRecord(
        wakeAt: Instant = Instant.now().minus(1, ChronoUnit.HOURS),
        fired: Boolean = false,
    ) = TimerRecord(
        workflowRunId = UUID.randomUUID(),
        taskName = "sleep-task",
        tenantId = "tenant-1",
        wakeAt = wakeAt,
        fired = fired,
    )

    @Test
    fun `create and findExpired returns expired unfired timers`() {
        val timer = timerRecord(wakeAt = Instant.now().minus(1, ChronoUnit.HOURS))
        repo.create(timer)

        val expired = repo.findExpired(Instant.now())
        assertEquals(1, expired.size)
        assertEquals(timer.taskName, expired[0].taskName)
    }

    @Test
    fun `findExpired does not return future timers`() {
        val timer = timerRecord(wakeAt = Instant.now().plus(1, ChronoUnit.HOURS))
        repo.create(timer)

        val expired = repo.findExpired(Instant.now())
        assertTrue(expired.isEmpty())
    }

    @Test
    fun `findExpired does not return fired timers`() {
        val timer = timerRecord(wakeAt = Instant.now().minus(1, ChronoUnit.HOURS))
        repo.create(timer)
        repo.markFired(timer)

        val expired = repo.findExpired(Instant.now())
        assertTrue(expired.isEmpty())
    }

    @Test
    fun `markFired prevents timer from being found as expired`() {
        val timer = timerRecord()
        repo.create(timer)

        repo.markFired(timer)

        val expired = repo.findExpired(Instant.now())
        assertTrue(expired.isEmpty())
    }

    @Test
    fun `findExpired respects limit`() {
        repeat(5) {
            repo.create(
                TimerRecord(
                    workflowRunId = UUID.randomUUID(),
                    taskName = "task-$it",
                    tenantId = "tenant-1",
                    wakeAt = Instant.now().minus(1, ChronoUnit.HOURS),
                    fired = false,
                )
            )
        }

        val expired = repo.findExpired(Instant.now(), limit = 3)
        assertEquals(3, expired.size)
    }
}
