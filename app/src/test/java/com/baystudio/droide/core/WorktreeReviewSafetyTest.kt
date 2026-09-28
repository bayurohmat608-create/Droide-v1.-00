package com.baystudio.droide.core

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorktreeReviewSafetyTest {
    @Test fun conflictingMergeReportsVerifiedRollbackAndKeepsParentCommit() = runBlocking {
        val root = Files.createTempDirectory("droide-worktree-conflict-").toFile()
        try {
            Git.init().setDirectory(root).call().use { parent ->
                root.resolve(".gitignore").writeText(".droide/\n")
                root.resolve("note.txt").writeText("base\n")
                parent.add().addFilepattern(".").call()
                parent.commit().setMessage("initial").setAuthor("Test", "test@example.org")
                    .setCommitter("Test", "test@example.org").call()
                parent.repository.config.apply {
                    setString("user", null, "name", "Test")
                    setString("user", null, "email", "test@example.org")
                    save()
                }
            }
            val manager = WorktreeManager(root)
            val info = manager.provision("conflict", "conflict")
            Git.open(manager.directory(info.name)!!).use { child ->
                manager.directory(info.name)!!.resolve("note.txt").writeText("child\n")
                child.add().addFilepattern("note.txt").call()
                child.commit().setMessage("child").setAuthor("Test", "test@example.org")
                    .setCommitter("Test", "test@example.org").call()
            }
            val parentHead = Git.open(root).use { parent ->
                root.resolve("note.txt").writeText("parent\n")
                parent.add().addFilepattern("note.txt").call()
                parent.commit().setMessage("parent").setAuthor("Test", "test@example.org")
                    .setCommitter("Test", "test@example.org").call().name
            }
            val outcome = manager.merge(info.name, info.baseCommit, info.parentBranch,
                info.parentRepositoryIdentity, info.branch, "merge conflict")
            assertFalse(outcome.merged)
            assertTrue(outcome.message.contains("Parent HEAD and tracked working tree were restored"))
            Git.open(root).use { parent ->
                assertTrue(parent.repository.resolve("HEAD")?.name == parentHead)
                assertTrue(parent.status().call().isClean)
            }
            assertTrue(root.resolve("note.txt").readText() == "parent\n")
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun reviewIncludesCommittedChangesButRedactsSensitivePathsAndDisclosesMissingBaseline() = runBlocking {
        val root = Files.createTempDirectory("droide-worktree-review-").toFile()
        try {
            Git.init().setDirectory(root).call().use { parent ->
                root.resolve(".gitignore").writeText(".droide/\n")
                root.resolve("note.txt").writeText("before\n")
                parent.add().addFilepattern(".").call()
                parent.commit().setMessage("initial").setAuthor("Test", "test@example.org")
                    .setCommitter("Test", "test@example.org").call()
            }
            val manager = WorktreeManager(root)
            val info = manager.provision("review", "review")
            val child = manager.directory(info.name)!!
            Git.open(child).use { git ->
                assertTrue(git.repository.config.getString("droideIsolated", null, "baseCommit") == info.baseCommit)
                child.resolve("note.txt").writeText("committed child change\n")
                git.add().addFilepattern("note.txt").call()
                git.commit().setMessage("child change").setAuthor("Test", "test@example.org")
                    .setCommitter("Test", "test@example.org").call()
                child.resolve(".env").writeText("SECRET=example\n")
            }
            val review = manager.reviewChanges(info.name)
            assertTrue(review.contains("committed since isolated base"))
            assertTrue(review.contains("committed child change"))
            assertTrue(review.contains("sensitive path(s) hidden"))
            assertFalse(review.contains(".env"))
            assertFalse(review.contains("SECRET=example"))

            Git.open(child).use { git ->
                git.repository.config.unset("droideIsolated", null, "baseCommit")
                git.repository.config.save()
            }
            assertTrue(manager.reviewChanges(info.name).contains("Committed changes cannot be reviewed"))
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }

    @Test fun largeTrackedPatchHasBoundedPreviewAndVisibleTruncationNotice() = runBlocking {
        val root = Files.createTempDirectory("droide-worktree-patch-").toFile()
        try {
            Git.init().setDirectory(root).call().use { parent ->
                root.resolve(".gitignore").writeText(".droide/\n")
                root.resolve("note.txt").writeText("before\n")
                parent.add().addFilepattern(".").call()
                parent.commit().setMessage("initial").setAuthor("Test", "test@example.org")
                    .setCommitter("Test", "test@example.org").call()
            }
            val manager = WorktreeManager(root)
            val info = manager.provision("large", "large")
            manager.directory(info.name)!!.resolve("note.txt").writeText(
                (0 until 600).joinToString("\n") { index -> "changed line $index with visible unique content $index" } + "\n"
            )
            val review = manager.reviewChanges(info.name)
            assertTrue(review.length <= 16_000)
            assertTrue(review.contains("Preview limited to 12,000 bytes"))
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}
