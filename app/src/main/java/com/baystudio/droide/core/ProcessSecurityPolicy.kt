package com.baystudio.droide.core

 
object ProcessSecurityPolicy {
    private val executableName = Regex("[A-Za-z0-9._+-]{1,128}")
    private val environmentKey = Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")
    private val GUEST_EXECUTABLE_ROOTS = listOf(
        "/root/.local/bin", "/root/.cargo/bin", "/root/go/bin",
        "/usr/local/sbin", "/usr/local/bin", "/usr/sbin", "/usr/bin", "/sbin", "/bin",
    )

    fun requireExecutableName(command: String) {
        require(executableName.matches(command)) { "Invalid executable name" }
    }

    fun isAllowedRemoteExecutable(path: String, managedRoot: String): Boolean {
        if (!isSafeAbsoluteExecutablePath(path)) return false
        val allowedManagedRoot = managedRoot.trimEnd('/') + "/"
        return path.startsWith("/system/") || path.startsWith("/apex/") || path.startsWith(allowedManagedRoot)
    }

     
    fun isAllowedGuestExecutable(path: String): Boolean {
        if (!isSafeAbsoluteExecutablePath(path)) return false
        return GUEST_EXECUTABLE_ROOTS.any { root -> path.startsWith("$root/") }
    }

     
    fun isAllowedCapabilityExecutable(path: String, managedRoot: String): Boolean =
        isAllowedRemoteExecutable(path, managedRoot) || isAllowedGuestExecutable(path)

    private fun isSafeAbsoluteExecutablePath(path: String): Boolean {
        if (!path.startsWith('/')) return false
        if ('\u0000' in path || '\n' in path || '\r' in path) return false
        if (path.length > 4_096) return false
        val parts = path.split('/').drop(1)
        return parts.none { it.isBlank() || it == "." || it == ".." }
    }

    fun validateArgv(argv: List<String>) {
        require(argv.isNotEmpty() && argv.size <= 256) { "Invalid process argv" }
        argv.forEach { argument ->
            require(argument.length <= 32_000 && '\u0000' !in argument && '\n' !in argument && '\r' !in argument) {
                "Invalid process argument"
            }
        }
    }

    fun validateEnvironment(environment: Map<String, String>) {
        require(environment.size <= 128) { "Too many process environment entries" }
        environment.forEach { (key, value) ->
            require(environmentKey.matches(key)) { "Invalid environment key" }
            require(value.length <= 32_000 && '\u0000' !in value && '\n' !in value && '\r' !in value) {
                "Invalid environment value"
            }
        }
    }
}
