package io.effectivelabs.durable.spring

import io.effectivelabs.durable.domain.port.DurableTaskEngine
import org.springframework.context.SmartLifecycle
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Spring lifecycle wrapper for DurableTaskEngine that automatically starts and stops
 * the engine with the Spring application context.
 */
class DurableTaskEngineLifecycle(
    private val engine: DurableTaskEngine,
    private val stopTimeoutSeconds: Long = DEFAULT_STOP_TIMEOUT_SECONDS,
) : SmartLifecycle {

    private val running = AtomicBoolean(false)

    override fun start() {
        if (running.compareAndSet(false, true)) {
            engine.start()
        }
    }

    override fun stop() {
        if (running.compareAndSet(true, false)) {
            engine.stop(Duration.ofSeconds(stopTimeoutSeconds))
        }
    }

    override fun isRunning(): Boolean = running.get()

    // Starts last in startup sequence, stops first in shutdown sequence
    override fun getPhase(): Int = Int.MAX_VALUE

    companion object {
        private const val DEFAULT_STOP_TIMEOUT_SECONDS = 30L
    }
}
