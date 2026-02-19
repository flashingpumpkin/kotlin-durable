package io.effectivelabs.durable.spring

import io.effectivelabs.durable.adapter.postgres.ExposedEventRepository
import io.effectivelabs.durable.adapter.postgres.ExposedIdGenerator
import io.effectivelabs.durable.adapter.postgres.ExposedReadyQueueRepository
import io.effectivelabs.durable.adapter.postgres.ExposedTaskRepository
import io.effectivelabs.durable.adapter.postgres.ExposedTimerRepository
import io.effectivelabs.durable.adapter.postgres.ExposedWorkflowRunRepository
import io.effectivelabs.durable.adapter.postgres.RealScheduler
import io.effectivelabs.durable.adapter.postgres.SystemClock
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
import org.springframework.context.annotation.Bean
import javax.sql.DataSource

@AutoConfiguration
open class DurableExposedAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(Database::class)
    open fun exposedDatabase(dataSource: DataSource): Database {
        return Database.connect(dataSource)
    }

    @Bean
    @ConditionalOnMissingBean(WorkflowRunRepository::class)
    open fun workflowRunRepository(db: Database): WorkflowRunRepository {
        return ExposedWorkflowRunRepository(db)
    }

    @Bean
    @ConditionalOnMissingBean(TaskRepository::class)
    open fun taskRepository(db: Database): TaskRepository {
        return ExposedTaskRepository(db)
    }

    @Bean
    @ConditionalOnMissingBean(ReadyQueueRepository::class)
    open fun readyQueueRepository(db: Database): ReadyQueueRepository {
        return ExposedReadyQueueRepository(db)
    }

    @Bean
    @ConditionalOnMissingBean(EventRepository::class)
    open fun eventRepository(db: Database): EventRepository {
        return ExposedEventRepository(db)
    }

    @Bean
    @ConditionalOnMissingBean(TimerRepository::class)
    open fun timerRepository(db: Database): TimerRepository {
        return ExposedTimerRepository(db)
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
        return ExposedIdGenerator()
    }
}
