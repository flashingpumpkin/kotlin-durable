package io.effectivelabs.durable.adapter.postgres

import io.effectivelabs.durable.domain.model.RunStatus
import io.effectivelabs.durable.domain.model.TaskRecord
import io.effectivelabs.durable.domain.model.TaskState
import io.effectivelabs.durable.domain.model.WorkflowRunRecord
import java.time.Instant
import java.util.UUID
import java.util.Collections
import java.util.concurrent.CountDownLatch
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ExposedTaskRepositoryTest : PostgresTestBase() {

    private val workflowRunRepo = ExposedWorkflowRunRepository(db)
    private val repo = ExposedTaskRepository(db)

    private lateinit var workflowRunId: UUID

    @BeforeTest
    fun setUp() {
        cleanTables()
        workflowRunId = UUID.randomUUID()
        workflowRunRepo.create(
            WorkflowRunRecord(
                id = workflowRunId,
                workflowName = "test",
                tenantId = "tenant-1",
                status = RunStatus.RUNNING,
                input = null,
                createdAt = Instant.now(),
                completedAt = null,
            )
        )
    }

    private fun taskRecord(
        name: String,
        status: TaskState = TaskState.PENDING,
        parentNames: List<String> = emptyList(),
        pendingParentCount: Int = parentNames.size,
    ) = TaskRecord(
        workflowRunId = workflowRunId,
        taskName = name,
        status = status,
        parentNames = parentNames,
        pendingParentCount = pendingParentCount,
        output = null,
        error = null,
        retryCount = 0,
        maxRetries = 0,
        createdAt = Instant.now(),
        startedAt = null,
        completedAt = null,
    )

    @Test
    fun `createAll and findByName returns task`() {
        repo.createAll(listOf(taskRecord("a", status = TaskState.QUEUED)))

        val found = repo.findByName(workflowRunId, "a")
        assertNotNull(found)
        assertEquals("a", found.taskName)
        assertEquals(TaskState.QUEUED, found.status)
    }

    @Test
    fun `findByName returns null for nonexistent task`() {
        assertNull(repo.findByName(workflowRunId, "nonexistent"))
    }

    @Test
    fun `findAllByWorkflowRunId returns all tasks`() {
        repo.createAll(
            listOf(
                taskRecord("a", status = TaskState.QUEUED),
                taskRecord("b", parentNames = listOf("a")),
                taskRecord("c", parentNames = listOf("a")),
            )
        )

        val tasks = repo.findAllByWorkflowRunId(workflowRunId)
        assertEquals(3, tasks.size)
    }

    @Test
    fun `updateStatus changes status and output`() {
        repo.createAll(listOf(taskRecord("a", status = TaskState.QUEUED)))

        repo.updateStatus(workflowRunId, "a", TaskState.COMPLETED, output = "result-a")

        val found = repo.findByName(workflowRunId, "a")
        assertNotNull(found)
        assertEquals(TaskState.COMPLETED, found.status)
        assertEquals("result-a", found.output)
    }

    @Test
    fun `decrementPendingParents returns ready tasks when count reaches zero`() {
        // a (root) -> b -> c (linear chain)
        repo.createAll(
            listOf(
                taskRecord("a", status = TaskState.COMPLETED),
                taskRecord("b", parentNames = listOf("a"), pendingParentCount = 1),
                taskRecord("c", parentNames = listOf("b"), pendingParentCount = 1),
            )
        )

        val readyAfterA = repo.decrementPendingParents(workflowRunId, "a")
        assertEquals(1, readyAfterA.size)
        assertEquals("b", readyAfterA[0].taskName)
        assertEquals(TaskState.QUEUED, readyAfterA[0].status)

        // c should not be ready yet
        val readyAfterAForC = repo.findByName(workflowRunId, "c")
        assertNotNull(readyAfterAForC)
        assertEquals(TaskState.PENDING, readyAfterAForC.status)
    }

    @Test
    fun `decrementPendingParents fan-in only readies when all parents complete`() {
        // a, b -> c (fan-in)
        repo.createAll(
            listOf(
                taskRecord("a", status = TaskState.COMPLETED),
                taskRecord("b", status = TaskState.COMPLETED),
                taskRecord("c", parentNames = listOf("a", "b"), pendingParentCount = 2),
            )
        )

        val readyAfterA = repo.decrementPendingParents(workflowRunId, "a")
        assertEquals(0, readyAfterA.size) // c still waiting for b

        val readyAfterB = repo.decrementPendingParents(workflowRunId, "b")
        assertEquals(1, readyAfterB.size)
        assertEquals("c", readyAfterB[0].taskName)
        assertEquals(TaskState.QUEUED, readyAfterB[0].status)
    }

    @Test
    fun `decrementPendingParents concurrent fan-in results in exactly one QUEUED`() {
        // Two threads complete parents "a" and "b" simultaneously for fan-in task "c".
        // Exactly one caller must see "c" transition to QUEUED.
        repo.createAll(
            listOf(
                taskRecord("a", status = TaskState.COMPLETED),
                taskRecord("b", status = TaskState.COMPLETED),
                taskRecord("c", parentNames = listOf("a", "b"), pendingParentCount = 2),
            )
        )

        val allReady = Collections.synchronizedList(mutableListOf<TaskRecord>())
        val latch = CountDownLatch(1)

        val t1 = Thread {
            latch.await()
            allReady.addAll(repo.decrementPendingParents(workflowRunId, "a"))
        }
        val t2 = Thread {
            latch.await()
            allReady.addAll(repo.decrementPendingParents(workflowRunId, "b"))
        }

        t1.start()
        t2.start()
        latch.countDown()
        t1.join()
        t2.join()

        assertEquals(1, allReady.size)
        assertEquals("c", allReady[0].taskName)
        assertEquals(TaskState.QUEUED, allReady[0].status)
    }
}
