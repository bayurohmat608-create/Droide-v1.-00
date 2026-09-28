package com.baystudio.droide.core

import com.flyfishxu.kadb.stream.AdbStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext








class AndroidJdwpTransport(private val bridge: DeviceBridgeManager) {
    data class ProbeResult(val pid: Int, val handshakeAccepted: Boolean)

    class Connection internal constructor(private val stream: AdbStream) : Closeable {
        val input: InputStream = stream.source.inputStream()
        val output: OutputStream = stream.sink.outputStream()

        override fun close() {
            runCatching { output.close() }
            runCatching { input.close() }
            runCatching { stream.close() }
        }
    }

    fun connect(pid: Int): Connection = Connection(bridge.openJdwp(pid))

     
    suspend fun probe(pid: Int): ProbeResult = withContext(Dispatchers.IO) {
        connect(pid).use { connection ->
            connection.output.write(HANDSHAKE)
            connection.output.flush()
            val reply = ByteArray(HANDSHAKE.size)
            var offset = 0
            while (offset < reply.size) {
                val read = connection.input.read(reply, offset, reply.size - offset)
                if (read < 0) break
                offset += read
            }
            ProbeResult(pid, offset == HANDSHAKE.size && reply.contentEquals(HANDSHAKE))
        }
    }

    companion object {
        private val HANDSHAKE = "JDWP-Handshake".encodeToByteArray()
    }
}
