package com.baystudio.droide.core

import kotlinx.serialization.json.*


internal object AgentIdeResult {
    private const val JSON_BUDGET = 8_000

    fun jsonPage(key: String, items: List<JsonObject>, start: Int = 0, count: Int = 100,
                 sourceOffset: Int = 0, mayHaveMore: Boolean = false): String {
        require(key.matches(Regex("[a-z_]+")) && start >= 0 && count in 1..500 && sourceOffset >= 0)
        val selected = mutableListOf<JsonObject>()
        val end = minOf(items.size.toLong(), start.toLong() + count).toInt()
        var cursor = minOf(start, items.size)
        var used = 0
        var omitted = 0
        while (cursor < end) {
            var item = items[cursor]
            var length = item.toString().length
            var itemOmitted = false
            if (length > JSON_BUDGET - 512) {
                item = buildJsonObject {
                    put("omitted", true); put("reason", "Item exceeds response budget; inspect its frame or variable reference")
                    for (identity in listOf("id", "reference", "line")) {
                        (items[cursor][identity] as? JsonPrimitive)?.intOrNull?.let { put(identity, it) }
                    }
                }
                length = item.toString().length
                itemOmitted = true
            }
            if (used + length + 1 > JSON_BUDGET - 512) break
            selected += item
            if (itemOmitted) omitted++
            used += length + 1
            cursor++
        }
        val more = cursor < items.size
        return buildJsonObject {
            put(key, JsonArray(selected)); put("start", sourceOffset.toLong() + start)
            put("returned", selected.size); put("available_in_batch", items.size)
            put("has_more", more); put("may_have_more", mayHaveMore); put("omitted_items", omitted)
            put("truncated", more || omitted > 0)
            val next = sourceOffset.toLong() + cursor
            if ((more || mayHaveMore) && selected.isNotEmpty() && next <= Int.MAX_VALUE) put("next_start", next)
        }.toString().also { check(it.length <= JSON_BUDGET) }
    }

    fun jsonText(key: String, value: String): String {
        require(key.matches(Regex("[a-z_]+")))
        fun payload(length: Int): String = buildJsonObject {
            val end = if (length > 0 && length < value.length && value[length - 1].isHighSurrogate()) length - 1 else length
            put(key, value.take(end)); put("truncated", end < value.length); put("original_characters", value.length)
        }.toString()
        val full = payload(value.length)
        if (full.length <= JSON_BUDGET) return full
        var low = 0
        var high = value.length
        while (low < high) {
            val middle = low + (high - low + 1) / 2
            if (payload(middle).length <= JSON_BUDGET) low = middle else high = middle - 1
        }
        return payload(low)
    }

    fun succeeded(operation: String, payload: String): String = render(operation, AgentToolEvidenceState.SUCCEEDED, true, payload)
    fun started(operation: String, payload: String): String = render(operation, AgentToolEvidenceState.STARTED, true, "IDE_EXECUTION_PENDING\n$payload")
    fun failed(operation: String, payload: String, operationStarted: Boolean = true): String =
        render(operation, AgentToolEvidenceState.FAILED, operationStarted, payload)

    fun runFile(result: RunnerExecutionResult): String {
        val exit = if (result.success) 0 else result.exitCode?.takeIf { it != 0 } ?: 1
        val payload = "exit=$exit\nsuccess=${result.success}\ntimed_out=${result.timedOut}\noutput:\n${result.output}"
        return if (result.success) succeeded("run_file", payload) else failed("run_file", payload, result.processStarted)
    }

    private fun render(operation: String, state: AgentToolEvidenceState, started: Boolean, payload: String): String {
        require(operation in AgentIdeRequest.OPERATIONS) { "Invalid IDE result operation" }
        return "IDE_ACTION operation=$operation\noutcome=${state.name.lowercase()}\noperation_started=$started\n$payload"
    }

    fun evidence(raw: String, expectedOperation: String?): AgentToolEvidence? {
        if (!raw.startsWith("IDE_ACTION operation=")) return null
        val lines = raw.lineSequence().take(3).toList()
        val operation = lines.first().removePrefix("IDE_ACTION operation=")
        val state = when (lines.getOrNull(1)) {
            "outcome=succeeded" -> AgentToolEvidenceState.SUCCEEDED
            "outcome=started" -> AgentToolEvidenceState.STARTED
            "outcome=failed" -> AgentToolEvidenceState.FAILED
            else -> null
        }
        val started = when (lines.getOrNull(2)) {
            "operation_started=true" -> true
            "operation_started=false" -> false
            else -> null
        }
        if (operation !in AgentIdeRequest.OPERATIONS || expectedOperation != null && operation != expectedOperation ||
            state == null || started == null || state != AgentToolEvidenceState.FAILED && !started) {
            return AgentToolEvidence(AgentToolEvidenceState.FAILED, false, false, "IDE result has invalid or mismatched execution evidence")
        }
        return AgentToolEvidence(state, started, when (state) {
            AgentToolEvidenceState.SUCCEEDED -> true
            AgentToolEvidenceState.STARTED -> null
            else -> false
        }, if (state == AgentToolEvidenceState.STARTED) "IDE accepted execution/control; target completion is unverified" else "IDE request ${state.name.lowercase()}")
    }
}
