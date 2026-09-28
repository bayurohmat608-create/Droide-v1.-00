package com.baystudio.droide.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material.icons.filled.TravelExplore
import com.baystudio.droide.core.AgentMode
import com.baystudio.droide.core.AgentService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class AgentAttachedContext(val name: String, val text: String)

internal suspend fun readAgentTextAttachment(context: Context, uri: Uri): AgentAttachedContext = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }?.take(120) ?: "context.txt"
    val text = resolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
        val out = StringBuilder()
        val buffer = CharArray(2_048)
        while (out.length < 12_000) {
            val read = reader.read(buffer, 0, minOf(buffer.size, 12_000 - out.length))
            if (read <= 0) break
            out.append(buffer, 0, read)
        }
        out.toString()
    } ?: error("Unable to read attachment")
    AgentAttachedContext(name, text)
}

internal fun composeAgentPrompt(userText: String, attachments: List<AgentAttachedContext>): String {
    if (attachments.isEmpty()) return userText.take(AgentService.MAX_USER_MESSAGE_CHARS)
    return buildString {
        append(userText.ifBlank { "Use the attached context for this request." })
        append("\n\n[Attached context]\n")
        attachments.forEach { attachment ->
            append("\n--- ").append(attachment.name).append(" ---\n")
            append(attachment.text)
            append('\n')
        }
    }.take(AgentService.MAX_USER_MESSAGE_CHARS)
}

internal fun agentModeLabel(mode: AgentMode): String = when (mode) {
    AgentMode.BUILD -> "Build"
    AgentMode.PLAN -> "Plan"
    AgentMode.REVIEW -> "Review"
    AgentMode.EXPLORE -> "Explore"
}

internal fun agentModeHint(mode: AgentMode): String = when (mode) {
    AgentMode.BUILD -> "Implement and verify with permission-gated tools"
    AgentMode.PLAN -> "Read-only investigation and implementation planning"
    AgentMode.REVIEW -> "Read-only code review focused on concrete findings"
    AgentMode.EXPLORE -> "Internal read-only exploration mode"
}

internal fun agentModeIcon(mode: AgentMode) = when (mode) {
    AgentMode.BUILD -> Icons.Default.Build
    AgentMode.PLAN -> Icons.Default.AccountTree
    AgentMode.REVIEW -> Icons.Default.RateReview
    AgentMode.EXPLORE -> Icons.Default.TravelExplore
}
