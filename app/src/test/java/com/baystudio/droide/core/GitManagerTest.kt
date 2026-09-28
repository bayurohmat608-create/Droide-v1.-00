package com.baystudio.droide.core

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.eclipse.jgit.storage.file.FileRepositoryBuilder

class GitManagerTest {
    private lateinit var root: java.io.File
    private lateinit var git: GitManager

    @Before fun setUp() {
        root = Files.createTempDirectory("droide-git-").toFile()
        git = GitManager(root)
    }

    @After fun tearDown() {
        PathSecurity.deleteTreeNoFollow(root)
    }

    @Test fun initStageAndStatusRoundTrip() = runBlocking {
        assertFalse(git.isRepository())
        assertTrue(git.init().contains("Initialized"))
        assertTrue(git.isRepository())

        root.resolve("src").mkdirs()
        root.resolve("src/Main.kt").writeText("fun main() = Unit\n")
        assertTrue(git.stage("src/Main.kt").contains("Staged"))

        val changes = git.changes()
        assertTrue(changes.any { it.path == "src/Main.kt" && it.staged })
        assertTrue(git.status().contains("src/Main.kt"))
    }


    @Test fun pullAndPushRejectImportedUnsafeRemote() = runBlocking {
        git.init()
        val repo = FileRepositoryBuilder()
            .setGitDir(root.resolve(".git"))
            .setWorkTree(root)
            .build()
        repo.use {
            it.config.setString("remote", "origin", "url", "file:///tmp/unsafe.git")
            it.config.save()
        }

        assertTrue(git.pull().contains("blocked by security policy", ignoreCase = true))
        assertTrue(git.push().contains("blocked by security policy", ignoreCase = true))
    }

    @Test fun refusesSensitiveAndEscapingPaths() {
        runBlocking {
            git.init()
            root.resolve(".env").writeText("SECRET=value\n")
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { git.stage(".env") }
            }
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { git.stage("../escape.txt") }
            }
        }
    }
    @Test fun commitRequiresExplicitIdentityAndUsesConfiguredAuthor() = runBlocking {
        git.init()
        assertNull(git.identity())
        root.resolve("README.md").writeText("hello\n")
        git.stage("README.md")

        val missing = runCatching { git.commitStaged("initial") }.exceptionOrNull()
        assertTrue(missing?.message?.contains("Git identity is not configured") == true)

        assertTrue(git.configureIdentity("Bay Developer", "bay@example.com").contains("Bay Developer"))
        assertEquals(GitIdentity("Bay Developer", "bay@example.com"), git.identity())
        assertTrue(git.commitStaged("initial").contains("Committed"))

        val repo = FileRepositoryBuilder().setGitDir(root.resolve(".git")).setWorkTree(root).build()
        repo.use { opened ->
            val commit = org.eclipse.jgit.api.Git(opened).use { it.log().setMaxCount(1).call().first() }
            assertEquals("Bay Developer", commit.authorIdent.name)
            assertEquals("bay@example.com", commit.authorIdent.emailAddress)
            assertEquals("Bay Developer", commit.committerIdent.name)
            assertEquals("bay@example.com", commit.committerIdent.emailAddress)
        }
    }

    @Test fun identityValidationRejectsControlCharactersAndMalformedEmail() = runBlocking {
        git.init()
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { git.configureIdentity("Bad\nName", "bay@example.com") }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { git.configureIdentity("Bay", "not-an-email") }
        }
    }

    @Test fun githubPatIsNeverOfferedToNonGithubRemote() = runBlocking {
        git.init()
        val repo = FileRepositoryBuilder().setGitDir(root.resolve(".git")).setWorkTree(root).build()
        repo.use {
            it.config.setString("remote", "origin", "url", "https://example.com/owner/repo.git")
            it.config.save()
        }
        val result = git.fetch(token = "ghp_test_secret")
        assertTrue(result.contains("host-bound", ignoreCase = true))
        assertFalse(result.contains("ghp_test_secret"))
    }

    @Test fun githubPatCloneRejectsSshAndNonGithubBeforeNetwork() = runBlocking {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { git.clone("https://example.com/owner/repo.git", token = "secret") }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { git.clone("git@github.com:owner/repo.git", token = "secret") }
        }
    }

}
