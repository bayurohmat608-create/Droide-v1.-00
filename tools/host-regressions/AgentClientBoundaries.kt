package com.baystudio.droide.core

import java.io.File


class FileRepository(val root: File) {
    suspend fun delete(path: String): Boolean = PathSecurity.resolveWithin(root, path).delete()
    suspend fun writeText(path: String, text: String) { PathSecurity.resolveWithin(root, path).apply { parentFile.mkdirs(); writeText(text) } }
    suspend fun readTextForEdit(path: String, maxBytes: Long): String {
        val file = PathSecurity.resolveWithin(root, path); require(file.length() <= maxBytes); return file.readText()
    }
}
class AndroidDevelopmentManager { suspend fun prepareInteractiveShell(syncProject: Boolean): Unit = error("ADB is outside fixture") }
enum class PermEffect { ALLOW, DENY, ASK }
class PermissionEngine { var effect = PermEffect.ASK; fun decide(action: String, resource: String, scope: String) = effect }
enum class ApprovalDecision { Approved, Denied }
class ApprovalManager {
    var handler: suspend () -> ApprovalDecision = { ApprovalDecision.Approved }
    fun clearSessionApprovals(sessionId: String, permissionScope: String) = Unit
    suspend fun requestApproval(kind: String, summary: String, detail: String, action: String, resource: String,
        permissionScope: String, provenance: AgentRequestProvenance, approvalSessionId: String,
        sessionGrantAction: String, sessionGrantResource: String): ApprovalDecision = handler()
}
class AgentPluginSource { companion object { val EMPTY = AgentPluginSource() } }
data class McpServer(val name: String, val argv: List<String>)
class McpManager(root: File, source: AgentPluginSource) { fun list(): List<McpServer> = emptyList() }
