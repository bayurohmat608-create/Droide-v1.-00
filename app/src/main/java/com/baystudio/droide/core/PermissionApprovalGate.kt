package com.baystudio.droide.core

internal const val EXTERNAL_ACP_PERMISSION_SCOPE = "external-acp-agent"

 
object PermissionApprovalGate {
    suspend fun approved(
        permissions: PermissionEngine,
        approvals: ApprovalManager,
        action: String,
        resource: String,
        permissionScope: String,
        kind: String,
        summary: String,
        detail: String,
        provenance: AgentRequestProvenance,
        approvalSessionId: String = provenance.sessionId,
        sessionGrantAction: String = action,
        sessionGrantResource: String = resource,
        preapproved: Boolean = false,
    ): Boolean = when (permissions.decide(action, resource, permissionScope)) {
        PermEffect.ALLOW -> true
        PermEffect.DENY -> false
        PermEffect.ASK -> if (preapproved) true else {
            val decision = approvals.requestApproval(
                kind = kind,
                summary = summary,
                detail = detail,
                action = action,
                resource = resource,
                permissionScope = permissionScope,
                provenance = provenance,
                approvalSessionId = approvalSessionId,
                sessionGrantAction = sessionGrantAction,
                sessionGrantResource = sessionGrantResource,
            )
            decision == ApprovalDecision.Approved &&
                ApprovalPolicyGuard.remainsPermitted(permissions, action, resource, permissionScope)
        }
    }
}
