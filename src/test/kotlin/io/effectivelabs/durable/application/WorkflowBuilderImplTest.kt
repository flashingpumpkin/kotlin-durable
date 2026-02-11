package io.effectivelabs.durable.application

import io.effectivelabs.durable.domain.model.RetryPolicy
import io.effectivelabs.durable.domain.model.StepRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.time.Duration

class WorkflowBuilderImplTest {

    @Test
    fun `build 3-step linear workflow has correct step count and parent linkages`() {
        val builder = WorkflowBuilderImpl<String>()

        val a: StepRef<String> = builder.step("a") { input, _ -> "result-a" }
        val b: StepRef<String> = builder.step("b", parents = listOf(a)) { input, _ -> "result-b" }
        val c: StepRef<String> = builder.step("c", parents = listOf(b)) { input, _ -> "result-c" }

        val definition = builder.build()

        assertEquals(3, definition.steps.size)
        assertEquals("a", definition.steps[0].name)
        assertEquals("b", definition.steps[1].name)
        assertEquals("c", definition.steps[2].name)

        assertTrue(definition.steps[0].parents.isEmpty())
        assertEquals(listOf("a"), definition.steps[1].parents.map { it.name })
        assertEquals(listOf("b"), definition.steps[2].parents.map { it.name })
    }

    @Test
    fun `step returns StepRef with correct name`() {
        val builder = WorkflowBuilderImpl<String>()

        val ref: StepRef<String> = builder.step("validate") { input, _ -> "validated" }

        assertEquals("validate", ref.name)
    }

    @Test
    fun `build workflow with sleep step`() {
        val builder = WorkflowBuilderImpl<String>()

        val a = builder.step("a") { input, _ -> "done" }
        val sleepRef = builder.sleep("wait", Duration.ofHours(1), parents = listOf(a))
        val b = builder.step("b", parents = listOf(sleepRef)) { input, _ -> "after-sleep" }

        val definition = builder.build()

        assertEquals(3, definition.steps.size)

        val sleepStep = definition.steps[1]
        assertEquals("wait", sleepStep.name)
        assertTrue(sleepStep.isSleep)
        assertEquals(Duration.ofHours(1), sleepStep.sleepDuration)
        assertNull(sleepStep.execute)
    }

    @Test
    fun `duplicate step name throws IllegalArgumentException`() {
        val builder = WorkflowBuilderImpl<String>()

        builder.step("a") { input, _ -> "first" }

        assertFailsWith<IllegalArgumentException>("Duplicate step name: 'a'") {
            builder.step("a") { input, _ -> "second" }
        }
    }

    @Test
    fun `parent references non-existent step throws IllegalArgumentException`() {
        val builder = WorkflowBuilderImpl<String>()
        val phantom = StepRef<String>("does-not-exist")

        builder.step("a", parents = listOf(phantom)) { input, _ -> "result" }

        assertFailsWith<IllegalArgumentException> {
            builder.build()
        }
    }

    @Test
    fun `onFailure handler captured on definition`() {
        val builder = WorkflowBuilderImpl<String>()
        var handlerCalled = false

        builder.step("a") { input, _ -> "result" }
        builder.onFailure { _, _ -> handlerCalled = true }

        val definition = builder.build()

        assertNotNull(definition.onFailureHandler)
    }

    @Test
    fun `definition without onFailure has null handler`() {
        val builder = WorkflowBuilderImpl<String>()

        builder.step("a") { input, _ -> "result" }

        val definition = builder.build()

        assertNull(definition.onFailureHandler)
    }

    @Test
    fun `step with custom retry policy preserves it`() {
        val builder = WorkflowBuilderImpl<String>()
        val customRetry = RetryPolicy(maxRetries = 3, initialDelayMs = 500)

        builder.step("a", retryPolicy = customRetry) { input, _ -> "result" }

        val definition = builder.build()

        assertEquals(3, definition.steps[0].retryPolicy.maxRetries)
        assertEquals(500L, definition.steps[0].retryPolicy.initialDelayMs)
    }

    @Test
    fun `step without parents defaults to empty list`() {
        val builder = WorkflowBuilderImpl<String>()

        builder.step("root") { input, _ -> "result" }

        val definition = builder.build()

        assertTrue(definition.steps[0].parents.isEmpty())
    }

    @Test
    fun `non-sleep step has isSleep false and null duration`() {
        val builder = WorkflowBuilderImpl<String>()

        builder.step("a") { input, _ -> "result" }

        val definition = builder.build()

        assertEquals(false, definition.steps[0].isSleep)
        assertNull(definition.steps[0].sleepDuration)
        assertNotNull(definition.steps[0].execute)
    }
}
