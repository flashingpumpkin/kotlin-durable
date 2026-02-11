package io.effectivelabs.durable.domain.service

import io.effectivelabs.durable.domain.model.RetryPolicy
import kotlin.math.pow

class RetryDelayCalculator {

    fun calculateDelayMs(policy: RetryPolicy, retryCount: Int): Long {
        val delay = (policy.initialDelayMs * policy.backoffFactor.pow(retryCount - 1)).toLong()
        return delay.coerceAtMost(policy.maxDelayMs)
    }
}
