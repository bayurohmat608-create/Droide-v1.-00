package com.baystudio.droide.core

 
internal object AwsSharedProfileParser {
    private const val MAX_SECTIONS = 128
    private const val MAX_ENTRIES_PER_SECTION = 64

    data class Profile(
        val accessKeyId: String,
        val secretAccessKey: String,
        val sessionToken: String?,
    )

    fun credentials(raw: String, profile: String): Profile {
        require(PROFILE.matches(profile)) { "Invalid AWS profile name" }
        val section = parse(raw)[profile] ?: error("AWS profile '$profile' was not found")
        val access = section["aws_access_key_id"]?.trim().orEmpty()
        val secret = section["aws_secret_access_key"]?.trim().orEmpty()
        require(access.isNotEmpty() && secret.isNotEmpty()) { "AWS profile '$profile' is missing access keys" }
        return Profile(access, secret, section["aws_session_token"]?.trim()?.takeIf { it.isNotEmpty() })
    }

    fun region(raw: String, profile: String): String? {
        require(PROFILE.matches(profile)) { "Invalid AWS profile name" }
        val section = if (profile == "default") "default" else "profile $profile"
        return parse(raw)[section]?.get("region")?.trim()?.takeIf { it.isNotEmpty() }?.also {
            require(REGION.matches(it)) { "Invalid cloud region" }
        }
    }

    internal fun parse(raw: String): Map<String, Map<String, String>> {
        val result = linkedMapOf<String, MutableMap<String, String>>()
        var current: MutableMap<String, String>? = null
        raw.lineSequence().forEach { original ->
            val line = original.trim()
            if (line.isEmpty() || line.startsWith('#') || line.startsWith(';')) return@forEach
            if (line.startsWith('[') && line.endsWith(']')) {
                val section = line.substring(1, line.length - 1).trim()
                require(section.isNotEmpty() && section.length <= 160) { "Invalid credential profile section" }
                require(result.size < MAX_SECTIONS || section in result) { "Too many credential profile sections" }
                current = result.getOrPut(section) { linkedMapOf() }
                return@forEach
            }
            val target = current ?: return@forEach
            val split = line.indexOf('=')
            if (split <= 0) return@forEach
            require(target.size < MAX_ENTRIES_PER_SECTION) { "Too many entries in credential profile" }
            val key = line.substring(0, split).trim().lowercase()
            val value = line.substring(split + 1).trim()
            if (key.matches(Regex("[a-z0-9_.-]{1,96}")) && value.length <= 32_768) target[key] = value
        }
        return result
    }

    private val PROFILE = Regex("[A-Za-z0-9+=,.@_-]{1,128}")
    private val REGION = Regex("[a-z]{2}(?:-gov)?-[a-z]+-\\d")
}
