package com.baystudio.droide.core

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ApkEntryInfo(
    val path: String,
    val compressedBytes: Long,
    val uncompressedBytes: Long,
    val method: Int,
)

data class ApkCategory(
    val name: String,
    val files: Int,
    val compressedBytes: Long,
    val uncompressedBytes: Long,
)

data class ApkAnalysis(
    val file: File,
    val archiveBytes: Long,
    val entries: Int,
    val dexFiles: Int,
    val nativeLibraries: Int,
    val signingEntries: Int,
    val categories: List<ApkCategory>,
    val largestEntries: List<ApkEntryInfo>,
)


object ApkAnalyzer {
    suspend fun analyze(file: File): ApkAnalysis = withContext(Dispatchers.IO) {
        require(file.isFile && file.extension.equals("apk", ignoreCase = true)) { "Select a local APK artifact" }
        require(file.length() in 1..MAX_APK_BYTES) { "APK size is outside the supported analysis range" }
        val entries = ArrayList<ApkEntryInfo>()
        ZipFile(file).use { zip ->
            val iterator = zip.entries()
            while (iterator.hasMoreElements()) {
                val entry = iterator.nextElement()
                if (entry.isDirectory) continue
                val compressed = entry.compressedSize.coerceAtLeast(0L)
                val uncompressed = entry.size.coerceAtLeast(0L)
                entries += ApkEntryInfo(entry.name.take(MAX_ENTRY_NAME), compressed, uncompressed, entry.method)
                require(entries.size <= MAX_ENTRIES) { "APK contains too many entries" }
            }
        }
        val grouped = entries.groupBy { categoryFor(it.path) }.map { (name, values) ->
            ApkCategory(name, values.size, values.sumOf { it.compressedBytes }, values.sumOf { it.uncompressedBytes })
        }.sortedByDescending { it.uncompressedBytes }
        ApkAnalysis(
            file = file,
            archiveBytes = file.length(),
            entries = entries.size,
            dexFiles = entries.count { it.path.matches(Regex("classes(?:\\d+)?\\.dex")) },
            nativeLibraries = entries.count { it.path.startsWith("lib/") && it.path.endsWith(".so") },
            signingEntries = entries.count { it.path.startsWith("META-INF/") && (it.path.endsWith(".RSA", true) || it.path.endsWith(".DSA", true) || it.path.endsWith(".EC", true) || it.path.endsWith(".SF", true)) },
            categories = grouped,
            largestEntries = entries.sortedByDescending { it.uncompressedBytes }.take(30),
        )
    }

    private fun categoryFor(path: String): String = when {
        path == "AndroidManifest.xml" -> "Manifest"
        path.matches(Regex("classes(?:\\d+)?\\.dex")) -> "DEX"
        path == "resources.arsc" || path.startsWith("res/") -> "Resources"
        path.startsWith("assets/") -> "Assets"
        path.startsWith("lib/") -> "Native libraries"
        path.startsWith("META-INF/") -> "Signing / META-INF"
        else -> "Other"
    }

    const val STORED = ZipEntry.STORED
    private const val MAX_ENTRY_NAME = 500
    private const val MAX_ENTRIES = 250_000
    private const val MAX_APK_BYTES = 8L * 1024 * 1024 * 1024
}
