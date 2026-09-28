package com.baystudio.droide.core

import java.io.File









object ProjectWorkspacePolicy {
    fun projectsRoot(appFilesDir: File): File = File(appFilesDir, "projects").canonicalFile

    fun defaultRoot(projectsRoot: File, projectId: String): File =
        requireDirectChild(projectsRoot, File(projectsRoot, PathSecurity.safeLeafName(projectId)))

    fun requireDirectChild(projectsRoot: File, candidate: File): File {
        val root = projectsRoot.canonicalFile
        val child = candidate.canonicalFile
        require(child != root) { "Project workspace must not be the projects container" }
        require(PathSecurity.contains(root, child)) { "Project workspace escaped app-private project root" }
        require(child.parentFile?.canonicalFile == root) { "Project workspace must be one direct child of the projects container" }
        return child
    }

     
    fun restoreRoot(projectsRoot: File, projectId: String, storedRoot: String?): File {
        val fallback = defaultRoot(projectsRoot, projectId)
        val restored = storedRoot?.let { value ->
            runCatching { requireDirectChild(projectsRoot, File(value)) }.getOrNull()
        }
        return restored ?: fallback
    }
}
