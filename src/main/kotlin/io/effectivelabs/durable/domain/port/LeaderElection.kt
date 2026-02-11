package io.effectivelabs.durable.domain.port

interface LeaderElection {
    fun tryAcquire(): Boolean
    fun release()
    fun isLeader(): Boolean
}
