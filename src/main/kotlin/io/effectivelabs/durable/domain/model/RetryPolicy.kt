package io.effectivelabs.durable.domain.model

data class RetryPolicy(
    val maxRetries: Int = 0,
    val initialDelayMs: Long = 1_000,
    val backoffFactor: Double = 2.0,
    val maxDelayMs: Long = 60_000,
) {
    companion object {
        fun default(): RetryPolicy = RetryPolicy()
    }
}
