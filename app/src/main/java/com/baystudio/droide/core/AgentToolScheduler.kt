package com.baystudio.droide.core

// Preserve ordering across mutation and approval boundaries.


internal object AgentToolScheduler {
    enum class Lane { PARALLEL_READ, SERIAL }

    data class Facts(
        val index: Int,
        val readOnlyCandidate: Boolean,
        val permissionAllowsWithoutPrompt: Boolean,
        val lifecycleHookBoundary: Boolean,
        val doomLoopApprovalPossible: Boolean,
    ) {
        val lane: Lane
            get() = if (
                readOnlyCandidate &&
                permissionAllowsWithoutPrompt &&
                !lifecycleHookBoundary &&
                !doomLoopApprovalPossible
            ) Lane.PARALLEL_READ else Lane.SERIAL
    }

    data class Batch(val lane: Lane, val indices: List<Int>) {
        val parallel: Boolean get() = lane == Lane.PARALLEL_READ && indices.size > 1
    }

     
    fun batches(facts: List<Facts>): List<Batch> {
        if (facts.isEmpty()) return emptyList()
        val out = mutableListOf<Batch>()
        var readBatch = mutableListOf<Int>()
        fun flushReads() {
            if (readBatch.isNotEmpty()) {
                out += Batch(Lane.PARALLEL_READ, readBatch.toList())
                readBatch = mutableListOf()
            }
        }
        facts.sortedBy { it.index }.forEach { fact ->
            when (fact.lane) {
                Lane.PARALLEL_READ -> readBatch += fact.index
                Lane.SERIAL -> {
                    flushReads()
                    out += Batch(Lane.SERIAL, listOf(fact.index))
                }
            }
        }
        flushReads()
        return out
    }
}

// Exact permission resource used to decide whether a read can avoid interactive approval.
internal data class AgentToolReadAccess(val action: String, val resource: String)
