from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    if old not in text:
        raise SystemExit(f"expected source block missing: {path}")
    if text.count(old) != 1:
        raise SystemExit(f"source block is not unique: {path}")
    p.write_text(text.replace(old, new, 1))


worktree = "app/src/main/java/com/baystudio/droide/core/WorktreeManager.kt"
replace_once(
    worktree,
    "import org.eclipse.jgit.diff.DiffFormatter\n",
    "import org.eclipse.jgit.diff.DiffFormatter\nimport org.eclipse.jgit.dircache.DirCacheIterator\nimport org.eclipse.jgit.treewalk.FileTreeIterator\nimport org.eclipse.jgit.treewalk.filter.PathFilterGroup\n",
)
replace_once(
    worktree,
    '                format(git.diff().setCached(true).call(), "staged")\n                format(git.diff().call(), "working tree")\n',
    '''                format(git.diff().setCached(true).call(), "staged")\n\n                // Working-tree entries may reference file content that does not exist as a Git object yet.\n                // Keep a FileTreeIterator attached to the formatter so JGit reads those bytes from disk\n                // instead of trying to reopen the synthetic working-tree object id from the object database.\n                val workingEntries = git.diff().call()\n                val allowedWorkingEntries = mutableListOf<DiffEntry>()\n                for (entry in workingEntries) {\n                    currentCoroutineContext().ensureActive()\n                    val paths = listOf(entry.oldPath, entry.newPath).filterNot { it == DiffEntry.DEV_NULL }\n                    if (paths.any(SensitivePathPolicy::isSensitive)) {\n                        redacted++\n                    } else if (allowedWorkingEntries.size < 80) {\n                        allowedWorkingEntries += entry\n                    } else {\n                        omittedEntries++\n                    }\n                }\n                if (allowedWorkingEntries.isNotEmpty() && !out.truncated) {\n                    out.write("\\n--- working tree ---\\n".toByteArray(Charsets.UTF_8))\n                    val allowedPaths = allowedWorkingEntries\n                        .flatMap { listOf(it.oldPath, it.newPath) }\n                        .filterNot { it == DiffEntry.DEV_NULL }\n                        .distinct()\n                    DiffFormatter(out).use { workingFormatter ->\n                        workingFormatter.setRepository(git.repository)\n                        workingFormatter.setPathFilter(PathFilterGroup.createFromStrings(allowedPaths))\n                        workingFormatter.format(\n                            DirCacheIterator(git.repository.readDirCache()),\n                            FileTreeIterator(git.repository),\n                        )\n                    }\n                }\n''',
)
replace_once(
    worktree,
    '''                        val integrated = RevWalk(parentGit.repository).use { walk ->\n                            val imported = parentGit.repository.resolve(childHead.name)\n                            imported != null && walk.isMergedInto(walk.parseCommit(imported), walk.parseCommit(parentHead))\n                        }\n''',
    '''                        val integrated = if (!parentGit.repository.objectDatabase.has(childHead)) {\n                            false\n                        } else {\n                            RevWalk(parentGit.repository).use { walk ->\n                                walk.isMergedInto(walk.parseCommit(childHead), walk.parseCommit(parentHead))\n                            }\n                        }\n''',
)

git_manager = "app/src/main/java/com/baystudio/droide/core/GitManager.kt"
replace_once(
    git_manager,
    '''                            git.checkout()\n                                .setName(plan.localName)\n                                .setCreateBranch(true)\n                                .setStartPoint(plan.remoteRef)\n                                .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK)\n                                .call()\n''',
    '''                            git.checkout()\n                                .setName(plan.localName)\n                                .setCreateBranch(true)\n                                .setStartPoint(plan.remoteRef)\n                                .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK)\n                                .call()\n                            val remotePath = plan.remoteRef.removePrefix(Constants.R_REMOTES)\n                            val separator = remotePath.indexOf('/')\n                            check(separator > 0 && separator < remotePath.lastIndex) {\n                                "Invalid remote tracking ref: ${plan.remoteRef}"\n                            }\n                            val remoteName = remotePath.substring(0, separator)\n                            val remoteBranch = remotePath.substring(separator + 1)\n                            git.repository.config.apply {\n                                setString("branch", plan.localName, "remote", remoteName)\n                                setString("branch", plan.localName, "merge", Constants.R_HEADS + remoteBranch)\n                                save()\n                            }\n''',
)

print("CI_SOURCE_FIXES_APPLIED")
