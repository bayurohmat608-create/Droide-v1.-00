package com.baystudio.droide.core


enum class WorkspaceDocumentKind { TEXT, IMAGE, BINARY, LARGE }

data class WorkspaceDocumentSnapshot(
    val path: String,
    val content: String,
    val loaded: Boolean,
    val kind: WorkspaceDocumentKind,
    val dirty: Boolean,
    val editable: Boolean,
    val revision: Int,
    val changeVersion: Int,
    val selectionStart: Int,
    val selectionEnd: Int,
    val savedContentLength: Int,
) {
    init {
        require(path.isNotBlank()) { "Workspace document path is blank" }
        require(revision >= 0) { "Workspace document revision is negative" }
        require(changeVersion >= 0) { "Workspace document changeVersion is negative" }
        require(savedContentLength >= 0) { "Workspace document savedContentLength is negative" }
        if (kind == WorkspaceDocumentKind.LARGE && !editable) {
            

            require(selectionStart >= 0) { "selectionStart is negative" }
            require(selectionEnd >= 0) { "selectionEnd is negative" }
        } else {
            require(selectionStart in 0..content.length) { "selectionStart is outside document content" }
            require(selectionEnd in 0..content.length) { "selectionEnd is outside document content" }
        }
    }

     
    val divergedFromSavedBaseline: Boolean get() = dirty
}

data class WorkspaceDocumentVersion(
    val revision: Int,
    val changeVersion: Int,
) {
    init {
        require(revision >= 0) { "Workspace document revision is negative" }
        require(changeVersion >= 0) { "Workspace document changeVersion is negative" }
    }
}

val WorkspaceDocumentSnapshot.version: WorkspaceDocumentVersion
    get() = WorkspaceDocumentVersion(revision, changeVersion)


data class WorkspacePersistedReconciliation(
    val refreshed: List<String> = emptyList(),
    val removed: List<String> = emptyList(),
    val remapped: List<Pair<String, String>> = emptyList(),
    val conflicts: List<String> = emptyList(),
) {
    val changed: Boolean get() = refreshed.isNotEmpty() || removed.isNotEmpty() || remapped.isNotEmpty()
}

class WorkspaceDocumentConflictException(val paths: List<String>) :
    IllegalStateException(
        "Persisted workspace mutation conflicts with unsaved editor buffer(s): " +
            paths.distinct().sorted().joinToString(", ")
    )

sealed interface WorkspaceDocumentMutationResult {
    data object NotLive : WorkspaceDocumentMutationResult
    data class ReadOnly(val current: WorkspaceDocumentSnapshot) : WorkspaceDocumentMutationResult
    data class Conflict(val current: WorkspaceDocumentSnapshot) : WorkspaceDocumentMutationResult
    data class Applied(
        val before: WorkspaceDocumentSnapshot,
        val after: WorkspaceDocumentSnapshot,
    ) : WorkspaceDocumentMutationResult
}


interface WorkspaceDocumentAuthority {
    suspend fun snapshot(path: String): WorkspaceDocumentSnapshot?
    suspend fun snapshots(): List<WorkspaceDocumentSnapshot>

    suspend fun dirtySnapshots(): List<WorkspaceDocumentSnapshot> = snapshots().filter { it.dirty }

    // Implementations must never persist the new content to disk as a side effect.


    suspend fun replaceText(
        path: String,
        expected: WorkspaceDocumentVersion,
        content: String,
        reason: String,
    ): WorkspaceDocumentMutationResult = WorkspaceDocumentMutationResult.NotLive

     
    suspend fun validatePersistedMutation(mutation: FileRepository.Mutation) = Unit

     
    suspend fun persistedMutation(mutation: FileRepository.Mutation): WorkspacePersistedReconciliation =
        WorkspacePersistedReconciliation()

     
    suspend fun reconcilePersistedWorkspace(): WorkspacePersistedReconciliation =
        WorkspacePersistedReconciliation()
}

// Bindings are generation-scoped: closing an old binding can never detach a newer editor authority.


class WorkspaceDocumentAuthorityBridge : WorkspaceDocumentAuthority {
    private val lock = Any()
    private var generation = 0L
    private var delegate: WorkspaceDocumentAuthority? = null

    class Binding internal constructor(
        private val owner: WorkspaceDocumentAuthorityBridge,
        internal val generation: Long,
    ) : AutoCloseable {
        private var closed = false

        override fun close() {
            if (closed) return
            closed = true
            owner.unbind(generation)
        }
    }

    fun bind(authority: WorkspaceDocumentAuthority): Binding = synchronized(lock) {
        generation += 1L
        delegate = authority
        Binding(this, generation)
    }

    val isBound: Boolean get() = synchronized(lock) { delegate != null }

    override suspend fun snapshot(path: String): WorkspaceDocumentSnapshot? =
        currentDelegate()?.snapshot(path)

    override suspend fun snapshots(): List<WorkspaceDocumentSnapshot> =
        currentDelegate()?.snapshots() ?: emptyList()

    override suspend fun dirtySnapshots(): List<WorkspaceDocumentSnapshot> =
        currentDelegate()?.dirtySnapshots() ?: emptyList()

    override suspend fun replaceText(
        path: String,
        expected: WorkspaceDocumentVersion,
        content: String,
        reason: String,
    ): WorkspaceDocumentMutationResult = currentDelegate()?.replaceText(path, expected, content, reason)
        ?: WorkspaceDocumentMutationResult.NotLive

    override suspend fun validatePersistedMutation(mutation: FileRepository.Mutation) {
        currentDelegate()?.validatePersistedMutation(mutation)
    }

    override suspend fun persistedMutation(mutation: FileRepository.Mutation): WorkspacePersistedReconciliation =
        currentDelegate()?.persistedMutation(mutation) ?: WorkspacePersistedReconciliation()

    override suspend fun reconcilePersistedWorkspace(): WorkspacePersistedReconciliation =
        currentDelegate()?.reconcilePersistedWorkspace() ?: WorkspacePersistedReconciliation()

    private fun currentDelegate(): WorkspaceDocumentAuthority? = synchronized(lock) { delegate }

    private fun unbind(bindingGeneration: Long) = synchronized(lock) {
        if (generation == bindingGeneration) delegate = null
    }
}
