package com.baystudio.droide.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GitBranchSwitchAuthorityTest {
    private fun inventory(
        local: Set<String> = emptySet(),
        remote: Set<String> = emptySet(),
    ) = GitBranchSwitchAuthority.Inventory(local, remote)

    @Test fun existingLocalWinsWithoutRemoteGuessing() {
        assertEquals(
            GitBranchSwitchAuthority.Plan.ExistingLocal("feature/ui"),
            GitBranchSwitchAuthority.plan(
                "feature/ui",
                create = false,
                inventory(local = setOf("main", "feature/ui"), remote = setOf("refs/remotes/origin/feature/ui")),
            ),
        )
    }

    @Test fun uniqueRemoteTailCreatesTrackingLocalBranch() {
        assertEquals(
            GitBranchSwitchAuthority.Plan.TrackRemote("feature", "refs/remotes/origin/feature"),
            GitBranchSwitchAuthority.plan(
                "feature",
                create = false,
                inventory(local = setOf("main"), remote = setOf("refs/remotes/origin/feature")),
            ),
        )
    }

    @Test fun explicitRemoteMapsToSafeLocalTrackingName() {
        assertEquals(
            GitBranchSwitchAuthority.Plan.TrackRemote("feature/ui", "refs/remotes/upstream/feature/ui"),
            GitBranchSwitchAuthority.plan(
                "upstream/feature/ui",
                create = false,
                inventory(remote = setOf("refs/remotes/upstream/feature/ui")),
            ),
        )
    }

    @Test fun ambiguousRemoteTailFailsClosed() {
        assertThrows(IllegalArgumentException::class.java) {
            GitBranchSwitchAuthority.plan(
                "feature",
                create = false,
                inventory(remote = setOf("refs/remotes/origin/feature", "refs/remotes/upstream/feature")),
            )
        }
    }

    @Test fun rawRefsAndDuplicateCreateFailClosed() {
        assertThrows(IllegalArgumentException::class.java) {
            GitBranchSwitchAuthority.plan("refs/heads/main", false, inventory(local = setOf("main")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            GitBranchSwitchAuthority.plan("main", true, inventory(local = setOf("main")))
        }
    }
}
