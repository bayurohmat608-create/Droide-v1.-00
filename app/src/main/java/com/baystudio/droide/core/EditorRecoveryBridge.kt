package com.baystudio.droide.core

 
data class EditorRecoveryRequest(
    val store: EditorStateStore,
    val snapshot: EditorRecoverySnapshot?,
)





class EditorRecoveryBridge {
    private var owner: Any? = null
    private var provider: (() -> EditorRecoveryRequest)? = null

    @Synchronized
    fun install(owner: Any, provider: () -> EditorRecoveryRequest) {
        this.owner = owner
        this.provider = provider
    }

    @Synchronized
    fun uninstall(owner: Any) {
        if (this.owner === owner) {
            this.owner = null
            provider = null
        }
    }

    @Synchronized
    fun captureRequest(): EditorRecoveryRequest? {
        val current = provider ?: return null
        

        return try {
            current()
        } catch (_: Exception) {
            null
        }
    }
}
