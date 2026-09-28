package com.baystudio.droide.core

 
internal data class AcpPermissionIdentity(
    val kind: AcpPermissionKind,
    val operationFingerprint: String,
    val action: String,
    val policyResource: String,
    val sessionGrantResource: String,
)

internal object AcpPermissionIdentities {
    fun mutation(canonicalRemotePath: String, content: String, policyPath: String): AcpPermissionIdentity =
        AcpPermissionIdentity(
            kind = AcpPermissionKind.MUTATION,
            operationFingerprint = AcpPermissionLedger.mutationFingerprint(canonicalRemotePath, content),
            action = "write_file",
            policyResource = policyPath.take(4_096),
            
            sessionGrantResource = canonicalRemotePath.take(4_096),
        )

    fun execute(
        command: String,
        args: List<String>,
        environment: Map<String, String>,
        canonicalCwd: String,
    ): AcpPermissionIdentity {
        val fingerprint = AcpPermissionLedger.executeFingerprint(command, args, environment, canonicalCwd)
        fun safe(value: String): String = value
            .replace("\u0000", "\\0")
            .replace("\r", "\\r")
            .replace("\n", "\\n")
        val display = buildString {
            append(safe(command))
            args.forEach { arg -> append(' '); append(safe(arg)) }
            append(" @ ")
            append(safe(canonicalCwd))
        }.let { value -> if (value.length <= 4_096) value else "${value.take(4_000)} #$fingerprint" }
        return AcpPermissionIdentity(
            kind = AcpPermissionKind.EXECUTE,
            operationFingerprint = fingerprint,
            action = "run_command",
            policyResource = display,
            
            sessionGrantResource = fingerprint,
        )
    }
}
