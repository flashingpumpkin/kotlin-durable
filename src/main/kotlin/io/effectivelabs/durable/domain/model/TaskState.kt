package io.effectivelabs.durable.domain.model

enum class TaskState {
    PENDING,
    QUEUED,
    RUNNING,
    SLEEPING,
    COMPLETED,
    FAILED,
    CANCELLED,
    SKIPPED,
}
