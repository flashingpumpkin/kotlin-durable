package io.effectivelabs.durable.domain.port

interface TaskSerializer {
    fun serialize(value: Any?): ByteArray
    fun <T> deserialize(data: ByteArray, type: Class<T>): T
}
