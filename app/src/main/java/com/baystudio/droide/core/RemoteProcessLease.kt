package com.baystudio.droide.core

import java.util.UUID









internal data class RemoteProcessLease private constructor(
    val pidFile: String,
    private val token: String,
    private val local: Boolean = false,
) {
    fun wrap(command: String): String {
        require(command.isNotBlank() && command.length <= 256_000 && '\u0000' !in command) { "Invalid leased command" }
        if (local) LocalExecutionSubstrate.requireSafeLocalPath(pidFile) else DeviceBridgeManager.requireSafeRemotePath(pidFile)
        require(token.matches(Regex("[0-9a-f]{20}"))) { "Invalid process-lease token" }
        val runDir = pidFile.substringBeforeLast('/')
        val inner = buildString {
            append("export DROIDE_PROCESS_LEASE=").append(DeviceBridgeManager.shellQuote(token)).append("; ")
            append("echo \"${'$'}${'$'}:").append(token).append("\" > ")
                .append(DeviceBridgeManager.shellQuote(pidFile)).append("; ")
            append("exec /system/bin/sh -c ").append(DeviceBridgeManager.shellQuote(command))
        }
        return buildString {
            append("set -eu; mkdir -p ").append(DeviceBridgeManager.shellQuote(runDir)).append("; ")
            append("rm -f ").append(DeviceBridgeManager.shellQuote(pidFile)).append("; ")
            append("exec /system/bin/toybox setsid /system/bin/sh -c ")
                .append(DeviceBridgeManager.shellQuote(inner))
        }
    }

    val environment: Map<String, String> get() = mapOf("DROIDE_PROCESS_LEASE" to token)

    fun cleanupCommand(): String = "rm -f ${DeviceBridgeManager.shellQuote(pidFile)}"

     
    fun aliveCommand(): String {
        if (local) LocalExecutionSubstrate.requireSafeLocalPath(pidFile) else DeviceBridgeManager.requireSafeRemotePath(pidFile)
        val qFile = DeviceBridgeManager.shellQuote(pidFile)
        val qToken = DeviceBridgeManager.shellQuote(token)
        return "set -eu; line=\$(cat $qFile); pid=\${line%%:*}; got=\${line#*:}; " +
            "case \"\$pid\" in ''|*[!0-9]*) exit 1;; esac; " +
            "[ \"\$got\" = $qToken ]; " + ownedProcessShell("\$pid", "\$got")
    }

     
    fun detachedCommand(command: String, logPath: String): String {
        DeviceBridgeManager.requireSafeRemotePath(logPath)
        val parent = logPath.substringBeforeLast('/')
        DeviceBridgeManager.requireSafeRemotePath(parent)
        return "set -eu; mkdir -p ${DeviceBridgeManager.shellQuote(parent)}; " +
            ": > ${DeviceBridgeManager.shellQuote(logPath)}; " +
            "(${wrap(command)}) >> ${DeviceBridgeManager.shellQuote(logPath)} 2>&1 < /dev/null &"
    }

    fun terminateCommand(): String = terminateOneCommand(pidFile, token)

    companion object {
        private const val TOKEN_LENGTH = 20

        fun create(namespace: String): RemoteProcessLease {
            val safe = namespace.lowercase().replace(Regex("[^a-z0-9._-]"), "-").trim('-').take(40)
            require(safe.matches(Regex("[a-z0-9][a-z0-9._-]{0,39}"))) { "Invalid process-lease namespace" }
            val token = UUID.randomUUID().toString().replace("-", "").take(TOKEN_LENGTH)
            return RemoteProcessLease("${DeviceBridgeManager.remoteRoot()}/run/$safe-$token.pid", token)
        }

        fun createLocal(namespace: String): RemoteProcessLease {
            require(namespace.matches(Regex("[a-z0-9-]{1,40}")))
            val token = UUID.randomUUID().toString().replace("-", "").take(TOKEN_LENGTH)
            return RemoteProcessLease("${LocalExecutionSubstrate.localRoot()}/run/$namespace-$token.pid", token, true)
        }

         
        fun reapAllCommand(): String {
            val runDir = "${DeviceBridgeManager.remoteRoot()}/run"
            DeviceBridgeManager.requireSafeRemotePath(runDir)
            val qDir = DeviceBridgeManager.shellQuote(runDir)
            return buildString {
                append("set +e; dir=").append(qDir).append("; [ -d \"${'$'}dir\" ] || exit 0; ")
                append("for f in \"${'$'}dir\"/*.pid; do [ -f \"${'$'}f\" ] || continue; ")
                append("line=${'$'}(cat \"${'$'}f\" 2>/dev/null); pid=${'$'}{line%%:*}; tok=${'$'}{line#*:}; ")
                append("valid=1; case \"${'$'}pid\" in ''|*[!0-9]*) valid=0;; esac; ")
                append("case \"${'$'}tok\" in ''|*[!0-9a-f]*) valid=0;; esac; ")
                append("[ ${'$'}{#tok} -eq $TOKEN_LENGTH ] 2>/dev/null || valid=0; ")
                append("if [ \"${'$'}valid\" = 1 ]; then ")
                append("if owned_process \"${'$'}pid\" \"${'$'}tok\"; then ")
                append(killGroupShell("${'$'}pid"))
                append("fi; kill_token_processes \"${'$'}tok\"; fi; rm -f \"${'$'}f\"; done")
            }.let { loop ->
                "owned_process() { " + ownedProcessShell("${'$'}1", "${'$'}2") + "; }; " +
                    "kill_token_processes() { " + killTokenProcessesShell("${'$'}1") + "; }; " + loop
            }
        }

        private fun terminateOneCommand(pidFile: String, token: String): String {
            val qFile = DeviceBridgeManager.shellQuote(pidFile)
            val qToken = DeviceBridgeManager.shellQuote(token)
            return buildString {
                append("set +e; f=").append(qFile).append("; expected=").append(qToken).append("; ")
                append("pid=''; got=''; if [ -r \"${'$'}f\" ]; then line=${'$'}(cat \"${'$'}f\" 2>/dev/null); ")
                append("pid=${'$'}{line%%:*}; got=${'$'}{line#*:}; ")
                append("case \"${'$'}pid\" in ''|*[!0-9]*) pid='';; esac; fi; ")
                append("if [ -n \"${'$'}pid\" ] && [ \"${'$'}got\" = \"${'$'}expected\" ] && ")
                append("(").append(ownedProcessShell("${'$'}pid", "${'$'}expected")).append("); then ")
                append(killGroupShell("${'$'}pid"))
                append("fi; ")
                append(killTokenProcessesShell("${'$'}expected"))
                append("rm -f \"${'$'}f\"")
            }
        }

        // It never matches by command name and never targets processes without the inherited token, so unrelated shell/app processes remain out of scope.





        private fun killTokenProcessesShell(token: String): String = buildString {
            append("for sig in TERM KILL; do ")
            append("for e in /proc/[0-9]*/environ; do [ -r \"${'$'}e\" ] || continue; ")
            append("if /system/bin/toybox tr '\\000' '\\n' < \"${'$'}e\" 2>/dev/null | ")
            append("/system/bin/toybox grep -Fqx \"DROIDE_PROCESS_LEASE=$token\"; then ")
            append("p=${'$'}{e#/proc/}; p=${'$'}{p%/environ}; ")
            append("case \"${'$'}p\" in ''|*[!0-9]*) continue;; esac; ")
            append("kill -${'$'}sig \"${'$'}p\" 2>/dev/null || true; fi; done; ")
            append("[ \"${'$'}sig\" = TERM ] && sleep 0.2; done; ")
        }

        private fun ownedProcessShell(pid: String, token: String): String =
            "[ -r /proc/$pid/environ ] && " +
                "/system/bin/toybox tr '\\000' '\\n' < /proc/$pid/environ 2>/dev/null | " +
                "/system/bin/toybox grep -Fqx \"DROIDE_PROCESS_LEASE=$token\""

        private fun killGroupShell(pid: String): String =
            "kill -TERM -\"$pid\" 2>/dev/null || kill -TERM \"$pid\" 2>/dev/null; " +
                "sleep 0.2; kill -KILL -\"$pid\" 2>/dev/null || kill -KILL \"$pid\" 2>/dev/null; "
    }
}
