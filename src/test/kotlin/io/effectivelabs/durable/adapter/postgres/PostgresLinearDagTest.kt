package io.effectivelabs.durable.adapter.postgres

import io.effectivelabs.durable.adapter.inmemory.InMemoryWorkflowRegistry
import io.effectivelabs.durable.application.DagTaskEngine
import io.effectivelabs.durable.domain.model.RunStatus
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class PostgresLinearDagTest : PostgresTestBase() {

    @BeforeTest
    fun setUp() {
        cleanTables()
    }

    private fun buildEngine(): DagTaskEngine {
        return DagTaskEngine(
            workflowRunRepository = ExposedWorkflowRunRepository(),
            taskRepository = ExposedTaskRepository(),
            readyQueueRepository = ExposedReadyQueueRepository(),
            eventRepository = ExposedEventRepository(),
            workflowRegistry = InMemoryWorkflowRegistry(),
            clock = SystemClock(),
            scheduler = RealScheduler(),
            idGenerator = ExposedIdGenerator(),
            pollIntervalMs = 50,
            batchSize = 10,
        )
    }

    @Test
    fun `a to b to c linear workflow completes against postgres`() {
        val engine = buildEngine()

        val workflow = engine.workflow<String>("linear-pg-test") {
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
        try {
            val result = workflow.run("hello", tenantId = "tenant-1")

            assertEquals(RunStatus.COMPLETED, result.status)
            assertEquals("result-a(hello)", result.outputs["a"])
            assertEquals("result-b(result-a(hello))", result.outputs["b"])
            assertEquals("result-c(result-b(result-a(hello)))", result.outputs["c"])
        } finally {
            engine.stop()
        }
    }

    @Test
    fun `single step workflow completes against postgres`() {
        val engine = buildEngine()

        val workflow = engine.workflow<String>("single-step-pg") {
            step("only") { input, _ -> "processed($input)" }
        }

        engine.start()
        try {
            val result = workflow.run("data", tenantId = "tenant-1")

            assertEquals(RunStatus.COMPLETED, result.status)
            assertEquals("processed(data)", result.outputs["only"])
        } finally {
            engine.stop()
        }
    }

    @Test
    fun `step failure marks workflow as failed against postgres`() {
        val engine = buildEngine()

        val workflow = engine.workflow<String>("failure-pg-test") {
            val a = step("a") { _, _ -> "ok" }
            step("b", parents = listOf(a)) { _, _ ->
                error("step b failed")
            }
        }

        engine.start()
        try {
            val result = workflow.run("input", tenantId = "tenant-1")
            assertEquals(RunStatus.FAILED, result.status)
        } finally {
            engine.stop()
        }
    }
}
