package io.effectivelabs.durable.domain.service

import io.effectivelabs.durable.domain.model.RetryPolicy
import kotlin.test.Test
import kotlin.test.assertEquals

class RetryDelayCalculatorTest {

    private val calculator = RetryDelayCalculator()
    private val policy = RetryPolicy(
        maxRetries = 10,
        initialDelayMs = 1_000,
        backoffFactor = 2.0,
        maxDelayMs = 60_000,
    )

    @Test
    fun `first retry delay is initial delay`() {
        assertEquals(1_000L, calculator.calculateDelayMs(policy, retryCount = 1))
    }

    @Test
    fun `second retry doubles the delay`() {
        assertEquals(2_000L, calculator.calculateDelayMs(policy, retryCount = 2))
    }

    @Test
    fun `third retry quadruples the initial delay`() {
        assertEquals(4_000L, calculator.calculateDelayMs(policy, retryCount = 3))
    }

    @Test
    fun `fourth retry is 8 seconds`() {
        assertEquals(8_000L, calculator.calculateDelayMs(policy, retryCount = 4))
    }

    @Test
    fun `delay is capped at maxDelayMs`() {
        assertEquals(60_000L, calculator.calculateDelayMs(policy, retryCount = 10))
    }

    @Test
    fun `delay does not exceed max even for very high retry count`() {
        assertEquals(60_000L, calculator.calculateDelayMs(policy, retryCount = 100))
    }

    @Test
    fun `custom policy with different backoff factor`() {
        val customPolicy = RetryPolicy(
            maxRetries = 5,
            initialDelayMs = 500,
            backoffFactor = 3.0,
            maxDelayMs = 30_000,
        )

        assertEquals(500L, calculator.calculateDelayMs(customPolicy, retryCount = 1))
        assertEquals(1_500L, calculator.calculateDelayMs(customPolicy, retryCount = 2))
        assertEquals(4_500L, calculator.calculateDelayMs(customPolicy, retryCount = 3))
        assertEquals(13_500L, calculator.calculateDelayMs(customPolicy, retryCount = 4))
        assertEquals(30_000L, calculator.calculateDelayMs(customPolicy, retryCount = 5))
    }
}
