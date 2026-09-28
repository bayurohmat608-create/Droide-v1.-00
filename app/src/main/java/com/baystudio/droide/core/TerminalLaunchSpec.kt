package com.baystudio.droide.core

 
data class TerminalLaunchSpec(
    val prefix: List<String> = emptyList(),
    val shell: String = "/system/bin/sh",
    val interactiveArgs: List<String> = listOf("-l"),
    val environment: Map<String, String> = emptyMap(),
) {
    init {
        ProcessSecurityPolicy.validateArgv(prefix + listOf(shell) + interactiveArgs)
        require(shell.isNotBlank()) { "Terminal shell is missing" }
        ProcessSecurityPolicy.validateEnvironment(environment)
    }

    val interactiveArgv: List<String> get() = prefix + shell + interactiveArgs

    fun shellCommand(shellText: String, injectedEnvironment: Map<String, String> = emptyMap()): List<String> {
        require('\u0000' !in shellText) { "Shell command contains NUL" }
        ProcessSecurityPolicy.validateEnvironment(injectedEnvironment)
        return command(listOf(shell, "-c", shellText), injectedEnvironment)
    }

    fun command(argv: List<String>, injectedEnvironment: Map<String, String> = emptyMap()): List<String> {
        ProcessSecurityPolicy.validateArgv(argv)
        ProcessSecurityPolicy.validateEnvironment(injectedEnvironment)
        require(argv.first().isNotBlank()) { "Executable is missing" }
        

        return if (prefix.isEmpty()) argv else prefix + injectedEnvironment.map { (key, value) -> "$key=$value" } + argv
    }

     
    val canReviveAsAndroidShell: Boolean
        get() = prefix.isEmpty() && shell == "/system/bin/sh" &&
            interactiveArgs == listOf("-l") && environment.isEmpty()
}
