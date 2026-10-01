package com.baystudio.droide.core

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.channels.Channel

// Only the newest pending geometry should cross a remote PTY boundary, and one consumer must apply those updates serially.






internal class TerminalResizeAuthority(
    columns: Int = DEFAULT_COLUMNS,
    rows: Int = DEFAULT_ROWS,
) {
    data class Geometry(val columns: Int, val rows: Int)

    private val latest = AtomicReference(normalize(columns, rows))
    private val updates = Channel<Geometry>(Channel.CONFLATED)

    fun latest(): Geometry = latest.get()

     
    fun offer(columns: Int, rows: Int): Boolean {
        val next = normalize(columns, rows)
        while (true) {
            val previous = latest.get()
            if (previous == next) return false
            if (latest.compareAndSet(previous, next)) {
                updates.trySend(next)
                return true
            }
        }
    }

     
    fun signalLatest() {
        updates.trySend(latest.get())
    }

    suspend fun receive(): Geometry = updates.receive()

    fun close() {
        updates.close()
    }

    companion object {
        const val DEFAULT_COLUMNS = 120
        const val DEFAULT_ROWS = 32
        const val MIN_COLUMNS = 2
        const val MAX_COLUMNS = 500
        const val MIN_ROWS = 2
        const val MAX_ROWS = 300

        fun normalize(columns: Int, rows: Int): Geometry = Geometry(
            columns = columns.coerceIn(MIN_COLUMNS, MAX_COLUMNS),
            rows = rows.coerceIn(MIN_ROWS, MAX_ROWS),
        )
    }
}
