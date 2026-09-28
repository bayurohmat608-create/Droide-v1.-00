package com.baystudio.droide.core

 
object ManagedPackageDependencyResolver {
    fun resolve(
        document: ManagedPackageCatalogDocument,
        target: ManagedPackageCatalogEntry,
        supportedAbis: List<String>,
    ): List<ManagedPackageCatalogEntry> {
        require(supportedAbis.isNotEmpty()) { "No supported ABI is available" }
        val resolved = linkedMapOf<String, ManagedPackageCatalogEntry>()
        val visiting = linkedSetOf<String>()

        fun visit(entry: ManagedPackageCatalogEntry) {
            val key = "${entry.familyId}@${entry.version}"
            check(visiting.add(key)) { "Managed package dependency cycle at $key" }
            entry.dependencies.forEach { dependency ->
                val at = dependency.lastIndexOf('@')
                require(at in 1 until dependency.lastIndex) { "Invalid managed package dependency: $dependency" }
                val family = dependency.substring(0, at)
                val version = dependency.substring(at + 1)
                val dep = ManagedPackageCatalog.select(document, family, version, supportedAbis)
                    ?: error("Missing verified dependency $dependency")
                val depKey = "${dep.familyId}@${dep.version}"
                if (depKey !in resolved) visit(dep)
            }
            visiting.remove(key)
            val previous = resolved[key]
            require(previous == null || previous.id == entry.id) { "Conflicting managed package dependency for $key" }
            resolved[key] = entry
            require(resolved.size <= 128) { "Managed package dependency graph is too large" }
        }

        visit(target)
        return resolved.values.toList()
    }
}
