package io.effectivelabs.durable.adapter.postgres

import io.effectivelabs.durable.domain.port.Scheduler
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class RealScheduler : Scheduler {

    private val executor: ScheduledExecutorService = Executors.newScheduledThreadPool(2)

    override fun scheduleRepeating(name: String, intervalMs: Long, task: Runnable) {
        executor.scheduleAtFixedRate(task, 0, intervalMs, TimeUnit.MILLISECONDS)
    }

    override fun scheduleOnce(name: String, delayMs: Long, task: Runnable) {
        executor.schedule(task, delayMs, TimeUnit.MILLISECONDS)
    }

    override fun tick() {
        // No-op for real scheduler — tasks run on their own threads
    }

    override fun shutdown() {
        executor.shutdown()
        executor.awaitTermination(5, TimeUnit.SECONDS)
    }
}
