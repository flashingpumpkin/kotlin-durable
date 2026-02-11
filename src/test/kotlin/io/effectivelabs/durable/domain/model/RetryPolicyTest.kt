package io.effectivelabs.durable.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals

class RetryPolicyTest {

    @Test
    fun `default policy has zero retries`() {
        val policy = RetryPolicy.default()

        assertEquals(0, policy.maxRetries)
    }

    @Test
    fun `default policy has 1000ms initial delay`() {
        val policy = RetryPolicy.default()

        assertEquals(1_000L, policy.initialDelayMs)
    }

    @Test
    fun `default policy has backoff factor of 2`() {
        val policy = RetryPolicy.default()

        assertEquals(2.0, policy.backoffFactor)
    }

    @Test
    fun `default policy has 60s max delay`() {
        val policy = RetryPolicy.default()

        assertEquals(60_000L, policy.maxDelayMs)
    }

    @Test
    fun `custom policy preserves all fields`() {
        val policy = RetryPolicy(
            maxRetries = 5,
            initialDelayMs = 500,
            backoffFactor = 3.0,
            maxDelayMs = 30_000,
        )

        assertEquals(5, policy.maxRetries)
        assertEquals(500L, policy.initialDelayMs)
        assertEquals(3.0, policy.backoffFactor)
        assertEquals(30_000L, policy.maxDelayMs)
    }
}
