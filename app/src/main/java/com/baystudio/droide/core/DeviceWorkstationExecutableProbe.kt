package com.baystudio.droide.core









object DeviceWorkstationExecutableProbe {
    private const val MAX_COMMANDS = 4_096
    private const val MAX_OUTPUT_BYTES = 768 * 1024
    private val commandName = Regex("[A-Za-z0-9._+-]{1,128}")

    suspend fun resolve(
        bridge: DeviceBridgeManager,
        config: AndroidDevelopmentManager.InteractiveShellConfig,
        command: String,
    ): String? {
        ProcessSecurityPolicy.requireExecutableName(command)
        val direct = bridge.shellBounded(
            config.shellPrefix() + "command -v ${DeviceBridgeManager.shellQuote(command)}",
            maxOutputBytes = 64 * 1024,
        )
        if (direct.exitCode == 0) {
            direct.stdout.lineSequence().map(String::trim).firstOrNull { candidate ->
                ProcessSecurityPolicy.isAllowedRemoteExecutable(candidate, DeviceBridgeManager.remoteRoot())
            }?.let { return it.take(4_096) }
        }
        return inventory(bridge, config)[command]
    }

    suspend fun inventory(
        bridge: DeviceBridgeManager,
        config: AndroidDevelopmentManager.InteractiveShellConfig,
    ): Map<String, String> {
        val root = DeviceBridgeManager.remoteRoot()
        val userRoot = "$root/user"
        val homeRoot = "$root/home"
        listOf(userRoot, homeRoot).forEach(DeviceBridgeManager::requireSafeRemotePath)
        val script = buildString {
            append(config.shellPrefix())
            append("emit_one() { kind=\"\$1\"; f=\"\$2\"; [ -f \"\$f\" ] && [ -x \"\$f\" ] || return 0; ")
            append("b=\${f##*/}; case \"\$b\" in ''|*[!A-Za-z0-9._+-]*) return 0;; esac; ")
            append("printf '%s\\t%s\\t%s\\n' \"\$kind\" \"\$b\" \"\$f\"; }; ")
            append("oldifs=\$IFS; IFS=:; set -- \${PATH:-}; IFS=\$oldifs; ")
            append("for d do [ -d \"\$d\" ] || continue; for f in \"\$d\"/*; do emit_one P \"\$f\"; done; done; ")
            append("/system/bin/toybox find ")
                .append(DeviceBridgeManager.shellQuote(userRoot)).append(' ')
                .append(DeviceBridgeManager.shellQuote(homeRoot))
                .append(" -print 2>/dev/null | /system/bin/toybox head -n 20000 | ")
            append("while IFS= read -r f; do emit_one U \"\$f\"; done")
        }
        val result = bridge.shellBounded(script, maxOutputBytes = MAX_OUTPUT_BYTES)
        if (result.exitCode != 0) return emptyMap()
        return parseInventoryOutput(result.stdout, root)
    }

     
    internal fun parseInventoryOutput(stdout: String, root: String): Map<String, String> {
        // PATH is deterministic and authoritative.


        val pathExecutables = linkedMapOf<String, String>()
        val fallbackCandidates = linkedMapOf<String, MutableSet<String>>()
        stdout.lineSequence().forEach { line ->
            val fields = line.split('\t', limit = 3)
            if (fields.size != 3) return@forEach
            val kind = fields[0]
            val command = fields[1].trim()
            val path = fields[2].trim().take(4_096)
            if (!commandName.matches(command)) return@forEach
            if (!ProcessSecurityPolicy.isAllowedRemoteExecutable(path, root)) return@forEach
            when (kind) {
                "P" -> if (pathExecutables.size < MAX_COMMANDS) pathExecutables.putIfAbsent(command, path)
                "U" -> if (command !in pathExecutables && fallbackCandidates.size < MAX_COMMANDS * 2) {
                    fallbackCandidates.getOrPut(command) { linkedSetOf() }.add(path)
                }
            }
        }
        val discovered = LinkedHashMap(pathExecutables)
        fallbackCandidates.forEach { (command, candidates) ->
            if (discovered.size < MAX_COMMANDS && candidates.size == 1) {
                discovered.putIfAbsent(command, candidates.first())
            }
        }
        return discovered
    }

}
