package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

// Keep activation atomic so rollback remains safe.
internal class LongRunningOperationJournal(
    root: File,
    workspaceId: String,
    private val currentPid: Int,
    private val currentProcessIdentity: String = "pid-$currentPid",
    private val clock: () -> Long = System::currentTimeMillis,
) {
    enum class Kind { BUILD, PACKAGE, VM }
    enum class State { RUNNING, COMPLETED, FAILED, CANCELED, INTERRUPTED }

    data class Record(
        val id: String,
        val kind: Kind,
        val state: State,
        val ownerPid: Int,
        val ownerProcessIdentity: String,
        val startedAtEpochMs: Long,
        val updatedAtEpochMs: Long,
        val label: String,
        val detail: String = "",
    )

    inner class Lease internal constructor(val id: String) {
        fun complete(detail: String = "") = finish(id, State.COMPLETED, detail)
        fun fail(detail: String = "") = finish(id, State.FAILED, detail)
        fun cancel(detail: String = "") = finish(id, State.CANCELED, detail)
    }

    private val directory = File(root, safeWorkspace(workspaceId)).apply { mkdirs() }

    init {
        require(currentPid > 0)
        requireProcessIdentity(currentProcessIdentity)
    }

    @Synchronized
    fun begin(kind: Kind, label: String): Lease {
        reconcileInterrupted()
        requireLabel(label)
        val now = clock()
        val id = UUID.randomUUID().toString().replace("-", "").take(24)
        write(Record(id, kind, State.RUNNING, currentPid, currentProcessIdentity, now, now, label))
        prune()
        return Lease(id)
    }

    @Synchronized
    fun reconcileInterrupted(): List<Record> {
        val interrupted = mutableListOf<Record>()
        list().filter { it.state == State.RUNNING && it.ownerProcessIdentity != currentProcessIdentity }.forEach { stale ->
            val next = stale.copy(
                state = State.INTERRUPTED,
                updatedAtEpochMs = clock(),
                detail = "Owning Android process ended before the operation committed.",
            )
            write(next)
            interrupted += next
        }
        prune()
        return interrupted
    }

    @Synchronized
    fun list(): List<Record> = directory.listFiles { file -> file.name.endsWith(".state") }
        .orEmpty()
        .mapNotNull(::read)
        .sortedByDescending { it.updatedAtEpochMs }

    @Synchronized
    private fun finish(id: String, state: State, detail: String) {
        require(state != State.RUNNING)
        requireDetail(detail)
        val file = stateFile(id)
        val current = read(file) ?: return
        if (current.state != State.RUNNING) return
        write(current.copy(state = state, updatedAtEpochMs = clock(), detail = detail))
        prune()
    }

    private fun write(record: Record) {
        validate(record)
        directory.mkdirs()
        check(directory.isDirectory && !PathSecurity.isSymbolicLink(directory)) { "Operation journal directory is unavailable" }
        val target = stateFile(record.id)
        val temp = File(directory, ".${record.id}.${UUID.randomUUID().toString().take(8)}.tmp")
        check(!PathSecurity.isSymbolicLink(target) && !PathSecurity.isSymbolicLink(temp)) { "Unsafe operation journal path" }
        val text = buildString {
            append("schema=2\n")
            append("id=").append(record.id).append('\n')
            append("kind=").append(record.kind.name).append('\n')
            append("state=").append(record.state.name).append('\n')
            append("ownerPid=").append(record.ownerPid).append('\n')
            append("ownerProcessIdentity=").append(escape(record.ownerProcessIdentity)).append('\n')
            append("startedAt=").append(record.startedAtEpochMs).append('\n')
            append("updatedAt=").append(record.updatedAtEpochMs).append('\n')
            append("label=").append(escape(record.label)).append('\n')
            append("detail=").append(escape(record.detail)).append('\n')
        }
        try {
            temp.outputStream().use { output ->
                output.write(text.toByteArray(Charsets.UTF_8))
                output.flush()
                (output as? java.io.FileOutputStream)?.fd?.sync()
            }
            try {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: Exception) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private fun read(file: File): Record? = runCatching {
        if (!file.isFile || PathSecurity.isSymbolicLink(file) || file.length() !in 1..16_384) return@runCatching null
        val values = linkedMapOf<String, String>()
        file.readLines(Charsets.UTF_8).forEach { line ->
            val split = line.indexOf('=')
            if (split > 0) values[line.substring(0, split)] = line.substring(split + 1)
        }
        val schema = values["schema"] ?: return@runCatching null
        if (schema != "1" && schema != "2") return@runCatching null
        val ownerPid = values.getValue("ownerPid").toInt()
        val record = Record(
            id = values.getValue("id"),
            kind = Kind.valueOf(values.getValue("kind")),
            state = State.valueOf(values.getValue("state")),
            ownerPid = ownerPid,
            ownerProcessIdentity = if (schema == "2") {
                unescape(values.getValue("ownerProcessIdentity"))
            } else {
                // Schema 1 knew only PID. Never let PID reuse make an old RUNNING operation look live.
                "legacy-pid-$ownerPid"
            },
            startedAtEpochMs = values.getValue("startedAt").toLong(),
            updatedAtEpochMs = values.getValue("updatedAt").toLong(),
            label = unescape(values.getValue("label")),
            detail = unescape(values["detail"].orEmpty()),
        )
        validate(record)
        check(file.name == "${record.id}.state")
        record
    }.getOrNull()

    private fun prune() {
        val finished = list().filter { it.state != State.RUNNING }.drop(MAX_HISTORY)
        finished.forEach { stateFile(it.id).delete() }
    }

    private fun stateFile(id: String): File {
        require(id.matches(Regex("[0-9a-f]{24}"))) { "Invalid operation id" }
        return File(directory, "$id.state")
    }

    private fun validate(record: Record) {
        require(record.id.matches(Regex("[0-9a-f]{24}")))
        require(record.ownerPid > 0)
        requireProcessIdentity(record.ownerProcessIdentity)
        require(record.startedAtEpochMs > 0 && record.updatedAtEpochMs >= record.startedAtEpochMs)
        requireLabel(record.label)
        requireDetail(record.detail)
    }

    private fun requireProcessIdentity(value: String) = require(value.length in 1..128 && value.none { it == '\u0000' || it == '\n' || it == '\r' || it == '=' })
    private fun requireLabel(value: String) = require(value.length in 1..160 && value.none { it == '\u0000' || it == '\n' || it == '\r' })
    private fun requireDetail(value: String) = require(value.length <= 400 && value.none { it == '\u0000' || it == '\n' || it == '\r' })

    private fun escape(value: String): String = value.replace("%", "%25").replace("=", "%3D")
    private fun unescape(value: String): String = value.replace("%3D", "=").replace("%25", "%")

    private fun safeWorkspace(value: String): String {
        val safe = value.lowercase().replace(Regex("[^a-z0-9._-]"), "-").trim('-').take(80)
        require(safe.matches(Regex("[a-z0-9][a-z0-9._-]{0,79}"))) { "Invalid operation-journal workspace" }
        return safe
    }

    private companion object { const val MAX_HISTORY = 48 }
}
