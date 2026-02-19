package io.effectivelabs.durable.spring

import io.effectivelabs.durable.adapter.postgres.ExposedEventRepository
import io.effectivelabs.durable.adapter.postgres.NoOpIdGenerator
import io.effectivelabs.durable.adapter.postgres.ExposedReadyQueueRepository
import io.effectivelabs.durable.adapter.postgres.ExposedTaskRepository
import io.effectivelabs.durable.adapter.postgres.ExposedTimerRepository
import io.effectivelabs.durable.adapter.postgres.ExposedWorkflowRunRepository
import io.effectivelabs.durable.adapter.postgres.RealScheduler
import io.effectivelabs.durable.adapter.postgres.SystemClock
import io.effectivelabs.durable.adapter.postgres.table.ReadyQueueTable
import io.effectivelabs.durable.adapter.postgres.table.TaskEventsTable
import io.effectivelabs.durable.adapter.postgres.table.TasksTable
import io.effectivelabs.durable.adapter.postgres.table.TimersTable
import io.effectivelabs.durable.adapter.postgres.table.WorkflowRunsTable
import io.effectivelabs.durable.domain.port.Clock
import io.effectivelabs.durable.domain.port.EventRepository
import io.effectivelabs.durable.domain.port.IdGenerator
import io.effectivelabs.durable.domain.port.ReadyQueueRepository
import io.effectivelabs.durable.domain.port.Scheduler
import io.effectivelabs.durable.domain.port.TaskRepository
import io.effectivelabs.durable.domain.port.TimerRepository
import io.effectivelabs.durable.domain.port.WorkflowRunRepository
import org.jetbrains.exposed.sql.Database
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import javax.sql.DataSource

@AutoConfiguration
@EnableConfigurationProperties(DurableProperties::class)
open class DurableExposedAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(Database::class)
    open fun exposedDatabase(dataSource: DataSource): Database {
        return Database.connect(dataSource)
    }

    @Bean
    @ConditionalOnMissingBean(WorkflowRunsTable::class)
    open fun workflowRunsTable(): WorkflowRunsTable {
        return WorkflowRunsTable()
    }

    @Bean
    @ConditionalOnMissingBean(TasksTable::class)
    open fun tasksTable(workflowRunsTable: WorkflowRunsTable): TasksTable {
        return TasksTable(workflowRunsTable)
    }

    @Bean
    @ConditionalOnMissingBean(ReadyQueueTable::class)
    open fun readyQueueTable(): ReadyQueueTable {
        return ReadyQueueTable()
    }

    @Bean
    @ConditionalOnMissingBean(TaskEventsTable::class)
    open fun taskEventsTable(): TaskEventsTable {
        return TaskEventsTable()
    }

    @Bean
    @ConditionalOnMissingBean(TimersTable::class)
    open fun timersTable(): TimersTable {
        return TimersTable()
    }

    @Bean
    @ConditionalOnMissingBean(WorkflowRunRepository::class)
    open fun workflowRunRepository(db: Database, table: WorkflowRunsTable): WorkflowRunRepository {
        return ExposedWorkflowRunRepository(db, table)
    }

    @Bean
    @ConditionalOnMissingBean(TaskRepository::class)
    open fun taskRepository(db: Database, table: TasksTable): TaskRepository {
        return ExposedTaskRepository(db, table)
    }

    @Bean
    @ConditionalOnMissingBean(ReadyQueueRepository::class)
    open fun readyQueueRepository(db: Database, table: ReadyQueueTable): ReadyQueueRepository {
        return ExposedReadyQueueRepository(db, table)
    }

    @Bean
    @ConditionalOnMissingBean(EventRepository::class)
    open fun eventRepository(db: Database, table: TaskEventsTable): EventRepository {
        return ExposedEventRepository(db, table)
    }

    @Bean
    @ConditionalOnMissingBean(TimerRepository::class)
    open fun timerRepository(db: Database, table: TimersTable): TimerRepository {
        return ExposedTimerRepository(db, table)
    }

    @Bean
    @ConditionalOnMissingBean(Clock::class)
    open fun clock(): Clock {
        return SystemClock()
    }

    @Bean
    @ConditionalOnMissingBean(Scheduler::class)
    open fun scheduler(): Scheduler {
        return RealScheduler()
    }

    @Bean
    @ConditionalOnMissingBean(IdGenerator::class)
    open fun idGenerator(): IdGenerator {
        return NoOpIdGenerator()
    }

    @Bean
    @ConditionalOnProperty(
        prefix = "durable.schema",
        name = ["auto-create"],
        havingValue = "true",
        matchIfMissing = false
    )
    open fun schemaInitializer(
        db: Database,
        workflowRunsTable: WorkflowRunsTable,
        tasksTable: TasksTable,
        readyQueueTable: ReadyQueueTable,
        taskEventsTable: TaskEventsTable,
        timersTable: TimersTable,
    ): SchemaInitializer {
        return SchemaInitializer(
            db,
            workflowRunsTable,
            tasksTable,
            readyQueueTable,
            taskEventsTable,
            timersTable,
        )
    }
}
