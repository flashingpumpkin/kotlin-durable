package io.effectivelabs.durable.spring

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "durable")
data class DurableProperties(
    val schema: SchemaProperties = SchemaProperties(),
)

data class SchemaProperties(
    val autoCreate: Boolean = false,
)
