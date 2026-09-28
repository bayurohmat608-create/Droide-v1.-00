package com.baystudio.droide.core

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class StoredPermissionRule(
    val action: String,
    val resource: String,
    val effect: String,
) {
    fun toRule(): PermRule = PermRule(action, resource, when (effect.lowercase()) {
        "allow" -> PermEffect.ALLOW
        "ask" -> PermEffect.ASK
        "deny" -> PermEffect.DENY
        else -> error("Unknown permission effect")
    })

    companion object {
        fun from(rule: PermRule) = StoredPermissionRule(rule.action, rule.resource, rule.effect.name.lowercase())
    }
}

@Serializable
private data class StoredPermissionPolicy(
    val schemaVersion: Int = PermissionPolicyDocument.SCHEMA_VERSION,
    val rules: List<StoredPermissionRule> = emptyList(),
    val agents: Map<String, List<StoredPermissionRule>> = emptyMap(),
) {
    fun toDocument() = PermissionPolicyDocument(
        schemaVersion = schemaVersion,
        rules = rules.map { it.toRule() },
        agents = agents.mapValues { (_, value) -> value.map { it.toRule() } },
    ).validated()

    companion object {
        fun from(policy: PermissionPolicyDocument) = StoredPermissionPolicy(
            schemaVersion = policy.schemaVersion,
            rules = policy.rules.map(StoredPermissionRule::from),
            agents = policy.agents.mapValues { (_, value) -> value.map(StoredPermissionRule::from) },
        )
    }
}

// App-private persistence keyed by Droide project id; never stored inside project source.
class PermissionPolicyStore(private val appFilesDir: File) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = false; encodeDefaults = true }
    private val root = File(appFilesDir, ".droide/permission-policies-v1")

    fun load(projectId: String): PermissionPolicyDocument {
        val file = fileFor(projectId)
        if (!file.isFile) return PermissionPolicyDocument.EMPTY
        require(file.length() in 1..MAX_POLICY_BYTES) { "Permission policy file is invalid or too large" }
        return runCatching { json.decodeFromString<StoredPermissionPolicy>(file.readText()).toDocument() }
            .getOrElse { throw IllegalStateException("Permission policy is invalid for project ${safeProjectId(projectId)}", it) }
    }

    fun save(projectId: String, policy: PermissionPolicyDocument) {
        val validated = policy.validated()
        val file = fileFor(projectId)
        file.parentFile?.mkdirs()
        val encoded = json.encodeToString(StoredPermissionPolicy.serializer(), StoredPermissionPolicy.from(validated)) + "\n"
        require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_POLICY_BYTES) { "Permission policy file is too large" }
        val tmp = File(file.parentFile, ".${file.name}.tmp-${System.nanoTime()}")
        try {
            tmp.writeText(encoded)
            runCatching { Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
                .getOrElse { Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    fun clear(projectId: String) {
        val file = fileFor(projectId)
        if (file.exists() && !file.delete()) error("Cannot remove permission policy")
    }

    private fun fileFor(projectId: String): File = File(root, "${safeProjectId(projectId)}.json")
    private fun safeProjectId(projectId: String): String = PathSecurity.safeLeafName(projectId)

    companion object { private const val MAX_POLICY_BYTES = 1_000_000L }
}
