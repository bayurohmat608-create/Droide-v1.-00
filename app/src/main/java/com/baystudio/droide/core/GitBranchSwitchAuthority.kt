package com.baystudio.droide.core








object GitBranchSwitchAuthority {
    data class Inventory(
        val localBranches: Set<String>,
        val remoteBranches: Set<String>,
    )

    sealed interface Plan {
        val localName: String

        data class ExistingLocal(override val localName: String) : Plan
        data class NewLocal(override val localName: String) : Plan
        data class TrackRemote(
            override val localName: String,
            val remoteRef: String,
        ) : Plan
    }

    fun plan(requested: String, create: Boolean, inventory: Inventory): Plan {
        val name = requested.trim()
        require(isValidBranchName(name)) { "Invalid branch name" }
        require(!name.startsWith("refs/")) { "Use a branch name, not a raw Git ref" }

        if (create) {
            require(name !in inventory.localBranches) { "Local branch already exists: $name" }
            return Plan.NewLocal(name)
        }

        if (name in inventory.localBranches) return Plan.ExistingLocal(name)

        val remotes = inventory.remoteBranches
            .filterNot { it.endsWith("/HEAD") }
            .sorted()
        val exactRemote = remotes.singleOrNull { it.removePrefix("refs/remotes/") == name }
        if (exactRemote != null) {
            val localName = exactRemote.removePrefix("refs/remotes/").substringAfter('/', missingDelimiterValue = "")
            require(localName.isNotBlank() && isValidBranchName(localName)) { "Remote branch cannot map to a safe local branch" }
            require(localName !in inventory.localBranches) {
                "Local branch '$localName' already exists; switch to it explicitly instead of '$name'"
            }
            return Plan.TrackRemote(localName, exactRemote)
        }

        val tailMatches = remotes.filter {
            val remoteShort = it.removePrefix("refs/remotes/")
            remoteShort.substringAfter('/', missingDelimiterValue = "") == name
        }
        val uniqueTail = tailMatches.singleOrNull()
        if (uniqueTail != null) {
            require(name !in inventory.localBranches) { "Local branch already exists: $name" }
            return Plan.TrackRemote(name, uniqueTail)
        }
        require(tailMatches.size <= 1) {
            "Branch '$name' is ambiguous across remotes: ${tailMatches.take(4).joinToString { it.removePrefix("refs/remotes/") }}"
        }
        throw IllegalArgumentException("Branch not found: $name")
    }

    fun isValidBranchName(name: String): Boolean =
        name.length in 1..200 && org.eclipse.jgit.lib.Repository.isValidRefName("refs/heads/$name")
}
