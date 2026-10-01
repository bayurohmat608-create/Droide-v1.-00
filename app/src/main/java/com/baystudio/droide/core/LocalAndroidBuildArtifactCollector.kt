package com.baystudio.droide.core

import java.io.File


internal object LocalAndroidBuildArtifactCollector {
    fun collect(projectRoot: File, task: String, completedOutputRoots: List<File>): List<File> {
        val query = AndroidBuildArtifactPolicy.queryFor(task) ?: return emptyList()
        val root = projectRoot.canonicalFile
        require(root.isDirectory && !PathSecurity.isSymbolicLink(root)) { "Invalid local Android workspace" }
        require(completedOutputRoots.size <= 2048) { "Too many Gradle output roots" }
        val admittedRoots = completedOutputRoots.map { output ->
            require(!PathSecurity.isSymbolicLink(output) && PathSecurity.contains(root, output)) { "Gradle output escaped workspace" }
            output.canonicalFile
        }
        val searchRoot = query.modulePath?.let { File(root, it) } ?: root
        val canonicalSearch = searchRoot.canonicalFile
        require(PathSecurity.contains(root, canonicalSearch) && canonicalSearch.isDirectory) { "Android module escaped workspace" }
        val matches = mutableListOf<File>()
        canonicalSearch.walkTopDown()
            .onEnter { dir ->
                dir == canonicalSearch || (dir.name !in SKIP_DIRS && dir.depthFrom(canonicalSearch) <= MAX_DEPTH && PathSecurity.canDescend(root, dir))
            }
            .forEach { file ->
                if (!file.isFile || PathSecurity.isSymbolicLink(file)) return@forEach
                val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return@forEach
                if (!PathSecurity.contains(root, canonical)) return@forEach
                val relative = canonical.relativeTo(root).invariantSeparatorsPath
                if (AndroidBuildArtifactPolicy.matchesRelativePath(query, relative)) {
                    // UP-TO-DATE/FROM-CACHE outputs need not have a fresh mtime. Only this
                    // invocation's successful task output declarations admit an existing file.
                    if (admittedRoots.none { output -> canonical == output || (output.isDirectory && PathSecurity.contains(output, canonical)) }) return@forEach
                    require(canonical.length() > 0L) { "Build artifact is empty: $relative" }
                    matches += canonical
                    require(matches.size <= MAX_ARTIFACTS) { "Too many build artifacts; narrow the Gradle task" }
                }
            }
        return matches.distinctBy(File::getAbsolutePath)
    }

    private fun File.depthFrom(root: File): Int = relativeTo(root).toPath().nameCount
    private val SKIP_DIRS = setOf(".git", ".gradle", ".idea", "node_modules", ".droide")
    private const val MAX_DEPTH = 12
    private const val MAX_ARTIFACTS = 40
}
