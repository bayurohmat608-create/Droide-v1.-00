package com.baystudio.droide.core

import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

 
object ProcessIo {
    fun readTextBounded(input: InputStream, maxChars: Int): String {
        require(maxChars in 1..1_000_000) { "Invalid output cap" }
        val out = StringBuilder(minOf(maxChars, 16_384))
        InputStreamReader(input, Charsets.UTF_8).use { reader ->
            val buf = CharArray(4_096)
            while (true) {
                val n = reader.read(buf)
                if (n <= 0) break
                out.append(buf, 0, n)
                if (out.length > maxChars) out.delete(0, out.length - maxChars)
            }
        }
        return out.toString()
    }
     
    fun terminate(process: Process?, graceMs: Long = 350L) {
        val p = process ?: return
        if (!p.isAlive) return
        runCatching { p.destroy() }
        val exited = runCatching { p.waitFor(graceMs.coerceIn(0L, 2_000L), TimeUnit.MILLISECONDS) }.getOrDefault(false)
        if (!exited && p.isAlive) {
            runCatching { p.destroyForcibly() }
            runCatching { p.waitFor(350L, TimeUnit.MILLISECONDS) }
        }
    }

}
