package com.baystudio.droide.core

// Preserves interpreter-default rollback while keeping provider lifecycle plumbing out of the UI coordinator.
internal class ReviewedArtifactInstallCoordinator(
    private val registry: ManagedPackageRegistry,
    private val packageInstaller: ManagedPackageInstaller,
    private val backend: ReviewedArtifactBackendRouter,
) {
    suspend fun install(
        recipe: GitHubReleaseInstallRecipe,
        ensureInterpreter: suspend (String, String) -> GitHubArtifactInterpreterDependency,
    ): ManagedPackageRecord = installWithInterpreter(recipe.interpreterFamilyId, recipe.interpreterCommand, ensureInterpreter) { interpreter ->
        backend.install(recipe, interpreter)
    }

    suspend fun install(
        recipe: VendorArtifactInstallRecipe,
        ensureInterpreter: suspend (String, String) -> GitHubArtifactInterpreterDependency,
    ): ManagedPackageRecord = installWithInterpreter(recipe.interpreterFamilyId, recipe.interpreterCommand, ensureInterpreter) { interpreter ->
        backend.install(recipe, interpreter)
    }

    private suspend fun installWithInterpreter(
        interpreterFamilyId: String?,
        interpreterCommand: String?,
        ensureInterpreter: suspend (String, String) -> GitHubArtifactInterpreterDependency,
        install: suspend (GitHubArtifactInterpreterDependency?) -> ManagedPackageRecord,
    ): ManagedPackageRecord {
        backend.requireBackend()
        val previous = interpreterFamilyId?.let { family -> registry.list().firstOrNull { it.familyId == family && it.active } }
        val interpreter = if (interpreterFamilyId != null) ensureInterpreter(interpreterFamilyId, requireNotNull(interpreterCommand)) else null
        return try {
            install(interpreter)
        } finally {
            if (previous != null && interpreter != null && previous.version != interpreter.record.version) {
                registry.find(previous.familyId, previous.version)?.let { record ->
                    if (!record.active) runSuspendCatching { packageInstaller.activate(record) }
                }
            }
        }
    }
}
