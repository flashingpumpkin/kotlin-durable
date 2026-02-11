package io.effectivelabs.durable.domain.port

import java.time.Duration

interface DurableTaskEngine {
    fun <TInput> workflow(
        name: String,
        block: WorkflowBuilder<TInput>.() -> Unit,
    ): Workflow<TInput>

    fun start()
    fun stop(timeout: Duration = Duration.ofSeconds(30))
}
