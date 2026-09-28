package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

 
data class Artifact(val id: String, val type: String, val content: String, val file: File)

class ArtifactManager(private val workDir: File) {
    private val dir = PathSecurity.resolveWithin(workDir, ".droide/artifacts").apply { mkdirs() }

    fun create(type: String, content: String): Artifact {
        require(content.toByteArray(Charsets.UTF_8).size <= MAX_ARTIFACT_BYTES) { "Artifact content is too large" }
        val safeType = type.trim().ifBlank { "artifact" }.take(80).replace(Regex("[\\r\\n]"), " ")
        val id = "a-${UUID.randomUUID()}"
        val target = PathSecurity.resolveWithin(dir, "$id.md")
        val encoded = "# $safeType\n\n$content"
        val tmp = File(dir, ".${target.name}.tmp-${System.nanoTime()}")
        try {
            tmp.writeText(encoded)
            runCatching {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            }.getOrElse {
                Files.move(tmp.toPath(), target.toPath())
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
        return Artifact(id, safeType, content, target)
    }

    companion object {
        private const val MAX_ARTIFACT_BYTES = 500_000
    }
}
