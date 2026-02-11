package io.effectivelabs.durable.adapter.inmemory

import io.effectivelabs.durable.domain.port.LeaderElection

class InMemoryLeaderElection : LeaderElection {

    override fun tryAcquire(): Boolean = true

    override fun release() {}

    override fun isLeader(): Boolean = true
}
