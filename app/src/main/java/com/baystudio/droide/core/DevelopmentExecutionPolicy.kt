package com.baystudio.droide.core


object DevelopmentExecutionPolicy {
    enum class Workload {
        BUILD,
        TEST,
        LINT,
        INSTALL_RUN,
        DEBUG,
        VM,
        PACKAGE_TRANSFER,
        RUN_FILE,
        CONTRIBUTED_TOOL,
        LSP,
        TERMINAL,
    }

    enum class Mode {
        
        SPECIAL_USE_FOREGROUND,

        
        USER_INITIATED_DATA_TRANSFER,

        
        VISIBLE_TRANSFER,

        
        RETAINED_PROCESS,
    }

    fun modeFor(workload: Workload, apiLevel: Int): Mode = when (workload) {
        Workload.BUILD,
        Workload.TEST,
        Workload.LINT,
        Workload.INSTALL_RUN,
        Workload.DEBUG,
        Workload.VM,
        -> Mode.SPECIAL_USE_FOREGROUND

        Workload.PACKAGE_TRANSFER -> if (apiLevel >= 34) {
            Mode.USER_INITIATED_DATA_TRANSFER
        } else {
            Mode.VISIBLE_TRANSFER
        }

        Workload.RUN_FILE,
        Workload.CONTRIBUTED_TOOL,
        Workload.LSP,
        Workload.TERMINAL,
        -> Mode.RETAINED_PROCESS
    }
}
