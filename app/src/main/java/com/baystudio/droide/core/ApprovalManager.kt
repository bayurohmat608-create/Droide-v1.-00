package com.baystudio.droide.core

import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

 
data class ApprovalRequest(
    val id: Long,
    val kind: String, 
    val summary: String,
    val detail: String, 
    val action: String = "",
    val resource: String = "",
    val permissionScope: String = "",
    val provenance: AgentRequestProvenance = AgentRequestProvenance.primary(),
     
    val approvalSessionId: String = "",
     
    val sessionGrantAction: String = "",
    val sessionGrantResource: String = "",
) {
    val canAllowForSession: Boolean get() =
        approvalSessionId.isNotBlank() && permissionScope.isNotBlank() &&
            sessionGrantAction.isNotBlank() && sessionGrantResource.isNotBlank()
}

sealed interface ApprovalDecision { data object Approved : ApprovalDecision; data object Denied : ApprovalDecision }
enum class ApprovalResolution { DENY, ALLOW_ONCE, ALLOW_SESSION }


class ApprovalManager {
    private data class SessionGrant(
        val sessionId: String,
        val permissionScope: String,
        val action: String,
        val resource: String,
    )

    private var nextId = 1L
    private val _pending = MutableStateFlow<ApprovalRequest?>(null)
    val pending: StateFlow<ApprovalRequest?> = _pending.asStateFlow()

    private val conts = mutableMapOf<Long, kotlin.coroutines.Continuation<ApprovalDecision>>()
    private val sessionGrants = LinkedHashSet<SessionGrant>()
    private val requestMutex = Mutex()
    private val stateLock = Any()

    suspend fun requestApproval(
        kind: String,
        summary: String,
        detail: String,
        action: String = "",
        resource: String = "",
        permissionScope: String = "",
        provenance: AgentRequestProvenance = AgentRequestProvenance.primary(),
        approvalSessionId: String = provenance.sessionId,
        sessionGrantAction: String = action,
        sessionGrantResource: String = resource,
    ): ApprovalDecision = requestMutex.withLock {
        val scope = permissionScope.takeIf { it.isNotBlank() }
            ?.let(PermissionPolicyDocument::normalizeAgentId).orEmpty()
        val sessionId = normalizeApprovalSessionId(approvalSessionId)
        val grantAction = normalizeGrantAction(sessionGrantAction)
        val grantResource = normalizeGrantResource(sessionGrantResource)
        if (sessionId.isNotBlank() && scope.isNotBlank() && grantAction.isNotBlank() && grantResource.isNotBlank() &&
            hasSessionApproval(sessionId, scope, grantAction, grantResource)
        ) {
            return@withLock ApprovalDecision.Approved
        }

        val req = ApprovalRequest(
            id = nextId++,
            kind = kind,
            summary = provenance.decorateSummary(summary),
            detail = detail.take(8_000),
            action = action,
            resource = resource,
            permissionScope = scope,
            provenance = provenance,
            approvalSessionId = sessionId,
            sessionGrantAction = grantAction,
            sessionGrantResource = grantResource,
        )
        kotlinx.coroutines.suspendCancellableCoroutine<ApprovalDecision> { cont ->
            synchronized(stateLock) {
                conts[req.id] = cont
                _pending.value = req
            }
            cont.invokeOnCancellation {
                synchronized(stateLock) {
                    conts.remove(req.id)
                    if (_pending.value?.id == req.id) _pending.value = null
                }
            }
        }
    }

    fun resolve(approved: Boolean) = resolve(if (approved) ApprovalResolution.ALLOW_ONCE else ApprovalResolution.DENY)

    fun resolve(resolution: ApprovalResolution) {
        val continuation = synchronized(stateLock) {
            val req = _pending.value ?: return
            _pending.value = null
            if (resolution == ApprovalResolution.ALLOW_SESSION && req.canAllowForSession) {
                addSessionGrantLocked(
                    SessionGrant(req.approvalSessionId, req.permissionScope, req.sessionGrantAction, req.sessionGrantResource)
                )
            }
            conts.remove(req.id)
        }
        continuation?.resumeWith(
            Result.success(if (resolution == ApprovalResolution.DENY) ApprovalDecision.Denied else ApprovalDecision.Approved)
        )
    }

    fun hasSessionApproval(sessionId: String, permissionScope: String, action: String, resource: String): Boolean {
        val sid = normalizeApprovalSessionId(sessionId)
        val scope = permissionScope.takeIf { it.isNotBlank() }
            ?.let(PermissionPolicyDocument::normalizeAgentId).orEmpty()
        val grantAction = normalizeGrantAction(action)
        val grantResource = normalizeGrantResource(resource)
        if (sid.isBlank() || scope.isBlank() || grantAction.isBlank() || grantResource.isBlank()) return false
        return synchronized(stateLock) { SessionGrant(sid, scope, grantAction, grantResource) in sessionGrants }
    }

     
    fun clearSessionApprovals(sessionId: String, permissionScope: String? = null) {
        val sid = normalizeApprovalSessionId(sessionId)
        if (sid.isBlank()) return
        val scope = permissionScope?.takeIf { it.isNotBlank() }?.let(PermissionPolicyDocument::normalizeAgentId)
        synchronized(stateLock) {
            sessionGrants.removeAll { it.sessionId == sid && (scope == null || it.permissionScope == scope) }
        }
    }

     
    fun clearAllSessionApprovals() = synchronized(stateLock) { sessionGrants.clear() }

    private fun addSessionGrantLocked(grant: SessionGrant) {
        sessionGrants.remove(grant)
        while (sessionGrants.size >= MAX_SESSION_GRANTS) {
            val oldest = sessionGrants.firstOrNull() ?: break
            sessionGrants.remove(oldest)
        }
        sessionGrants += grant
    }

    private fun normalizeApprovalSessionId(value: String): String {
        val normalized = value.trim()
        if (normalized.isBlank()) return ""
        if (normalized.length > 512 || '\u0000' in normalized || '\n' in normalized || '\r' in normalized) return ""
        return normalized
    }

    private fun normalizeGrantAction(value: String): String {
        val normalized = value.trim()
        if (normalized.isBlank()) return ""
        if (normalized.length > 128 || !normalized.matches(Regex("[A-Za-z0-9_.*?-]{1,128}"))) return ""
        return normalized
    }

    private fun normalizeGrantResource(value: String): String {
        val normalized = value.trim()
        if (normalized.isBlank()) return ""
        if (normalized.length > 4_096 || '\u0000' in normalized || '\n' in normalized || '\r' in normalized) return ""
        return normalized
    }

    private companion object { const val MAX_SESSION_GRANTS = 512 }
}
