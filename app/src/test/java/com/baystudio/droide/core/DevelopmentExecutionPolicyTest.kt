package com.baystudio.droide.core

import org.junit.Assert.assertEquals
import org.junit.Test

class DevelopmentExecutionPolicyTest {
    @Test fun interactiveDeveloperOperationsUseSpecialUseForeground() {
        listOf(
            DevelopmentExecutionPolicy.Workload.BUILD,
            DevelopmentExecutionPolicy.Workload.TEST,
            DevelopmentExecutionPolicy.Workload.LINT,
            DevelopmentExecutionPolicy.Workload.INSTALL_RUN,
            DevelopmentExecutionPolicy.Workload.DEBUG,
            DevelopmentExecutionPolicy.Workload.VM,
        ).forEach { workload ->
            assertEquals(
                DevelopmentExecutionPolicy.Mode.SPECIAL_USE_FOREGROUND,
                DevelopmentExecutionPolicy.modeFor(workload, 36),
            )
        }
    }

    @Test fun packageTransferUsesUidtOnlyOnAndroid14Plus() {
        assertEquals(
            DevelopmentExecutionPolicy.Mode.VISIBLE_TRANSFER,
            DevelopmentExecutionPolicy.modeFor(DevelopmentExecutionPolicy.Workload.PACKAGE_TRANSFER, 33),
        )
        assertEquals(
            DevelopmentExecutionPolicy.Mode.USER_INITIATED_DATA_TRANSFER,
            DevelopmentExecutionPolicy.modeFor(DevelopmentExecutionPolicy.Workload.PACKAGE_TRANSFER, 34),
        )
    }

    @Test fun idleInteractiveToolsDoNotAutoPromoteToForegroundService() {
        listOf(
            DevelopmentExecutionPolicy.Workload.RUN_FILE,
            DevelopmentExecutionPolicy.Workload.CONTRIBUTED_TOOL,
            DevelopmentExecutionPolicy.Workload.LSP,
            DevelopmentExecutionPolicy.Workload.TERMINAL,
        ).forEach { workload ->
            assertEquals(
                DevelopmentExecutionPolicy.Mode.RETAINED_PROCESS,
                DevelopmentExecutionPolicy.modeFor(workload, 36),
            )
        }
    }
}
