package com.baystudio.droide.core

import java.io.File
import java.util.UUID


internal object LocalAndroidBuildEvidence {
    data class Session(val directory: File, val initScript: File, val manifest: File)
    private const val MAX_BYTES = 512L * 1024L
    private const val MAX_ROOTS = 2048

    fun prepare(projectRoot: File): Session {
        val root = projectRoot.canonicalFile
        val directory = PathSecurity.resolveWithin(root, ".droide/build-evidence/${UUID.randomUUID()}")
        check(directory.mkdirs() && !PathSecurity.isSymbolicLink(directory)) { "Cannot prepare Gradle artifact evidence" }
        return try {
            val init = File(directory, "outputs.init.gradle")
            val manifest = File(directory, "outputs.tsv")
            manifest.writeText("", Charsets.UTF_8)
            init.writeText(script(root, manifest), Charsets.UTF_8)
            Session(directory, init, manifest)
        } catch (failure: Throwable) {
            PathSecurity.deleteTreeNoFollow(directory)
            throw failure
        }
    }

    fun readOutputRoots(projectRoot: File, manifest: File): List<File> {
        val root = projectRoot.canonicalFile
        require(PathSecurity.contains(root, manifest) && manifest.isFile && !PathSecurity.isSymbolicLink(manifest)) { "Invalid Gradle artifact evidence" }
        require(manifest.length() <= MAX_BYTES) { "Gradle artifact evidence exceeds size limit" }
        val result = linkedSetOf<File>()
        manifest.bufferedReader(Charsets.UTF_8).useLines { lines ->
            var count = 0
            lines.forEach { line ->
                require(++count <= MAX_ROOTS) { "Too many Gradle artifact output roots" }
                val columns = line.split('\t')
                require(columns.size == 2 && columns[0] in setOf("EXECUTED", "UP-TO-DATE", "FROM-CACHE")) { "Invalid Gradle task outcome" }
                val path = columns[1]
                require(path.length in 1..4096 && path.none { it.code < 0x20 || it.code == 0x7f }) { "Invalid Gradle output path" }
                val file = File(path)
                require(file.isAbsolute && !PathSecurity.isSymbolicLink(file)) { "Unsafe Gradle output root" }
                val canonical = file.canonicalFile
                require(PathSecurity.contains(root, canonical)) { "Gradle output escaped workspace" }
                if (canonical.exists()) result += canonical
            }
        }
        return result.toList()
    }

    private fun groovyQuote(value: String): String = "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"

    private fun script(root: File, manifest: File): String = """
        def droideRoot = new File(${groovyQuote(root.path)}).canonicalFile.toPath()
        def droideManifest = new File(${groovyQuote(manifest.path)})
        def droideOutputLock = new Object()
        def droideSeenOutputs = new HashSet<String>()
        gradle.taskGraph.afterTask { task, state ->
            if (!state.executed || state.failure != null || state.noSource) return
            def outcome = state.upToDate ? 'UP-TO-DATE' : (state.skipMessage == 'FROM-CACHE' ? 'FROM-CACHE' : 'EXECUTED')
            if (state.skipped && outcome == 'EXECUTED') return
            task.outputs.files.files.each { output ->
                def canonical = output.canonicalFile
                def outputPath = canonical.toPath()
                if (!outputPath.startsWith(droideRoot) || !canonical.exists()) return
                def relative = droideRoot.relativize(outputPath).toString().replace('\\', '/')
                if (!(relative == 'build/outputs' || relative.startsWith('build/outputs/') || relative.endsWith('/build/outputs') || relative.contains('/build/outputs/'))) return
                def path = canonical.path
                if (path.length() > 4096 || path.toCharArray().any { c -> ((int) c) < 32 || ((int) c) == 127 }) throw new GradleException('Unsafe Droide output path')
                synchronized (droideOutputLock) {
                    def row = outcome + '\t' + path + '\n'
                    if (droideSeenOutputs.add(row)) {
                        if (droideSeenOutputs.size() > $MAX_ROOTS || droideManifest.length() + row.getBytes('UTF-8').length > $MAX_BYTES) {
                            throw new GradleException('Droide artifact evidence exceeded its limit')
                        }
                        droideManifest.append(row, 'UTF-8')
                    }
                }
            }
        }
    """.trimIndent() + "\n"
}
