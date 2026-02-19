package io.effectivelabs.durable.adapter.postgres

import io.effectivelabs.durable.domain.port.IdGenerator
import java.security.SecureRandom
import kotlin.math.abs

class ExposedIdGenerator : IdGenerator {

    private val random = SecureRandom()

    override fun nextQueueId(): Long {
        var value = random.nextLong()
        if (value == Long.MIN_VALUE) value = 0L
        return abs(value)
    }
}
