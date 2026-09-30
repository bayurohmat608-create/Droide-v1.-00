package com.baystudio.droide.core

import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.dircache.DirCacheIterator
import org.eclipse.jgit.treewalk.FileTreeIterator
import org.eclipse.jgit.treewalk.filter.PathFilterGroup









data class IsolatedWorkspaceInfo(
    val name: String,
    val directory: String,
    val branch: String,
    val baseCommit: String,
    val parentBranch: String,
    val parentRepositoryIdentity: String,
)

data class IsolatedWorkspaceMergeResult(
    val merged: Boolean,
    val noChanges: Boolean = false,
    val conflicts: List<String> = emptyList(),
    val message: String,
)

class WorktreeManager(private val workDir: File) {
    private val base = PathSecurity.resolveWithin(workDir, ".droide/worktrees")

    // Compatibility API used by the human-facing Isolated Workspaces sheet/tool.
    suspend fun create(name: String, branch: String = name): String = runCatching {
        val info = provision(name, branch)
        "Isolated workspace ${info.name} created at ${info.directory} (branch ${info.branch}, base ${info.baseCommit.take(8)})"
    }.getOrElse { "Workspace create failed: ${it.message}" }

    // Parent must be clean so the child never receives a silently stale/incomplete snapshot.



    suspend fun provision(name: String, branch: String = "droide-agent/$name"): IsolatedWorkspaceInfo = withContext(Dispatchers.IO) {
        val safeName = PathSecurity.safeLeafName(name.trim())
        val safeBranch = branch.trim().also {
            require(it.length in 1..200 && Repository.isValidRefName("refs/heads/$it")) { "Invalid branch" }
        }
        check(base.isDirectory || base.mkdirs()) { "Cannot create isolated-workspace directory" }
        val dir = PathSecurity.resolveWithin(base, safeName)
        require(!dir.exists() && !PathSecurity.isSymbolicLink(dir)) { "Workspace already exists: $safeName" }

        val parent = openGit(workDir) ?: error("Isolated subagent workspaces require a Git repository")
        parent.use { parentGit ->
            val status = parentGit.status().call()
            require(status.isClean) {
                "Parent Git workspace must be clean before creating an isolated child workspace. Commit/stash changes or explicitly use shared serialized execution."
            }
            val fullBranch = parentGit.repository.fullBranch.orEmpty()
            require(fullBranch.startsWith(Constants.R_HEADS)) { "Detached HEAD is not supported for isolated child workspaces" }
            val sourceBranch = fullBranch.removePrefix(Constants.R_HEADS)
            val head = parentGit.repository.resolve(Constants.HEAD)?.name
                ?: error("Cannot resolve parent HEAD")
            val parentRepositoryIdentity = WorktreeProvenance.repositoryIdentity(
                parentGit.repository.workTree,
                parentGit.repository.directory,
            )

            try {
                Git.cloneRepository()
                    .setURI(workDir.canonicalFile.toURI().toString())
                    .setDirectory(dir)
                    .setBranch(sourceBranch)
                    .setCloneAllBranches(true)
                    .setTimeout(30)
                    .call().use { clone ->
                        val clonedHead = clone.repository.resolve(Constants.HEAD)?.name
                            ?: error("Cannot resolve isolated workspace HEAD")
                        require(clonedHead == head) {
                            "Isolated workspace base changed during provisioning; expected ${head.take(8)}, got ${clonedHead.take(8)}"
                        }
                        clone.checkout()
                            .setCreateBranch(true)
                            .setName(safeBranch)
                            .setStartPoint(head)
                            .call()
                        clone.repository.config.apply {
                            setString("droideIsolated", null, "baseCommit", head)
                            save()
                        }
                    }
            } catch (t: Throwable) {
                PathSecurity.deleteTreeNoFollow(dir)
                throw t
            }
            IsolatedWorkspaceInfo(
                name = safeName,
                directory = dir.canonicalPath,
                branch = safeBranch,
                baseCommit = head,
                parentBranch = sourceBranch,
                parentRepositoryIdentity = parentRepositoryIdentity,
            )
        }
    }

     
    suspend fun status(name: String): String = withContext(Dispatchers.IO) {
        val safe = PathSecurity.safeLeafName(name)
        val dir = PathSecurity.resolveWithin(base, safe)
        val child = openGit(dir) ?: return@withContext "Workspace not found or not a Git repository: $safe"
        child.use { git ->
            val s = git.status().call()
            val branch = git.repository.branch
            fun listVisible(paths: Collection<String>): String {
                val unique = paths.distinct().sorted()
                val shown = unique.filterNot(SensitivePathPolicy::isSensitive)
                val hidden = unique.size - shown.size
                return buildString {
                    append(shown.take(50))
                    if (shown.size > 50) append(" … +${shown.size - 50} more")
                    if (hidden > 0) append(" ($hidden sensitive path(s) hidden)")
                }
            }
            buildString {
                appendLine("workspace=$safe branch=$branch")
                appendLine("clean=${s.isClean}")
                appendLine("added=${listVisible(s.added)}")
                appendLine("changed=${listVisible(s.changed + s.modified)}")
                appendLine("removed=${listVisible(s.removed + s.missing)}")
                appendLine("untracked=${listVisible(s.untracked)}")
                appendLine("conflicts=${listVisible(s.conflicting)}")
            }.take(12_000)
        }
    }

     
    suspend fun reviewChanges(name: String): String = withContext(Dispatchers.IO) {
        val safe = PathSecurity.safeLeafName(name)
        val dir = PathSecurity.resolveWithin(base, safe)
        val child = openGit(dir) ?: return@withContext "Workspace not found or not a Git repository: $safe"
        child.use { git ->
            val fullStatus = status(safe)
            val statusText = fullStatus.take(2_800) + if (fullStatus.length > 2_800) "\n… Status list truncated; some paths are not shown." else ""
            val out = BoundedOutput(12_000)
            var redacted = 0
            var omittedEntries = 0
            var baselineNote: String? = null
            DiffFormatter(out).use { formatter ->
                formatter.setRepository(git.repository)
                suspend fun format(entries: List<DiffEntry>, label: String) {
                    if (out.truncated) return
                    var shown = 0
                    for (entry in entries) {
                        currentCoroutineContext().ensureActive()
                        val paths = listOf(entry.oldPath, entry.newPath).filterNot { it == DiffEntry.DEV_NULL }
                        if (paths.any(SensitivePathPolicy::isSensitive)) { redacted++; continue }
                        if (shown == 80 || out.truncated) { omittedEntries++; continue }
                        if (shown == 0) out.write("\n--- $label ---\n".toByteArray(Charsets.UTF_8))
                        formatter.format(entry)
                        shown++
                    }
                }

                val recordedBase = git.repository.config.getString("droideIsolated", null, "baseCommit")
                val baseId = recordedBase?.takeIf { ObjectId.isId(it) }?.let { git.repository.resolve(it) }
                val headId = git.repository.resolve(Constants.HEAD)
                if (baseId == null || headId == null) {
                    baselineNote = "Committed changes cannot be reviewed: recorded base is unavailable (older workspace or changed Git metadata)."
                } else {
                    RevWalk(git.repository).use { walk ->
                        val baseCommit = walk.parseCommit(baseId)
                        val headCommit = walk.parseCommit(headId)
                        if (!walk.isMergedInto(baseCommit, headCommit)) {
                            baselineNote = "Committed changes cannot be reviewed: child HEAD no longer descends from the recorded base."
                        } else if (baseId != headId) {
                            format(formatter.scan(baseCommit.tree.id, headCommit.tree.id), "committed since isolated base")
                        }
                    }
                }
                format(git.diff().setCached(true).call(), "staged")

                // Working-tree entries may reference file content that does not exist as a Git object yet.
                // Keep a FileTreeIterator attached to the formatter so JGit reads those bytes from disk
                // instead of trying to reopen the synthetic working-tree object id from the object database.
                val workingEntries = git.diff().call()
                val allowedWorkingEntries = mutableListOf<DiffEntry>()
                for (entry in workingEntries) {
                    currentCoroutineContext().ensureActive()
                    val paths = listOf(entry.oldPath, entry.newPath).filterNot { it == DiffEntry.DEV_NULL }
                    if (paths.any(SensitivePathPolicy::isSensitive)) {
                        redacted++
                    } else if (allowedWorkingEntries.size < 80) {
                        allowedWorkingEntries += entry
                    } else {
                        omittedEntries++
                    }
                }
                if (allowedWorkingEntries.isNotEmpty() && !out.truncated) {
                    out.write("\n--- working tree ---\n".toByteArray(Charsets.UTF_8))
                    val allowedPaths = allowedWorkingEntries
                        .flatMap { listOf(it.oldPath, it.newPath) }
                        .filterNot { it == DiffEntry.DEV_NULL }
                        .distinct()
                    DiffFormatter(out).use { workingFormatter ->
                        workingFormatter.setRepository(git.repository)
                        workingFormatter.setPathFilter(PathFilterGroup.createFromStrings(allowedPaths))
                        workingFormatter.format(
                            DirCacheIterator(git.repository.readDirCache()),
                            FileTreeIterator(git.repository),
                        )
                    }
                }
            }
            buildString {
                append(statusText)
                baselineNote?.let { append('\n').append(it) }
                if (redacted > 0) append("\n$redacted sensitive diff path(s) hidden")
                val patch = out.utf8()
                if (patch.isNotBlank()) append(patch) else append("\nNo visible tracked patch. See status for untracked files.")
                if (out.truncated || omittedEntries > 0) {
                    append("\n… Preview limited to 12,000 bytes and 80 paths per section; further changes are not shown.")
                }
            }.take(16_000)
        }
    }

    // The parent must be clean.




    suspend fun merge(
        name: String,
        expectedBaseCommit: String,
        expectedParentBranch: String,
        expectedParentRepositoryIdentity: String,
        expectedChildBranch: String,
        commitMessage: String,
    ): IsolatedWorkspaceMergeResult = withContext(Dispatchers.IO) {
        val safe = PathSecurity.safeLeafName(name)
        val childDir = PathSecurity.resolveWithin(base, safe)
        val child = openGit(childDir) ?: return@withContext IsolatedWorkspaceMergeResult(
            merged = false, message = "Isolated workspace is missing: $safe"
        )
        val parent = openGit(workDir) ?: run {
            child.close()
            return@withContext IsolatedWorkspaceMergeResult(false, message = "Parent workspace is not a Git repository")
        }

        child.use { childGit ->
            parent.use { parentGit ->
                val parentStatus = parentGit.status().call()
                if (!parentStatus.isClean) {
                    return@withContext IsolatedWorkspaceMergeResult(
                        false,
                        message = "Parent workspace changed while the child was running. Commit/stash parent changes before merge."
                    )
                }

                val parentFullBranch = parentGit.repository.fullBranch.orEmpty()
                if (!parentFullBranch.startsWith(Constants.R_HEADS)) {
                    return@withContext IsolatedWorkspaceMergeResult(
                        false, message = "Parent workspace is detached; switch back to the original branch before merge."
                    )
                }
                val currentParentBranch = parentFullBranch.removePrefix(Constants.R_HEADS)
                val currentParentRepositoryIdentity = WorktreeProvenance.repositoryIdentity(
                    parentGit.repository.workTree,
                    parentGit.repository.directory,
                )
                val currentChildBranch = childGit.repository.branch.orEmpty()
                WorktreeProvenance.validateMergeBinding(
                    expectedParentBranch = expectedParentBranch,
                    currentParentBranch = currentParentBranch,
                    expectedParentRepositoryIdentity = expectedParentRepositoryIdentity,
                    currentParentRepositoryIdentity = currentParentRepositoryIdentity,
                    expectedChildBranch = expectedChildBranch,
                    currentChildBranch = currentChildBranch,
                )?.let { reason ->
                    return@withContext IsolatedWorkspaceMergeResult(false, message = reason)
                }

                val originalHead = parentGit.repository.resolve(Constants.HEAD)?.name
                    ?: return@withContext IsolatedWorkspaceMergeResult(false, message = "Cannot resolve parent HEAD")
                val parentBaseObject = parentGit.repository.resolve(expectedBaseCommit)
                    ?: return@withContext IsolatedWorkspaceMergeResult(
                        false, message = "Parent repository no longer contains the child's recorded base commit; refusing unsafe merge."
                    )
                val parentHeadObject = parentGit.repository.resolve(Constants.HEAD)
                    ?: return@withContext IsolatedWorkspaceMergeResult(false, message = "Cannot resolve parent HEAD for ancestry check")
                val parentStillDescendsFromBase = RevWalk(parentGit.repository).use { walk ->
                    walk.isMergedInto(walk.parseCommit(parentBaseObject), walk.parseCommit(parentHeadObject))
                }
                if (!parentStillDescendsFromBase) {
                    return@withContext IsolatedWorkspaceMergeResult(
                        false, message = "Original child base is no longer an ancestor of the parent branch; refusing merge after branch history rewrite."
                    )
                }

                
                val childStatus = childGit.status().call()
                if (!childStatus.isClean) {
                    val candidates = childStatus.added + childStatus.changed + childStatus.modified + childStatus.untracked +
                        childStatus.untrackedFolders + childStatus.removed + childStatus.missing
                    val sensitive = candidates.filter(SensitivePathPolicy::isSensitive).sorted()
                    if (sensitive.isNotEmpty()) {
                        return@withContext IsolatedWorkspaceMergeResult(
                            false,
                            message = "Refusing to merge sensitive child path(s): ${sensitive.take(8).joinToString()}"
                        )
                    }
                    childGit.add().addFilepattern(".").call()
                    childGit.add().setUpdate(true).addFilepattern(".").call()
                    val identity = GitIdentityPolicy.effective(parentGit.repository)
                        ?: return@withContext IsolatedWorkspaceMergeResult(
                            false, message = "Git identity is not configured. Set user.name and user.email before merging Agent workspace changes."
                        )
                    childGit.commit()
                        .setMessage(validateMergeMessage(commitMessage))
                        .setAuthor(identity.name, identity.email)
                        .setCommitter(identity.name, identity.email)
                        .call()
                }

                val childHead = childGit.repository.resolve(Constants.HEAD)?.name
                    ?: return@withContext IsolatedWorkspaceMergeResult(false, message = "Cannot resolve child HEAD")
                if (childHead == expectedBaseCommit) {
                    return@withContext IsolatedWorkspaceMergeResult(
                        merged = true,
                        noChanges = true,
                        message = "Child workspace produced no Git changes."
                    )
                }
                val baseObject = childGit.repository.resolve(expectedBaseCommit)
                    ?: return@withContext IsolatedWorkspaceMergeResult(
                        false, message = "Child workspace no longer contains its recorded base commit; refusing unsafe merge."
                    )
                val headObject = childGit.repository.resolve(Constants.HEAD)
                    ?: return@withContext IsolatedWorkspaceMergeResult(false, message = "Cannot resolve child HEAD for ancestry check")
                val ancestryOk = RevWalk(childGit.repository).use { walk ->
                    walk.isMergedInto(walk.parseCommit(baseObject), walk.parseCommit(headObject))
                }
                if (!ancestryOk) {
                    return@withContext IsolatedWorkspaceMergeResult(
                        false, message = "Child history no longer descends from its recorded base commit; refusing unsafe merge."
                    )
                }

                val branch = currentChildBranch
                val tempRef = "refs/droide/subagents/$safe"
                try {
                    parentGit.fetch()
                        .setRemote(childDir.canonicalFile.toURI().toString())
                        .setRefSpecs(RefSpec("+refs/heads/$branch:$tempRef"))
                        .call()
                    val imported = parentGit.repository.resolve(tempRef)
                        ?: return@withContext IsolatedWorkspaceMergeResult(false, message = "Failed to import child branch")
                    val result = parentGit.merge()
                        .include(imported)
                        .setMessage(validateMergeMessage(commitMessage))
                        .call()
                    if (result.mergeStatus.isSuccessful) {
                        return@withContext IsolatedWorkspaceMergeResult(
                            true,
                            message = "Merged isolated workspace $safe into parent (${result.mergeStatus})."
                        )
                    }

                    val conflicts = result.conflicts?.keys?.sorted().orEmpty()
                    val rollback = rollbackParent(parentGit, originalHead)
                    IsolatedWorkspaceMergeResult(
                        merged = false,
                        conflicts = conflicts,
                        message = buildString {
                            append("Merge refused (${result.mergeStatus}). ").append(rollback)
                            if (conflicts.isNotEmpty()) append(" Conflicts: ${conflicts.take(20).joinToString()}")
                        }
                    )
                } catch (t: Throwable) {
                    val rollback = rollbackParent(parentGit, originalHead)
                    IsolatedWorkspaceMergeResult(false, message = "Merge failed: ${t.message}. $rollback")
                } finally {
                    runCatching {
                        parentGit.repository.updateRef(tempRef).apply { isForceUpdate = true }.delete()
                    }
                }
            }
        }
    }

     
    suspend fun remove(name: String, force: Boolean = false): String = withContext(Dispatchers.IO) {
        val safe = PathSecurity.safeLeafName(name)
        val dir = PathSecurity.resolveWithin(base, safe)
        if (!dir.exists()) return@withContext "Workspace already absent: $safe"
        require(PathSecurity.contains(base, dir) && !PathSecurity.isSymbolicLink(dir)) { "Unsafe workspace path" }
        val child = openGit(dir)
        require(force || child != null) { "Workspace $safe has no readable Git repository; refusing to delete it" }
        if (child != null) {
            child.use {
                val dirty = !it.status().call().isClean
                require(force || !dirty) { "Workspace $safe has uncommitted changes; merge it or use explicit discard." }
                if (!force) {
                    val childHead = it.repository.resolve(Constants.HEAD) ?: error("Cannot resolve workspace HEAD")
                    val parent = openGit(workDir) ?: error("Cannot verify parent Git repository")
                    parent.use { parentGit ->
                        val parentHead = parentGit.repository.resolve(Constants.HEAD) ?: error("Cannot resolve parent HEAD")
                        val integrated = if (!parentGit.repository.objectDatabase.has(childHead)) {
                            false
                        } else {
                            RevWalk(parentGit.repository).use { walk ->
                                walk.isMergedInto(walk.parseCommit(childHead), walk.parseCommit(parentHead))
                            }
                        }
                        require(integrated) { "Workspace $safe contains commits not merged into the parent branch; review and merge before removal." }
                    }
                }
            }
        }
        PathSecurity.deleteTreeNoFollow(dir)
        "Removed isolated workspace $safe"
    }

    fun directory(name: String): File? {
        val safe = runCatching { PathSecurity.safeLeafName(name) }.getOrNull() ?: return null
        val dir = runCatching { PathSecurity.resolveWithin(base, safe) }.getOrNull() ?: return null
        return dir.takeIf { it.isDirectory && !PathSecurity.isSymbolicLink(it) && PathSecurity.contains(base, it) }
    }

    fun list(): List<String> = base.listFiles().orEmpty().asSequence()
        .filter { it.isDirectory && !PathSecurity.isSymbolicLink(it) && PathSecurity.contains(base, it) }
        .map { it.name }
        .sorted()
        .take(100)
        .toList()

    private fun openGit(root: File): Git? {
        val gitDir = PathSecurity.resolveWithin(root, ".git")
        if (!gitDir.exists()) return null
        return runCatching {
            val repo = FileRepositoryBuilder()
                .setGitDir(gitDir)
                .setWorkTree(root)
                .readEnvironment()
                .build()
            Git(repo)
        }.getOrNull()
    }

    private fun rollbackParent(parentGit: Git, originalHead: String): String = try {
        parentGit.reset().setMode(ResetCommand.ResetType.HARD).setRef(originalHead).call()
        val actual = parentGit.repository.resolve(Constants.HEAD)?.name
        if (actual != originalHead) {
            "Parent rollback could not confirm its original HEAD; inspect Git before continuing."
        } else {
            val after = parentGit.status().call()
            val tracked = after.added + after.changed + after.modified + after.removed + after.missing + after.conflicting
            when {
                tracked.isNotEmpty() -> "Parent HEAD was restored, but tracked changes remain; inspect Git before continuing."
                after.untracked.isNotEmpty() || after.untrackedFolders.isNotEmpty() ->
                    "Parent HEAD was restored; untracked files were preserved for review and may include merge residue."
                else -> "Parent HEAD and tracked working tree were restored."
            }
        }
    } catch (error: Exception) {
        "Parent rollback could not be confirmed (${error.message ?: error::class.java.simpleName}); inspect Git before continuing."
    }

    private fun validateMergeMessage(message: String): String {
        val clean = message.replace('\u0000', ' ').trim()
        require(clean.isNotBlank() && clean.length <= 500) { "Merge message must be 1..500 chars" }
        return clean
    }
}
