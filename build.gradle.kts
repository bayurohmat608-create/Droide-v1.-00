plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.4.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.0" apply false
}

// Resolve external build inputs without invoking compilation, packaging or signing tasks.
tasks.register("resolveDependencyInputs") {
    group = "verification"
    description = "Resolves dependency graphs and external artifacts for locking and checksum verification"
    notCompatibleWithConfigurationCache("Enumerates dependency configurations at execution time")
    doLast {
        allprojects.sortedBy { it.path }.forEach { target ->
            val inputs = target.configurations.toList() + target.buildscript.configurations.toList()
            inputs.filter { it.isCanBeResolved }.sortedBy { it.name }.forEach { configuration ->
                configuration.incoming.resolutionResult.allComponents
                val files = configuration.incoming.artifactView {
                    componentFilter { it is org.gradle.api.artifacts.component.ModuleComponentIdentifier }
                }.files.files
                logger.lifecycle("Dependency inputs: ${target.path}:${configuration.name} (${files.size} external artifacts)")
            }
        }
    }
}
