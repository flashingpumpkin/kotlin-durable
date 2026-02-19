package io.effectivelabs.durable.spring

import io.effectivelabs.durable.domain.port.DurableTaskEngine
import org.springframework.context.SmartLifecycle
import java.time.Duration

/**
 * Spring lifecycle wrapper for DurableTaskEngine that automatically starts and stops
 * the engine with the Spring application context.
 */
class DurableTaskEngineLifecycle(
    private val engine: DurableTaskEngine,
) : SmartLifecycle {

    @Volatile
    private var running = false

    override fun start() {
        if (!running) {
            engine.start()
            running = true
        }
    }

    override fun stop() {
        if (running) {
            engine.stop(Duration.ofSeconds(30))
            running = false
        }
    }

    override fun isRunning(): Boolean = running

    override fun getPhase(): Int = Int.MAX_VALUE // Start last, stop first
}
