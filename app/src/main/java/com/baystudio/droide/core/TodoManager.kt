package com.baystudio.droide.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap





@Serializable
data class Todo(val content: String, val status: String, val priority: String)

class TodoManager {
    private val store = ConcurrentHashMap<String, MutableStateFlow<List<Todo>>>()
    private val global = MutableStateFlow<List<Todo>>(emptyList())

    fun flow(sessionId: String?): StateFlow<List<Todo>> {
        if (sessionId == null) return global
        return store.computeIfAbsent(sessionId) { MutableStateFlow(emptyList()) }
    }

    val todos: StateFlow<List<Todo>> get() = global

    fun set(all: List<Todo>, sessionId: String? = null) {
        if (sessionId == null) global.value = all.take(30)
        else flow(sessionId).let { (it as MutableStateFlow).value = all.take(30) }
    }

    fun get(sessionId: String? = null): List<Todo> = if (sessionId == null) global.value else store[sessionId]?.value ?: emptyList()

    fun clear(sessionId: String? = null) {
        if (sessionId == null) global.value = emptyList() else store.remove(sessionId)
    }

    fun snapshot(sessionId: String? = null): String {
        val list = (if (sessionId == null) global.value else store[sessionId]?.value ?: emptyList())
        return if (list.isEmpty()) "(no todos)"
        else list.mapIndexed { i, t -> "${i + 1}. [${t.status}/${t.priority}] ${t.content}" }.joinToString("\n")
    }
}
