package io.effectivelabs.durable.application

import io.effectivelabs.durable.domain.port.ReadyQueueRepository

class TaskPoller(
    private val readyQueueRepository: ReadyQueueRepository,
    private val taskExecutor: TaskExecutor,
    private val batchSize: Int = 10,
) : Runnable {

    override fun run() {
        val claimed = readyQueueRepository.claim(batchSize)
        for (item in claimed) {
            taskExecutor.execute(item.workflowRunId, item.taskName)
        }
    }
}
