package com.baystudio.droide.core

// Registry/workspace ownership lives elsewhere.
object ProjectHistoryPolicy {
    const val MAX_RECENTS = 64

    fun initial(
        persisted: List<String>?,
        registeredIds: List<String>,
        activeId: String?,
    ): List<String> {
        val registered = registeredIds.asSequence().filter { it != "default" }.distinct().toList()
        val known = registered.toSet()
        val migrated = if (persisted == null) registered else persisted.filter { it in known }.distinct()
        val active = activeId?.takeIf { it != "default" && it in known }
        return (listOfNotNull(active) + migrated.filterNot { it == active }).take(MAX_RECENTS)
    }

    fun markRecent(current: List<String>, id: String, registeredIds: Set<String>): List<String> {
        if (id == "default" || id !in registeredIds) return current.distinct().take(MAX_RECENTS)
        return (listOf(id) + current.filterNot { it == id }).distinct().take(MAX_RECENTS)
    }

    fun remove(current: List<String>, id: String): List<String> =
        current.filterNot { it == id }.distinct().take(MAX_RECENTS)

    fun clear(activeId: String?): List<String> =
        listOfNotNull(activeId?.takeIf { it != "default" }).take(MAX_RECENTS)
}
