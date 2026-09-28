package com.baystudio.droide.core

 
data class FileQueryPage(
    val paths: List<String>,
    val scannedEntries: Int,
    val scanLimitReached: Boolean,
    val resultLimitReached: Boolean,
)

data class TextSearchHit(val path: String, val line: Int, val column: Int, val excerpt: String)

data class TextSearchOptions(
    val caseSensitive: Boolean = false,
    val wholeWord: Boolean = false,
    val includeGlob: String = "",
)

data class TextSearchPage(
    val hits: List<TextSearchHit>,
    val scannedEntries: Int,
    val scanLimitReached: Boolean,
    val resultLimitReached: Boolean,
    val unreadableFiles: Int,
    val skippedLargeFiles: Int = 0,
    val skippedDirtyBuffers: Int = 0,
)
