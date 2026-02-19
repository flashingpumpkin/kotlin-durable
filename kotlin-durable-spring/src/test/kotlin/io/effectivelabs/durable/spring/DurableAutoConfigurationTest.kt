package io.effectivelabs.durable.spring

import io.effectivelabs.durable.domain.port.Clock
import io.effectivelabs.durable.domain.port.DurableTaskEngine
import io.effectivelabs.durable.domain.port.EventRepository
import io.effectivelabs.durable.domain.port.IdGenerator
import io.effectivelabs.durable.domain.port.ReadyQueueRepository
import io.effectivelabs.durable.domain.port.Scheduler
import io.effectivelabs.durable.domain.port.TaskRepository
import io.effectivelabs.durable.domain.port.TimerRepository
import io.effectivelabs.durable.domain.port.WorkflowRegistry
import io.effectivelabs.durable.domain.port.WorkflowRunRepository
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import kotlin.test.assertNotNull

class DurableAutoConfigurationTest {

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                DataSourceAutoConfiguration::class.java,
                DurableExposedAutoConfiguration::class.java,
                DurableEngineAutoConfiguration::class.java,
            )
        )
        .withPropertyValues(
            "spring.datasource.url=jdbc:postgresql://localhost:5432/durable",
            "spring.datasource.username=durable",
            "spring.datasource.password=durable",
            "spring.datasource.driver-class-name=org.postgresql.Driver",
        )

    @Test
    fun `auto-configuration creates all repository beans`() {
        contextRunner.run { context ->
            assertNotNull(context.getBean(WorkflowRunRepository::class.java))
            assertNotNull(context.getBean(TaskRepository::class.java))
            assertNotNull(context.getBean(ReadyQueueRepository::class.java))
            assertNotNull(context.getBean(EventRepository::class.java))
            assertNotNull(context.getBean(TimerRepository::class.java))
            assertNotNull(context.getBean(Clock::class.java))
            assertNotNull(context.getBean(Scheduler::class.java))
            assertNotNull(context.getBean(IdGenerator::class.java))
            assertNotNull(context.getBean(WorkflowRegistry::class.java))
            assertNotNull(context.getBean(DurableTaskEngine::class.java))
        }
    }
}
