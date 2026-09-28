package com.baystudio.droide.core

import org.junit.Assert.assertThrows
import org.junit.Test

class LocalLlamaRuntimeContractTest {
    @Test fun pinnedContractIsInternallyValid() {
        LocalLlamaRuntimeContract.validate()
        LocalLlamaRuntimeContract.requireCompatibleTarget("arm64-v8a", 30)
        LocalLlamaRuntimeContract.requireArchivePath(LocalLlamaRuntimeContract.CLI_RELATIVE_PATH)
        LocalLlamaRuntimeContract.requireArchivePath(LocalLlamaRuntimeContract.SERVER_RELATIVE_PATH)
    }

    @Test fun incompatibleTargetFailsClosed() {
        assertThrows(IllegalArgumentException::class.java) {
            LocalLlamaRuntimeContract.requireCompatibleTarget("x86_64", 36)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LocalLlamaRuntimeContract.requireCompatibleTarget("arm64-v8a", 27)
        }
    }

    @Test fun archiveEscapeFailsClosed() {
        assertThrows(IllegalArgumentException::class.java) {
            LocalLlamaRuntimeContract.requireArchivePath("../llama-cli")
        }
        assertThrows(IllegalArgumentException::class.java) {
            LocalLlamaRuntimeContract.requireArchivePath("other/llama-cli")
        }
    }
}
