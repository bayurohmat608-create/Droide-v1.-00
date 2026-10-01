package com.baystudio.droide.core

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GitBranchSwitchIntegrationTest {
    private lateinit var root: java.io.File
    private lateinit var authority: RecordingDocumentAuthority
    private lateinit var git: GitManager

    @Before fun setUp() {
        root = Files.createTempDirectory("droide-branch-switch-").toFile()
        authority = RecordingDocumentAuthority()
        git = GitManager(root, authority)
    }

    @After fun tearDown() {
        PathSecurity.deleteTreeNoFollow(root)
    }

    @Test fun dirtyLiveBufferBlocksRemoteTrackingCheckoutThenCleanCheckoutReconciles() = runBlocking {
        git.init()
        git.configureIdentity("Droide Test", "droide@example.com")
        root.resolve("README.md").writeText("main\n")
        git.stage("README.md")
        git.commitStaged("initial")

        FileRepositoryBuilder().setGitDir(root.resolve(".git")).setWorkTree(root).build().use { repo ->
            val head = repo.resolve(Constants.HEAD)
            repo.updateRef("refs/remotes/origin/feature").apply {
                setNewObjectId(head)
                setForceUpdate(true)
            }.update()
        }

        authority.dirty = true
        val blocked = git.checkout("feature")
        assertTrue(blocked.contains("Save or discard unsaved editor changes"))
        FileRepositoryBuilder().setGitDir(root.resolve(".git")).setWorkTree(root).build().use { repo ->
            assertNull(repo.findRef("refs/heads/feature"))
        }

        authority.dirty = false
        val switched = git.checkout("feature")
        assertTrue(switched.contains("tracking origin/feature"))
        assertTrue(authority.reconciled)
        FileRepositoryBuilder().setGitDir(root.resolve(".git")).setWorkTree(root).build().use { repo ->
            assertEquals("refs/heads/feature", repo.fullBranch)
            assertEquals("origin", repo.config.getString("branch", "feature", "remote"))
            assertEquals("refs/heads/feature", repo.config.getString("branch", "feature", "merge"))
        }
    }

    private class RecordingDocumentAuthority : WorkspaceDocumentAuthority {
        var dirty = false
        var reconciled = false

        override suspend fun snapshot(path: String): WorkspaceDocumentSnapshot? = null

        override suspend fun snapshots(): List<WorkspaceDocumentSnapshot> = if (!dirty) emptyList() else listOf(
            WorkspaceDocumentSnapshot(
                path = "README.md",
                content = "unsaved\n",
                loaded = true,
                kind = WorkspaceDocumentKind.TEXT,
                dirty = true,
                editable = true,
                revision = 1,
                changeVersion = 1,
                selectionStart = 0,
                selectionEnd = 0,
                savedContentLength = 5,
            )
        )

        override suspend fun reconcilePersistedWorkspace(): WorkspacePersistedReconciliation {
            reconciled = true
            return WorkspacePersistedReconciliation(refreshed = listOf("README.md"))
        }
    }
}
