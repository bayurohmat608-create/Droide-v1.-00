package com.baystudio.droide.core

 
object ApprovalPolicyGuard {
    fun remainsPermitted(
        permissions: PermissionEngine,
        action: String,
        resource: String,
        permissionScope: String,
    ): Boolean = permissions.decide(action, resource, permissionScope) != PermEffect.DENY
}
