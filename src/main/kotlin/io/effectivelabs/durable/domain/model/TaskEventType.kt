package io.effectivelabs.durable.domain.model

enum class TaskEventType {
    QUEUED,
    STARTED,
    COMPLETED,
    FAILED,
    RETRYING,
    CANCELLED,
    SKIPPED,
}
