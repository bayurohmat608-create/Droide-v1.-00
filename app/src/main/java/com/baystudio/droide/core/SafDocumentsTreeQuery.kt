package com.baystudio.droide.core

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

internal data class SafDocumentNode(
    val documentId: String,
    val uri: Uri,
    val name: String,
    val mimeType: String,
    val size: Long,
    val modified: Long,
) {
    val isDirectory: Boolean get() = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
    val isFile: Boolean get() = !isDirectory
}

internal class SafDirectQueryException(cause: Throwable? = null) : Exception(cause)

 
internal class SafDocumentsTreeQuery(
    private val context: Context,
    private val treeUri: Uri,
) {
    fun rootDocumentId(): String = try {
        DocumentsContract.getTreeDocumentId(treeUri)
    } catch (t: Exception) {
        throw SafDirectQueryException(t)
    }

    fun listChildren(parentDocumentId: String): List<SafDocumentNode> {
        val childUri = try {
            DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        } catch (t: Exception) {
            throw SafDirectQueryException(t)
        }
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val cursor = try {
            context.contentResolver.query(childUri, projection, null, null, null)
        } catch (t: Exception) {
            throw SafDirectQueryException(t)
        } ?: throw SafDirectQueryException()

        return cursor.use { c ->
            val idIndex = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIndex = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeIndex = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val modifiedIndex = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            if (idIndex < 0 || nameIndex < 0 || mimeIndex < 0) throw SafDirectQueryException()
            val out = ArrayList<SafDocumentNode>(c.count.coerceAtMost(256))
            while (c.moveToNext()) {
                val documentId = c.getString(idIndex) ?: throw SafDirectQueryException()
                val rawName = c.getString(nameIndex) ?: throw IllegalStateException("SAF contains an unnamed entry")
                val safeName = try {
                    PathSecurity.safeLeafName(rawName)
                } catch (t: Throwable) {
                    throw IllegalStateException("Unsupported SAF filename '$rawName'", t)
                }
                val mime = c.getString(mimeIndex) ?: "application/octet-stream"
                val size = if (sizeIndex >= 0 && !c.isNull(sizeIndex)) c.getLong(sizeIndex).coerceAtLeast(0L) else -1L
                val modified = if (modifiedIndex >= 0 && !c.isNull(modifiedIndex)) c.getLong(modifiedIndex).coerceAtLeast(0L) else 0L
                out += SafDocumentNode(
                    documentId = documentId,
                    uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId),
                    name = safeName,
                    mimeType = mime,
                    size = size,
                    modified = modified,
                )
            }
            out
        }
    }
}
