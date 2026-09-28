package com.baystudio.droide.core

 
object SensitivePathPolicy {
    fun isSensitive(path: String): Boolean {
        val normalized = path.replace('\\', '/').trim().trimStart('/').lowercase()
        if (normalized.isEmpty()) return false
        val segments = normalized.split('/').filter { it.isNotEmpty() && it != "." }
        val base = segments.lastOrNull().orEmpty()

        // Monorepos, submodules and nested worktrees must receive the same protection as workspace-root metadata.

        if (segments.any { it == ".droide" || it == ".git" }) return true
        if (base == ".env" || base.startsWith(".env.")) return true
        if (base in EXACT_SECRET_FILES) return true
        if (base in PRIVATE_KEY_NAMES) return true
        if (SECRET_EXTENSIONS.any(base::endsWith)) return true
        return false
    }

    private val EXACT_SECRET_FILES = setOf(
        ".npmrc",
        ".pypirc",
        ".git-credentials",
        "local.properties",
        "gradle.properties",
        "secrets.properties",
        "keystore.properties",
        "credentials.json",
        "service-account.json",
        "service_account.json",
    )

    private val PRIVATE_KEY_NAMES = setOf("id_rsa", "id_dsa", "id_ecdsa", "id_ed25519")
    private val SECRET_EXTENSIONS = listOf(".pem", ".key", ".p12", ".pfx", ".jks", ".keystore")
}
