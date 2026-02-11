package io.effectivelabs.durable.acceptance

import io.effectivelabs.durable.application.TestEngineBuilder
import io.effectivelabs.durable.domain.model.RunStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LinearDagTest {

    @Test
    fun `a to b to c linear workflow completes with all outputs`() {
        val harness = TestEngineBuilder()
        val engine = harness.build()

        val workflow = engine.workflow<String>("linear-test") {
            val a = step("a") { input, _ -> "result-a($input)" }
            val b = step("b", parents = listOf(a)) { input, ctx ->
                val parentOut = ctx.parentOutput(a)
                "result-b($parentOut)"
            }
            step("c", parents = listOf(b)) { input, ctx ->
                val parentOut = ctx.parentOutput(b)
                "result-c($parentOut)"
            }
        }

        engine.start()
        val ref = workflow.runNoWait("hello", tenantId = "tenant-1")
        val result = harness.runUntilComplete(ref.id)

        assertEquals(RunStatus.COMPLETED, result.status)
        assertEquals("result-a(hello)", result.outputs["a"])
        assertEquals("result-b(result-a(hello))", result.outputs["b"])
        assertEquals("result-c(result-b(result-a(hello)))", result.outputs["c"])
    }

    @Test
    fun `step b receives step a output via parentOutput`() {
        val harness = TestEngineBuilder()
        val engine = harness.build()
        var capturedParentOutput: String? = null

        val workflow = engine.workflow<String>("parent-output-test") {
            val a = step("a") { _, _ -> "from-a" }
            step("b", parents = listOf(a)) { _, ctx ->
                capturedParentOutput = ctx.parentOutput(a)
                "from-b"
            }
        }

        engine.start()
        val ref = workflow.runNoWait("input", tenantId = "tenant-1")
        harness.runUntilComplete(ref.id)

        assertEquals("from-a", capturedParentOutput)
    }

    @Test
    fun `step c receives step b output via parentOutput`() {
        val harness = TestEngineBuilder()
        val engine = harness.build()
        var capturedParentOutput: String? = null

        val workflow = engine.workflow<String>("chain-output-test") {
            val a = step("a") { _, _ -> "value-a" }
            val b = step("b", parents = listOf(a)) { _, _ -> "value-b" }
            step("c", parents = listOf(b)) { _, ctx ->
                capturedParentOutput = ctx.parentOutput(b)
                "value-c"
            }
        }

        engine.start()
        val ref = workflow.runNoWait("input", tenantId = "tenant-1")
        harness.runUntilComplete(ref.id)

        assertEquals("value-b", capturedParentOutput)
    }

    @Test
    fun `single step workflow completes`() {
        val harness = TestEngineBuilder()
        val engine = harness.build()

        val workflow = engine.workflow<String>("single-step") {
            step("only") { input, _ -> "processed($input)" }
        }

        engine.start()
        val ref = workflow.runNoWait("data", tenantId = "tenant-1")
        val result = harness.runUntilComplete(ref.id)

        assertEquals(RunStatus.COMPLETED, result.status)
        assertEquals("processed(data)", result.outputs["only"])
    }

    @Test
    fun `runNoWait returns WorkflowRunRef with valid UUID`() {
        val harness = TestEngineBuilder()
        val engine = harness.build()

        val workflow = engine.workflow<String>("ref-test") {
            step("a") { _, _ -> "done" }
        }

        engine.start()
        val ref = workflow.runNoWait("input", tenantId = "tenant-1")

        assertNotNull(ref)
        assertNotNull(ref.id)
    }

    @Test
    fun `step throws exception causes workflow FAILED`() {
        val harness = TestEngineBuilder()
        val engine = harness.build()

        val workflow = engine.workflow<String>("failure-test") {
            val a = step("a") { _, _ -> "ok" }
            step("b", parents = listOf(a)) { _, _ ->
                error("step b failed")
            }
        }

        engine.start()
        val ref = workflow.runNoWait("input", tenantId = "tenant-1")
        val result = harness.runUntilComplete(ref.id)

        assertEquals(RunStatus.FAILED, result.status)
    }

    @Test
    fun `workflow with typed input passes input to all steps`() {
        val harness = TestEngineBuilder()
        val engine = harness.build()
        val capturedInputs = mutableListOf<Int>()

        val workflow = engine.workflow<Int>("typed-input") {
            val a = step("a") { input, _ ->
                capturedInputs.add(input)
                input * 2
            }
            step("b", parents = listOf(a)) { input, _ ->
                capturedInputs.add(input)
                input * 3
            }
        }

        engine.start()
        val ref = workflow.runNoWait(42, tenantId = "tenant-1")
        harness.runUntilComplete(ref.id)

        assertEquals(listOf(42, 42), capturedInputs)
    }

    @Test
    fun `failure in first step marks workflow as failed`() {
        val harness = TestEngineBuilder()
        val engine = harness.build()

        val workflow = engine.workflow<String>("first-step-fail") {
            val a = step("a") { _, _ -> error("immediate failure") }
            step("b", parents = listOf(a)) { _, _ -> "unreachable" }
        }

        engine.start()
        val ref = workflow.runNoWait("input", tenantId = "tenant-1")
        val result = harness.runUntilComplete(ref.id)

        assertEquals(RunStatus.FAILED, result.status)
    }

    @Test
    fun `outputs map contains entries for completed steps only`() {
        val harness = TestEngineBuilder()
        val engine = harness.build()

        val workflow = engine.workflow<String>("partial-output") {
            val a = step("a") { _, _ -> "output-a" }
            step("b", parents = listOf(a)) { _, _ ->
                error("b fails")
            }
            step("c", parents = listOf(a)) { _, _ -> "output-c" }
        }

        engine.start()
        val ref = workflow.runNoWait("input", tenantId = "tenant-1")
        val result = harness.runUntilComplete(ref.id)

        assertEquals(RunStatus.FAILED, result.status)
        assertEquals("output-a", result.outputs["a"])
        assertTrue(result.outputs.containsKey("b"))
        assertTrue(result.outputs.containsKey("c"))
    }
}
