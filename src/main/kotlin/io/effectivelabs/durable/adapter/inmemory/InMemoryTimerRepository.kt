package io.effectivelabs.durable.adapter.inmemory

import io.effectivelabs.durable.domain.model.TimerRecord
import io.effectivelabs.durable.domain.port.TimerRepository
import java.time.Instant

class InMemoryTimerRepository : TimerRepository {

    private val timers = mutableListOf<TimerRecord>()

    @Synchronized
    override fun create(record: TimerRecord) {
        timers.add(record)
    }

    @Synchronized
    override fun findExpired(now: Instant, limit: Int): List<TimerRecord> =
        timers.filter { !it.fired && !it.wakeAt.isAfter(now) }.take(limit)

    @Synchronized
    override fun markFired(record: TimerRecord) {
        val index = timers.indexOfFirst {
            it.workflowRunId == record.workflowRunId && it.taskName == record.taskName
        }
        if (index >= 0) {
            timers[index] = timers[index].copy(fired = true)
        }
    }
}
