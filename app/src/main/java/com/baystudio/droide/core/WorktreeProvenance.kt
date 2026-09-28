package com.baystudio.droide.core

import java.io.File
import java.security.MessageDigest








object WorktreeProvenance {
    fun repositoryIdentity(workTree: File, gitDirectory: File): String {
        val payload = buildString {
            append("droide-repository-v1\u0000")
            append(workTree.canonicalFile.path)
            append('\u0000')
            append(gitDirectory.canonicalFile.path)
        }.toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256")
            .digest(payload)
            .joinToString(separator = "") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

     
    fun validateMergeBinding(
        expectedParentBranch: String,
        currentParentBranch: String,
        expectedParentRepositoryIdentity: String,
        currentParentRepositoryIdentity: String,
        expectedChildBranch: String,
        currentChildBranch: String,
    ): String? {
        if (expectedParentBranch.isBlank() || expectedParentRepositoryIdentity.isBlank() || expectedChildBranch.isBlank()) {
            return "Merge metadata is missing for this isolated workspace. Merge was blocked for safety."
        }
        if (currentParentRepositoryIdentity != expectedParentRepositoryIdentity) {
            return "Parent repository identity changed while the child was running; refusing to merge into a different checkout."
        }
        if (currentParentBranch != expectedParentBranch) {
            return "Parent branch changed while the child was running (expected '$expectedParentBranch', current '$currentParentBranch'); switch back to the original branch before merge."
        }
        if (currentChildBranch != expectedChildBranch) {
            return "Child branch changed from its isolated branch (expected '$expectedChildBranch', current '$currentChildBranch'); refusing unsafe merge."
        }
        return null
    }
}
