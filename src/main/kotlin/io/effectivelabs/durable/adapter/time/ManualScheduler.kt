package io.effectivelabs.durable.adapter.time

import io.effectivelabs.durable.domain.port.Scheduler
import java.time.Instant
import java.util.PriorityQueue

class ManualScheduler(private val clock: FakeClock) : Scheduler {

    private data class ScheduledTask(
        val name: String,
        val executeAt: Instant,
        val task: Runnable,
        val repeatingIntervalMs: Long?,
    ) : Comparable<ScheduledTask> {
        override fun compareTo(other: ScheduledTask): Int = executeAt.compareTo(other.executeAt)
    }

    private val queue = PriorityQueue<ScheduledTask>()
    private var running = true

    override fun scheduleRepeating(name: String, intervalMs: Long, task: Runnable) {
        queue.add(
            ScheduledTask(
                name = name,
                executeAt = clock.now().plusMillis(intervalMs),
                task = task,
                repeatingIntervalMs = intervalMs,
            )
        )
    }

    override fun scheduleOnce(name: String, delayMs: Long, task: Runnable) {
        queue.add(
            ScheduledTask(
                name = name,
                executeAt = clock.now().plusMillis(delayMs),
                task = task,
                repeatingIntervalMs = null,
            )
        )
    }

    override fun tick() {
        if (!running) return

        val toExecute = mutableListOf<ScheduledTask>()
        while (queue.isNotEmpty() && !queue.peek().executeAt.isAfter(clock.now())) {
            toExecute.add(queue.poll())
        }

        for (scheduled in toExecute) {
            scheduled.task.run()
            if (scheduled.repeatingIntervalMs != null) {
                queue.add(
                    scheduled.copy(
                        executeAt = clock.now().plusMillis(scheduled.repeatingIntervalMs),
                    )
                )
            }
        }
    }

    override fun shutdown() {
        running = false
        queue.clear()
    }
}
