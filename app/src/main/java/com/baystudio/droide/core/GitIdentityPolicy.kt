package com.baystudio.droide.core

import org.eclipse.jgit.lib.Repository

 
data class GitIdentity(val name: String, val email: String)

object GitIdentityPolicy {
    fun effective(repository: Repository): GitIdentity? {
        val config = repository.config
        val name = config.getString("user", null, "name")?.trim().orEmpty()
        val email = config.getString("user", null, "email")?.trim().orEmpty()
        if (name.isBlank() || email.isBlank()) return null
        return runCatching { validated(name, email) }.getOrNull()
    }

    fun configureLocal(repository: Repository, name: String, email: String): GitIdentity {
        val identity = validated(name, email)
        val config = repository.config
        config.setString("user", null, "name", identity.name)
        config.setString("user", null, "email", identity.email)
        config.save()
        return identity
    }

    fun clearLocal(repository: Repository) {
        val config = repository.config
        config.unset("user", null, "name")
        config.unset("user", null, "email")
        config.save()
    }

    fun require(repository: Repository): GitIdentity = effective(repository)
        ?: error("Git identity is not configured. Set user.name and user.email for this repository before committing.")

    fun validated(name: String, email: String): GitIdentity {
        val safeName = name.trim()
        val safeEmail = email.trim()
        require(safeName.length in 1..200 && safeName.none(::isUnsafeControl)) { "Git user.name is invalid" }
        require(safeEmail.length in 3..320 && safeEmail.none(::isUnsafeControl) && EMAIL.matches(safeEmail)) {
            "Git user.email is invalid"
        }
        return GitIdentity(safeName, safeEmail)
    }

    private fun isUnsafeControl(ch: Char): Boolean = ch == '\n' || ch == '\r' || ch == '\u0000' || ch.code < 0x20 || ch.code == 0x7f
    private val EMAIL = Regex("^[^\\s<>@]+@[^\\s<>@]+$")
}
