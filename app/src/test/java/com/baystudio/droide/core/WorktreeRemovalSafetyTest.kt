package com.baystudio.droide.core

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WorktreeRemovalSafetyTest {
    @Test fun defaultRemoveRefusesCleanUnmergedCommits() = runBlocking {
        val root = Files.createTempDirectory("droide-worktree-remove-").toFile()
        try {
            Git.init().setDirectory(root).call().use { parent ->
                root.resolve("readme.txt").writeText("initial")
                parent.add().addFilepattern("readme.txt").call()
                parent.commit().setMessage("initial").setAuthor("Test", "test@example.org")
                    .setCommitter("Test", "test@example.org").call()
            }
            val manager = WorktreeManager(root)
            val child = manager.provision("review", "review")
            Git.open(manager.directory(child.name)!!).use { git ->
                manager.directory(child.name)!!.resolve("readme.txt").writeText("changed")
                git.add().addFilepattern("readme.txt").call()
                git.commit().setMessage("child change").setAuthor("Test", "test@example.org")
                    .setCommitter("Test", "test@example.org").call()
            }
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { manager.remove(child.name) }
            }
            assertTrue(manager.directory(child.name)!!.isDirectory)
            assertTrue(manager.remove(child.name, force = true).startsWith("Removed"))
            assertFalse(manager.directory(child.name)?.exists() == true)
        } finally { PathSecurity.deleteTreeNoFollow(root) }
    }
}
