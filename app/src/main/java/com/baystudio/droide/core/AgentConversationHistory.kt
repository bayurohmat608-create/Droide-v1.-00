package com.baystudio.droide.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Owns the two histories with intentionally different lifecycles: - active: model-facing and compactable.


internal class AgentConversationHistory {
    val active = mutableListOf<JsonObject>()
    private val exactFork = mutableListOf<JsonObject>()
    private var exact = true

    fun resetNew() {
        active.clear()
        exactFork.clear()
        exact = true
    }

    fun load(session: DroideSession): List<AgentMessage> {
        active.clear()
        if (session.apiHistory.isNotEmpty()) {
            active.addAll(session.apiHistory)
        } else {
            session.messages.filter { it.streamPart == null }
                .filterNot { it.content.startsWith("TOOL|") }
                .filter { it.role == "user" || it.role == "assistant" }
                .forEach { message ->
                    active += buildJsonObject {
                        put("role", message.role)
                        put("content", message.content.take(8_000))
                    }
                }
        }

        exactFork.clear()
        val migrated = when {
            session.forkHistoryVersion == SessionForkHistory.SCHEMA_VERSION &&
                SessionForkHistory.isExact(session.forkHistory) -> session.forkHistory
            session.forkHistoryVersion == 0 -> SessionForkHistory.migrateLegacy(session.messages, session.apiHistory)
            else -> null
        }
        exact = migrated != null
        if (migrated != null) exactFork.addAll(migrated)
        return if (migrated != null) SessionForkHistory.annotate(session.messages, migrated)
        else session.messages.map { it.copy(forkHistoryEnd = null) }
    }

    fun append(entry: JsonObject) {
        active += entry
        if (exact) exactFork += entry
    }

    fun append(entries: List<JsonObject>) {
        active += entries
        if (exact) exactFork += entries
    }

    fun completeActive(): List<JsonObject> = AgentHistory.completeGroups(active).flatten()

    fun completeExactOrNull(): List<JsonObject>? {
        if (!exact) return null
        val complete = AgentHistory.completeGroups(exactFork).flatten()
        check(complete.size == exactFork.size && complete.indices.all { complete[it] == exactFork[it] }) {
            "Exact fork history contains an incomplete structured tool group"
        }
        return complete
    }
}
