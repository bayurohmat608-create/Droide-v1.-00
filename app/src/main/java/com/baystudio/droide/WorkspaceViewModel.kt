package com.baystudio.droide

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.baystudio.droide.core.EditorRecoveryRequest
import com.baystudio.droide.core.ModelsDevCatalog
import com.baystudio.droide.core.WorkspaceCoordinator
import com.baystudio.droide.core.WorkspaceRuntime
import com.baystudio.droide.ui.EditorWorkspaceState
import com.baystudio.droide.ui.ExplorerWorkspaceState
import com.baystudio.droide.ui.EditorWorkspaceDocumentAuthority
import com.baystudio.droide.core.runSuspendCatching
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

// Rotation must not restart terminals, agent sessions, approvals, or the active project.



class WorkspaceViewModel(application: Application) : AndroidViewModel(application) {
    private val runtimeJob = SupervisorJob()
    private val runtimeScope = CoroutineScope(runtimeJob + Dispatchers.Main.immediate)
    private var initJob: Job? = null

    val coordinator = WorkspaceCoordinator(application.applicationContext, runtimeScope)

    private val _initError = MutableStateFlow<String?>(null)
    val initError: StateFlow<String?> = _initError

    // Keep it in the retained ViewModel rather than Bundle/rememberSaveable so rotation and window resizing cannot drop unsaved text.


    private var editorRuntime: WorkspaceRuntime? = null
    private var retainedEditorState = EditorWorkspaceState()
    private val retainedExplorerStates = linkedMapOf<String, ExplorerWorkspaceState>()
    private var editorAuthorityBinding: com.baystudio.droide.core.WorkspaceDocumentAuthorityBridge.Binding? = null

    fun editorStateFor(runtime: WorkspaceRuntime): EditorWorkspaceState {
        if (editorRuntime !== runtime) {
            editorAuthorityBinding?.close()
            editorRuntime = runtime
            retainedEditorState = EditorWorkspaceState()
            editorAuthorityBinding = runtime.documentAuthority.bind(
                EditorWorkspaceDocumentAuthority(retainedEditorState, runtime.files, runtime.lsp)
            )
        } else if (!runtime.documentAuthority.isBound) {
            editorAuthorityBinding = runtime.documentAuthority.bind(
                EditorWorkspaceDocumentAuthority(retainedEditorState, runtime.files, runtime.lsp)
            )
        }
        return retainedEditorState
    }

     
    fun explorerStateFor(runtime: WorkspaceRuntime): ExplorerWorkspaceState {
        val key = runtime.project.id
        retainedExplorerStates[key]?.let { return it }
        val created = ExplorerWorkspaceState()
        retainedExplorerStates[key] = created
        

        while (retainedExplorerStates.size > 12) {
            retainedExplorerStates.remove(retainedExplorerStates.keys.first())
        }
        return created
    }

    init {
        runtimeScope.launch(Dispatchers.IO) { runSuspendCatching { ModelsDevCatalog.initialize(application.applicationContext) } }
        initializeWorkspace()
    }

    fun initializeWorkspace() {
        if (initJob?.isActive == true) return
        initJob = runtimeScope.launch {
            _initError.value = null
            runSuspendCatching { coordinator.init() }
                .onFailure { _initError.value = it.message ?: "Workspace initialization failed" }
        }
    }

    fun persistEditorRecovery(request: EditorRecoveryRequest) {
        runtimeScope.launch {
            runSuspendCatching {
                if (request.snapshot == null) request.store.clear() else request.store.save(request.snapshot)
            }
        }
    }

     
    fun persistTerminalRecovery() {
        runtimeScope.launch {
            runSuspendCatching { coordinator.runtime.value?.terminals?.flushReviveState() }
        }
    }

    override fun onCleared() {
        editorAuthorityBinding?.close()
        editorAuthorityBinding = null
        

        coordinator.close(preserveLocalTerminals = true)
        runtimeScope.cancel()
    }
}
