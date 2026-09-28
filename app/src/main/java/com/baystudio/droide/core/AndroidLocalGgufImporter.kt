package com.baystudio.droide.core

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

 
class AndroidLocalGgufImporter(private val context: Context) {
    private val store = LocalGgufModelStore(LocalGgufAndroidStorage.root(context))

    suspend fun import(
        uri: Uri,
        checkCancelled: () -> Unit = {},
        onProgress: (copiedBytes: Long, declaredSizeBytes: Long?) -> Unit = { _, _ -> },
    ): LocalGgufModelStore.ImportedModel = withContext(Dispatchers.IO) {
        require(uri.scheme.equals("content", ignoreCase = true)) { "Local GGUF import requires a SAF content URI" }
        val metadata = queryMetadata(uri)
        context.contentResolver.openInputStream(uri)?.use { input ->
            store.import(
                input = input,
                displayName = metadata.first,
                declaredSizeBytes = metadata.second,
                checkCancelled = checkCancelled,
                onProgress = onProgress,
            )
        } ?: error("Could not open selected GGUF document")
    }

    suspend fun listStored(): List<LocalGgufModelStore.StoredModel> = withContext(Dispatchers.IO) {
        store.listStored()
    }

    suspend fun verifyIntegrity(modelId: String, checkCancelled: () -> Unit = {}): Boolean = withContext(Dispatchers.IO) {
        store.verifyIntegrity(modelId, checkCancelled)
    }

    private fun queryMetadata(uri: Uri): Pair<String, Long?> {
        var displayName: String? = null
        var size: Long? = null
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) displayName = cursor.getString(nameIndex)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex).takeIf { it > 0 }
            }
        }
        return (displayName?.takeIf { it.isNotBlank() } ?: "local-model.gguf") to size
    }
}
