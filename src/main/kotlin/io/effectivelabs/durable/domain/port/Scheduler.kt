package io.effectivelabs.durable.domain.port

interface Scheduler {
    fun scheduleRepeating(name: String, intervalMs: Long, task: Runnable)
    fun scheduleOnce(name: String, delayMs: Long, task: Runnable)
    fun tick()
    fun shutdown()
}
