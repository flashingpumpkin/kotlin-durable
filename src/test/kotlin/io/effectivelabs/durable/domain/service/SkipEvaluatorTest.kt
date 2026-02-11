package io.effectivelabs.durable.domain.service

import io.effectivelabs.durable.domain.model.RetryPolicy
import io.effectivelabs.durable.domain.model.SkipCondition
import io.effectivelabs.durable.domain.model.StepDefinition
import io.effectivelabs.durable.domain.model.StepRef
import io.effectivelabs.durable.domain.model.TaskRecord
import io.effectivelabs.durable.domain.model.TaskState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.time.Instant
import java.util.UUID

class SkipEvaluatorTest {

    private val evaluator = SkipEvaluator()
    private val workflowRunId = UUID.randomUUID()

    @Test
    fun `predicate returns true causes skip`() {
        val parentRef = StepRef<String>("validate")
        val step = stepDefinition(
            name = "charge",
            parents = listOf(parentRef),
            skipConditions = listOf(SkipCondition(parentRef) { output: String -> output == "invalid" }),
        )
        val parentTasks = mapOf(
            "validate" to taskRecord("validate", TaskState.COMPLETED, output = "invalid"),
        )

        assertTrue(evaluator.shouldSkip(step, parentTasks))
    }

    @Test
    fun `predicate returns false does not skip`() {
        val parentRef = StepRef<String>("validate")
        val step = stepDefinition(
            name = "charge",
            parents = listOf(parentRef),
            skipConditions = listOf(SkipCondition(parentRef) { output: String -> output == "invalid" }),
        )
        val parentTasks = mapOf(
            "validate" to taskRecord("validate", TaskState.COMPLETED, output = "valid"),
        )

        assertFalse(evaluator.shouldSkip(step, parentTasks))
    }

    @Test
    fun `parent SKIPPED causes cascade skip regardless of predicate`() {
        val parentRef = StepRef<String>("validate")
        val step = stepDefinition(
            name = "charge",
            parents = listOf(parentRef),
            skipConditions = listOf(SkipCondition(parentRef) { false }),
        )
        val parentTasks = mapOf(
            "validate" to taskRecord("validate", TaskState.SKIPPED),
        )

        assertTrue(evaluator.shouldSkip(step, parentTasks))
    }

    @Test
    fun `no skip conditions and no skipped parents does not skip`() {
        val parentRef = StepRef<String>("validate")
        val step = stepDefinition(
            name = "charge",
            parents = listOf(parentRef),
            skipConditions = emptyList(),
        )
        val parentTasks = mapOf(
            "validate" to taskRecord("validate", TaskState.COMPLETED, output = "done"),
        )

        assertFalse(evaluator.shouldSkip(step, parentTasks))
    }

    @Test
    fun `step with no parents does not skip`() {
        val step = stepDefinition(
            name = "root",
            parents = emptyList(),
            skipConditions = emptyList(),
        )

        assertFalse(evaluator.shouldSkip(step, emptyMap()))
    }

    private fun stepDefinition(
        name: String,
        parents: List<StepRef<*>>,
        skipConditions: List<SkipCondition<*>>,
    ): StepDefinition =
        StepDefinition(
            name = name,
            parents = parents,
            retryPolicy = RetryPolicy.default(),
            skipConditions = skipConditions,
            isSleep = false,
            sleepDuration = null,
            execute = null,
        )

    private fun taskRecord(
        name: String,
        status: TaskState,
        output: Any? = null,
    ): TaskRecord =
        TaskRecord(
            workflowRunId = workflowRunId,
            taskName = name,
            status = status,
            parentNames = emptyList(),
            pendingParentCount = 0,
            output = output,
            error = null,
            retryCount = 0,
            maxRetries = 0,
            createdAt = Instant.EPOCH,
            startedAt = null,
            completedAt = null,
        )
}
