package com.baystudio.droide.core




internal class AndroidToolchainSelector(
    private val managedProvider: suspend () -> AndroidDevelopmentManager.ToolchainManifest?,
    private val userProvider: suspend () -> AndroidDevelopmentManager.ToolchainManifest?,
    private val verifier: suspend (AndroidDevelopmentManager.ToolchainManifest, Int?) -> String?,
    private val requirementsVerifier: (suspend (AndroidDevelopmentManager.ToolchainManifest, AndroidToolchainRequirements) -> String?)? = null,
    private val userCandidatesProvider: (suspend () -> List<AndroidDevelopmentManager.ToolchainManifest>)? = null,
) {
    enum class Source(val label: String) { MANAGED("managed"), USER("user-managed") }

    data class Resolved(
        val manifest: AndroidDevelopmentManager.ToolchainManifest,
        val source: Source,
    )

    data class Resolution(
        val resolved: Resolved?,
        val failure: String? = null,
    )

    data class CandidateResolution(
        val candidates: List<Resolved>,
        val failures: List<String>,
    ) {
        val failure: String? get() = failures.takeIf(List<String>::isNotEmpty)?.joinToString(" · ")
    }

    suspend fun resolve(requiredCompileSdk: Int?): Resolution =
        resolve(AndroidToolchainRequirements(compileSdk = requiredCompileSdk))

     
    suspend fun resolve(requirements: AndroidToolchainRequirements): Resolution {
        val problems = mutableListOf<String>()
        managedProvider()?.let { managed ->
            val problem = problemFor(managed, requirements)
            if (problem == null) return Resolution(Resolved(managed, Source.MANAGED))
            problems += "Managed toolchain: $problem"
        }
        val users = runSuspendCatching { userCandidates() }
            .getOrElse { error ->
                problems += "User toolchain: ${error.message ?: "invalid marker"}"
                emptyList()
            }
        for (user in orderUserCandidates(users)) {
            val problem = problemFor(user, requirements)
            if (problem == null) return Resolution(Resolved(user, Source.USER))
            problems += "User toolchain ${user.version}: $problem"
        }
        return Resolution(null, problems.takeIf { it.isNotEmpty() }?.joinToString(" · "))
    }

    



    suspend fun resolveAll(requirements: AndroidToolchainRequirements): CandidateResolution {
        val candidates = mutableListOf<Resolved>()
        val problems = mutableListOf<String>()
        managedProvider()?.let { managed ->
            val problem = problemFor(managed, requirements)
            if (problem == null) candidates += Resolved(managed, Source.MANAGED)
            else problems += "Managed toolchain: $problem"
        }
        val users = runSuspendCatching { userCandidates() }
            .getOrElse { error ->
                problems += "User toolchain: ${error.message ?: "invalid marker"}"
                emptyList()
            }
        for (user in orderUserCandidates(users)) {
            val problem = problemFor(user, requirements)
            if (problem == null) candidates += Resolved(user, Source.USER)
            else problems += "User toolchain ${user.version}: $problem"
        }
        return CandidateResolution(candidates.distinct(), problems)
    }

    private suspend fun problemFor(
        manifest: AndroidDevelopmentManager.ToolchainManifest,
        requirements: AndroidToolchainRequirements,
    ): String? = requirementsVerifier?.invoke(manifest, requirements) ?: verifier(manifest, requirements.compileSdk)

    private suspend fun userCandidates(): List<AndroidDevelopmentManager.ToolchainManifest> =
        userCandidatesProvider?.invoke() ?: listOfNotNull(userProvider())

    private fun orderUserCandidates(
        candidates: List<AndroidDevelopmentManager.ToolchainManifest>,
    ): List<AndroidDevelopmentManager.ToolchainManifest> = candidates.sortedWith(
        compareBy<AndroidDevelopmentManager.ToolchainManifest> { AndroidToolchainRuntime.runtimePriority(it.runtime) }
            .thenByDescending { it.javaVersion }
            .thenBy { it.version }
    )

    





    suspend fun sourceCandidateStillPresent(candidate: Resolved): Boolean = when (candidate.source) {
        Source.MANAGED -> managedProvider() == candidate.manifest
        Source.USER -> userCandidates().any { it == candidate.manifest }
    }
}
