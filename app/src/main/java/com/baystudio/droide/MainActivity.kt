package com.baystudio.droide

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.baystudio.droide.core.EditorRecoveryBridge
import com.baystudio.droide.core.SafMirrorPhase
import com.baystudio.droide.core.SafMirrorProgress
import com.baystudio.droide.core.runSuspendCatching
import com.baystudio.droide.ui.IdeScreen
import com.baystudio.droide.ui.DroideTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var workspaceModel: WorkspaceViewModel
    private val editorRecoveryBridge = EditorRecoveryBridge()
    private val folderImportProgress = MutableStateFlow<SafMirrorProgress?>(null)
    private var folderImportJob: Job? = null
    private var hidePortraitEditorStatusBar = false

    private val pickTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            val permission = runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            if (permission.isFailure) {
                Toast.makeText(this, "Cannot persist folder access: ${permission.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                return@registerForActivityResult
            }
            if (folderImportJob?.isActive == true) return@registerForActivityResult
            folderImportProgress.value = SafMirrorProgress(SafMirrorPhase.PREPARING)
            folderImportJob = lifecycleScope.launch {
                try {
                    workspaceModel.coordinator.addSafProject(
                        uri = uri,
                        onProgress = { progress -> folderImportProgress.value = progress },
                    )
                } catch (_: CancellationException) {
                    

                } catch (failure: Throwable) {
                    Toast.makeText(this@MainActivity, "Cannot open project: ${failure.message}", Toast.LENGTH_LONG).show()
                } finally {
                    folderImportProgress.value = null
                    folderImportJob = null
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        workspaceModel = ViewModelProvider(this)[WorkspaceViewModel::class.java]
        applyDroideOrientationStatusBar(window, resources.configuration.orientation, hidePortraitEditorStatusBar)
        handleGitHubOAuthCallback(intent)

        setContent {
            val runtime by workspaceModel.coordinator.runtime.collectAsState()
            val error by workspaceModel.initError.collectAsState()
            val importingFolder by folderImportProgress.collectAsState()
            DroideTheme {
                Surface(Modifier.fillMaxSize()) {
                    val rt = runtime
                    if (rt == null) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            if (error == null) CircularProgressIndicator() else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Cannot initialize workspace", style = MaterialTheme.typography.titleMedium)
                                Text(error ?: "Unknown error", style = MaterialTheme.typography.bodySmall)
                                Button(onClick = { workspaceModel.initializeWorkspace() }) { Text("Retry") }
                            }
                        }
                    } else {


                        key(rt.project.id, rt.files.root.canonicalPath) {
                            IdeScreen(
                                files = rt.files,
                                documents = rt.documentAuthority,
                                terminalManager = rt.terminals,
                                git = rt.git,
                                lsp = rt.lsp,
                                debugger = rt.debugger,
                                agent = rt.agent,
                                agentBrowser = rt.agentBrowser,
                                agentPlugins = rt.agentPlugins,
                                githubAccount = workspaceModel.coordinator.githubAccount,
                                providerConnections = workspaceModel.coordinator.providerConnections,
                                localLlamaModels = workspaceModel.coordinator.localLlamaAgentModels,
                                androidDevelopment = rt.androidDevelopment,
                                extensions = rt.extensions,
                                execution = rt.execution,
                                deviceBridge = workspaceModel.coordinator.deviceBridge,
                                approvals = workspaceModel.coordinator.approvals(),
                                projectManager = workspaceModel.coordinator.projects,
                                recoveryBridge = editorRecoveryBridge,
                                editorState = workspaceModel.editorStateFor(rt),
                                explorerState = workspaceModel.explorerStateFor(rt),
                                onPortraitEditorStatusBarHiddenChanged = { hidden ->
                                    if (hidePortraitEditorStatusBar != hidden) {
                                        hidePortraitEditorStatusBar = hidden
                                        applyDroideOrientationStatusBar(window, resources.configuration.orientation, hidden)
                                    }
                                },
                                onPickSaf = { pickTree.launch(null) },
                                safLinked = rt.safMirror != null,
                                onReconcileSaf = { workspaceModel.coordinator.reconcileActiveSaf() },
                                onProjectSwitch = { id ->
                                    lifecycleScope.launch {
                                        runSuspendCatching { workspaceModel.coordinator.switchProject(id) }
                                            .onFailure { Toast.makeText(this@MainActivity, "Cannot switch project: ${it.message}", Toast.LENGTH_LONG).show() }
                                    }
                                },
                                onProjectCreate = { name, templateId ->
                                    workspaceModel.coordinator.createProjectFromTemplateAndSwitch(name, templateId)
                                },
                                onPermissionPolicyChange = { policy -> workspaceModel.coordinator.replaceActivePermissionPolicy(policy) },
                            )
                        }
                    }
                    importingFolder?.let { progress ->
                        FolderImportProgressDialog(
                            progress = progress,
                            onCancel = {
                                if (progress.cancellable) {
                                    folderImportProgress.value = progress.copy(phase = SafMirrorPhase.CANCELLING)
                                    folderImportJob?.cancel()
                                }
                            },
                        )
                    }
                }
            }
        }
    }


    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleGitHubOAuthCallback(intent)
    }

    private fun handleGitHubOAuthCallback(intent: Intent?) {
        if (intent?.action != GitHubOAuthCallbackActivity.ACTION_GITHUB_OAUTH_CALLBACK) return
        val callback = intent.data?.toString() ?: return
        // Activity recreation must not replay a one-shot authorization code after GitHubAccountManager has already claimed it.

        intent.action = null
        intent.data = null
        lifecycleScope.launch { workspaceModel.coordinator.githubAccount.handleCallback(callback) }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            

            applyDroideOrientationStatusBar(window, resources.configuration.orientation, hidePortraitEditorStatusBar)
        }
    }

    override fun onStop() {
        

        try {
            editorRecoveryBridge.captureRequest()?.let(workspaceModel::persistEditorRecovery)
            workspaceModel.persistTerminalRecovery()
        } catch (error: Exception) {
            Log.w("DroideLifecycle", "Best-effort onStop recovery skipped", error)
        } finally {
            super.onStop()
        }
    }
}

@Composable
private fun FolderImportProgressDialog(
    progress: SafMirrorProgress,
    onCancel: () -> Unit,
) {
    val phaseLabel = when (progress.phase) {
        SafMirrorPhase.PREPARING -> "Preparing folder…"
        SafMirrorPhase.COPYING_EXTERNAL -> "Importing folder…"
        SafMirrorPhase.SCANNING_LOCAL -> "Checking workspace…"
        SafMirrorPhase.RESOLVING -> "Resolving changes…"
        SafMirrorPhase.CANCELLING -> "Cancelling safely…"
        SafMirrorPhase.APPLYING -> "Applying changes safely…"
        SafMirrorPhase.FINALIZING -> "Finishing safely…"
    }
    val detail = when (progress.phase) {
        SafMirrorPhase.COPYING_EXTERNAL -> "${progress.filesProcessed} files · ${formatImportBytes(progress.bytesProcessed)} copied"
        SafMirrorPhase.SCANNING_LOCAL -> "${progress.filesProcessed} files · ${formatImportBytes(progress.bytesProcessed)} checked"
        SafMirrorPhase.RESOLVING,
        SafMirrorPhase.APPLYING,
        SafMirrorPhase.FINALIZING -> "${progress.filesProcessed} files · ${formatImportBytes(progress.bytesProcessed)} prepared"
        else -> "Droide is keeping the workspace responsive while this runs."
    }

    AlertDialog(
        onDismissRequest = {},
        title = { Text("Opening folder") },
        text = {
            Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                Text(phaseLabel, style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(Modifier.fillMaxWidth().height(4.dp))
                Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                progress.currentPath?.let { path ->
                    Text(
                        path,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            if (progress.cancellable) TextButton(onClick = onCancel) { Text("Cancel") }
        },
    )
}

private fun formatImportBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> java.lang.String.format(java.util.Locale.US, "%.2f GiB", bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> java.lang.String.format(java.util.Locale.US, "%.1f MiB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> java.lang.String.format(java.util.Locale.US, "%.1f KiB", bytes / 1024.0)
    else -> "$bytes B"
}
