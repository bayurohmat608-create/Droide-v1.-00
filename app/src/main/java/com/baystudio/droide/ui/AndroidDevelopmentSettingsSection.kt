package com.baystudio.droide.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.baystudio.droide.core.AndroidDevelopmentManager
import com.baystudio.droide.core.AndroidSdkLicense
import com.baystudio.droide.core.AndroidSdkLicenseManager
import com.baystudio.droide.core.AndroidToolchainCatalog
import com.baystudio.droide.core.AndroidToolchainCatalogDocument
import com.baystudio.droide.core.AndroidToolchainCatalogEntry
import com.baystudio.droide.core.AndroidToolchainCandidateManager
import com.baystudio.droide.core.AndroidToolchainCertificationManager
import com.baystudio.droide.core.DeviceBridgeManager
import com.baystudio.droide.core.ManagedAndroidToolchainInstaller
import com.baystudio.droide.core.runSuspendCatching
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

 
@Composable
fun AndroidDevelopmentSettingsSection(
    manager: AndroidDevelopmentManager,
    bridge: DeviceBridgeManager,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bridgeState by bridge.state.collectAsState()
    val devState by manager.status.collectAsState()
    val operation by manager.operation.collectAsState()
    val licenseManager = remember(context) { AndroidSdkLicenseManager(context) }
    val managedInstaller = remember(context, manager, licenseManager) { ManagedAndroidToolchainInstaller(context, manager, licenseManager) }
    val managedInstallState by managedInstaller.state.collectAsState()
    val candidateManager = remember(context, manager) { AndroidToolchainCandidateManager(context, manager) }
    val certificationManager = remember(context, manager, bridge) { AndroidToolchainCertificationManager(context, manager, bridge) }
    val certificationState by certificationManager.state.collectAsState()
    var candidate by remember { mutableStateOf<AndroidToolchainCandidateManager.Candidate?>(null) }
    var candidateJob by remember { mutableStateOf<Job?>(null) }
    var certificationJob by remember { mutableStateOf<Job?>(null) }
    var candidateLicense by remember { mutableStateOf<AndroidSdkLicense?>(null) }
    var candidateLicenseAccepted by remember { mutableStateOf(false) }
    var showCandidateLicense by remember { mutableStateOf(false) }
    var confirmCandidateInstall by remember { mutableStateOf(false) }
    var toolchainCatalog by remember { mutableStateOf<AndroidToolchainCatalogDocument?>(null) }
    var toolchainCatalogError by remember { mutableStateOf<String?>(null) }
    var catalogEntry by remember { mutableStateOf<AndroidToolchainCatalogEntry?>(null) }
    var installJob by remember { mutableStateOf<Job?>(null) }
    var downloadCached by remember { mutableStateOf(false) }
    var pairingCode by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var sdkLicense by remember { mutableStateOf<AndroidSdkLicense?>(null) }
    var sdkLicenseAccepted by remember { mutableStateOf(false) }
    var showLicense by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var confirmClearCaches by remember { mutableStateOf(false) }
    var storageUsage by remember { mutableStateOf<AndroidDevelopmentManager.RemoteStorageUsage?>(null) }
    var workstationInfo by remember { mutableStateOf<AndroidDevelopmentManager.WorkstationInfo?>(null) }
    var workstationInstalledVersion by remember { mutableStateOf<String?>(null) }
    val controlsBusy = busy || operation.running || managedInstallState.running || certificationState.running || candidateJob?.isActive == true

    fun formatBytes(bytes: Long): String {
        if (bytes < 1024L) return "${bytes} B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unit = -1
        while (value >= 1024.0 && unit < units.lastIndex) {
            value /= 1024.0
            unit++
        }
        return if (value >= 100.0 || value % 1.0 == 0.0) "%.0f %s".format(value, units[unit]) else "%.1f %s".format(value, units[unit])
    }

    fun refreshStorageUsage() {
        if (bridgeState.connected == null) {
            storageUsage = null
            return
        }
        scope.launch {
            runSuspendCatching { manager.remoteStorageUsage() }
                .onSuccess { storageUsage = it }
                .onFailure { message = "Storage check failed: ${it.message}" }
        }
    }

    fun hasNearbyPermission(): Boolean = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED

    fun discover() {
        if (!hasNearbyPermission()) return
        bridge.startDiscovery()
        scope.launch { runSuspendCatching { manager.refresh() } }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) discover() else message = "Nearby devices permission is required for the local Wireless Debugging bridge."
    }

    val candidatePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            candidateJob = scope.launch {
                val result = runSuspendCatching { candidateManager.import(uri) }
                if (result.isSuccess) {
                    candidate = result.getOrThrow()
                    candidateLicense = null
                    candidateLicenseAccepted = false
                    message = "Candidate imported. Review its SHA-256, provenance and SDK license before installation."
                } else {
                    message = "Candidate import failed: ${result.exceptionOrNull()?.message}"
                }
                candidateJob = null
            }
        }
    }

    val certificationExporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val result = runSuspendCatching {
                    context.contentResolver.openOutputStream(uri, "w")?.use { certificationManager.exportLatest(it) }
                        ?: error("Could not open certification report destination")
                }
                message = if (result.isSuccess) "Certification report exported." else "Report export failed: ${result.exceptionOrNull()?.message}"
            }
        }
    }

    LaunchedEffect(manager) { runSuspendCatching { manager.refresh() } }
    LaunchedEffect(manager, devState.compileSdk) {
        val loaded = runCatching { AndroidToolchainCatalog.load(context) }
        if (loaded.isSuccess) {
            val document = loaded.getOrThrow()
            toolchainCatalog = document
            toolchainCatalogError = null
            catalogEntry = AndroidToolchainCatalog.select(
                document = document,
                requiredCompileSdk = devState.compileSdk ?: 36,
                supportedAbis = Build.SUPPORTED_ABIS.toList(),
            )
            downloadCached = catalogEntry?.let { entry ->
                runSuspendCatching { managedInstaller.isDownloadCached(entry) }.getOrDefault(false)
            } ?: false
        } else {
            toolchainCatalog = null
            catalogEntry = null
            toolchainCatalogError = loaded.exceptionOrNull()?.message ?: "Toolchain catalog could not be loaded"
        }
    }
    LaunchedEffect(bridgeState.connected, operation.phase) {
        if (bridgeState.connected != null) {
            runSuspendCatching { manager.remoteStorageUsage() }.onSuccess { storageUsage = it }
            workstationInfo = runSuspendCatching { manager.workstationInfo() }.getOrNull()
            workstationInstalledVersion = runSuspendCatching { manager.installedToolchainManifest()?.version }.getOrNull()
        } else {
            storageUsage = null
            workstationInfo = null
            workstationInstalledVersion = null
        }
    }

    HorizontalDivider()
    Text("Android Development", style = MaterialTheme.typography.labelLarge)
    Text(
        "Build Android projects in local Ubuntu; Wireless Debugging is used for Device Workstation, install, run, and device debugging.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(devState.message, style = MaterialTheme.typography.bodySmall)
    if (!bridgeState.supported) {
        Text(
            "Android 10: Local Ubuntu build remains available when its JDK/SDK host tools are healthy. Device Workstation pairing, install/run, and device debugging require Android 11+.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    devState.toolchainVersion?.let { Text("Toolchain $it", style = MaterialTheme.typography.bodySmall) }
    if (operation.message.isNotBlank()) {
        Text(operation.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        if (operation.running) {
            operation.progress?.let { progress ->
                Text("${(progress * 100f).toInt()}% · ${operation.completedItems}/${operation.totalItems}", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    if (managedInstallState.message.isNotBlank()) {
        Text(managedInstallState.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        managedInstallState.fraction?.let { progress ->
            Text("${(progress * 100f).toInt()}% · ${formatBytes(managedInstallState.bytesDownloaded)} / ${formatBytes(managedInstallState.totalBytes ?: 0L)}", style = MaterialTheme.typography.labelSmall)
        }
    }
    message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled = !controlsBusy, onClick = {
            scope.launch {
                busy = true
                runSuspendCatching { manager.refresh() }
                    .onSuccess { message = it.message }
                    .onFailure { message = "Build environment verification failed: ${it.message}" }
                busy = false
            }
        }) { Text("Verify build environment") }
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            enabled = !controlsBusy && Build.VERSION.SDK_INT >= 30,
            onClick = {
                if (hasNearbyPermission()) discover()
                else permissionLauncher.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
            },
        ) { Text(if (bridgeState.discovering) "Discovering…" else "Discover") }
        OutlinedButton(onClick = {
            runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { message = "Developer options could not be opened: ${it.message}" }
        }) { Text("Developer options") }
    }

    bridgeState.connected?.let { endpoint ->
        Text("Connected: ${endpoint.name} · ${endpoint.host}:${endpoint.port}", style = MaterialTheme.typography.bodySmall)
    }

    if (bridgeState.pairEndpoints.isNotEmpty()) {
        Text("Pair this device", style = MaterialTheme.typography.labelMedium)
        OutlinedTextField(
            value = pairingCode,
            onValueChange = { pairingCode = it.filter(Char::isDigit).take(6) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("6-digit pairing code") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        bridgeState.pairEndpoints.take(4).forEach { endpoint ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(endpoint.name, style = MaterialTheme.typography.bodyMedium)
                    Text("${endpoint.host}:${endpoint.port}", style = MaterialTheme.typography.bodySmall)
                }
                Button(enabled = pairingCode.length == 6 && !controlsBusy, onClick = {
                    busy = true
                    scope.launch {
                        runSuspendCatching { bridge.pair(endpoint, pairingCode) }
                            .onSuccess { message = "Paired. Waiting for the Wireless Debugging connect endpoint…"; pairingCode = "" }
                            .onFailure { message = "Pairing failed: ${it.message}" }
                        busy = false
                    }
                }) { Text("Pair") }
            }
        }
    }

    if (bridgeState.connectEndpoints.isNotEmpty()) {
        Text("Available device bridge", style = MaterialTheme.typography.labelMedium)
        bridgeState.connectEndpoints.take(6).forEach { endpoint ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(endpoint.name, style = MaterialTheme.typography.bodyMedium)
                    Text("${endpoint.host}:${endpoint.port}", style = MaterialTheme.typography.bodySmall)
                }
                Button(enabled = !controlsBusy, onClick = {
                    busy = true
                    scope.launch {
                        val result = runSuspendCatching { bridge.connect(endpoint) }
                        if (result.isSuccess) {
                            message = result.getOrThrow()
                            runSuspendCatching { manager.refresh() }
                        } else {
                            message = "Connection failed: ${result.exceptionOrNull()?.message}"
                        }
                        busy = false
                    }
                }) { Text("Connect") }
            }
        }
    }

    bridgeState.lastError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }

    if (bridgeState.connected != null) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !controlsBusy, onClick = {
                scope.launch {
                    busy = true
                    runSuspendCatching { bridge.disconnect() }
                    manager.refresh()
                    busy = false
                }
            }) { Text("Disconnect") }
            TextButton(enabled = !controlsBusy, onClick = {
                scope.launch {
                    busy = true
                    runSuspendCatching { bridge.resetPairingIdentity() }
                        .onSuccess { message = "Pairing identity reset. Pair again when needed." }
                        .onFailure { message = "Reset failed: ${it.message}" }
                    manager.refresh()
                    busy = false
                }
            }) { Text("Reset pairing") }
        }

        HorizontalDivider()
        Text("Device Workstation toolchain", style = MaterialTheme.typography.labelMedium)
        workstationInfo?.let { info ->
            Text("${info.version} · JDK ${info.javaVersion} · SDK ${info.sdkPlatforms.sorted().joinToString(",")}", style = MaterialTheme.typography.bodySmall)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !controlsBusy, onClick = {
                scope.launch {
                    busy = true
                    runSuspendCatching { manager.workstationInfo() }
                        .onSuccess { info ->
                            workstationInfo = info
                            message = if (info != null) "Device Workstation toolchain verified." else "No healthy Device Workstation toolchain is available."
                        }
                        .onFailure { message = "Device Workstation verification failed: ${it.message}" }
                    busy = false
                }
            }) { Text("Verify") }
            if (workstationInstalledVersion != null && workstationInfo == null) {
                OutlinedButton(enabled = !controlsBusy, onClick = {
                    scope.launch {
                        runSuspendCatching { manager.repairToolchain() }
                            .onSuccess { message = it }
                            .onFailure { message = "Repair failed: ${it.message}" }
                    }
                }) { Text("Repair") }
            }
            if (workstationInstalledVersion != null) {
                TextButton(enabled = !controlsBusy, onClick = { confirmRemove = true }) { Text("Remove") }
            }
        }

        HorizontalDivider()
        Text("Device build storage", style = MaterialTheme.typography.labelMedium)
        storageUsage?.let { usage ->
            Text("Free: ${formatBytes(usage.freeBytes)}", style = MaterialTheme.typography.bodySmall)
            Text(
                "Toolchain ${formatBytes(usage.toolchainsBytes)} · Gradle cache ${formatBytes(usage.gradleCacheBytes)} · Workspace cache ${formatBytes(usage.workspacesBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } ?: Text(
            "Storage usage has not been measured yet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !controlsBusy, onClick = { refreshStorageUsage() }) { Text("Refresh storage") }
            TextButton(enabled = !controlsBusy, onClick = { confirmClearCaches = true }) { Text("Clear build caches") }
        }

        HorizontalDivider()
        Text("Developer candidate toolchain", style = MaterialTheme.typography.labelMedium)
        Text(
            "Advanced: validate a local toolchain pack before using it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !controlsBusy, onClick = {
                candidatePicker.launch(arrayOf("application/zip", "application/octet-stream"))
            }) { Text("Select candidate ZIP") }
            candidate?.let { selected ->
                TextButton(enabled = !controlsBusy, onClick = {
                    scope.launch {
                        runSuspendCatching { candidateManager.discard(selected) }
                        candidate = null
                        candidateLicense = null
                        candidateLicenseAccepted = false
                        message = "Candidate snapshot discarded."
                    }
                }) { Text("Discard") }
            }
        }
        candidate?.let { selected ->
            Text(
                "${selected.displayId} · ${selected.manifest.abi} · SDK ${selected.manifest.compileSdk} · JDK ${selected.manifest.javaVersion}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "${formatBytes(selected.sizeBytes)} · pack ${selected.packSha256.take(16)}… · source ${selected.sourceLockSha256.take(16)}…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "${selected.manifest.runtime.mode} · recipe ${selected.manifest.buildRecipeVersion ?: "unknown"} · ${selected.provenancePurpose}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !controlsBusy, onClick = {
                    scope.launch {
                        busy = true
                        val result = runSuspendCatching { licenseManager.fetchCurrent() }
                        if (result.isSuccess) {
                            val license = result.getOrThrow()
                            val expected = selected.manifest.sdkLicenseSha256
                            if (expected == null || license.sha256 != expected) {
                                candidateLicense = null
                                candidateLicenseAccepted = false
                                message = "Candidate SDK license hash does not match Google's current license. Installation is blocked."
                            } else {
                                candidateLicense = license
                                candidateLicenseAccepted = runSuspendCatching { licenseManager.isAccepted(license) }.getOrDefault(false)
                                showCandidateLicense = true
                            }
                        } else {
                            message = "Could not load Android SDK license: ${result.exceptionOrNull()?.message}"
                        }
                        busy = false
                    }
                }) { Text(if (candidateLicenseAccepted) "License accepted" else "Review SDK license") }
                Button(
                    enabled = !controlsBusy && candidateLicenseAccepted &&
                        candidateLicense?.sha256 == selected.manifest.sdkLicenseSha256,
                    onClick = { confirmCandidateInstall = true },
                ) { Text("Install candidate") }
            }
        }

        if (workstationInfo != null) {
            HorizontalDivider()
            Text("Toolchain certification", style = MaterialTheme.typography.labelMedium)
            Text(
                "Runs certification on the connected device without changing the release catalog.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (certificationState.running) {
                Text(
                    "${certificationState.currentStep} · ${certificationState.completed}/${certificationState.total}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                certificationState.progress?.let { progress ->
                    Text("${(progress * 100f).toInt()}%", style = MaterialTheme.typography.labelSmall)
                }
            } else if (certificationState.message.isNotBlank()) {
                Text(certificationState.message, style = MaterialTheme.typography.bodySmall)
            }
            certificationState.latest?.let { report ->
                Text(
                    "Latest: ${report.level} · ${if (report.passed) "PASS" else "FAIL"} · ${if (report.devicePhysical) "physical" else "emulator"} · ${report.deviceManufacturer} ${report.deviceModel} · API ${report.androidApi} · ${report.packSha256.take(12)}…",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (report.passed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !controlsBusy, onClick = {
                    certificationJob = scope.launch {
                        try {
                            val result = runSuspendCatching { certificationManager.certify(AndroidToolchainCertificationManager.Level.SMOKE) }
                            message = if (result.isSuccess) "Smoke certification passed." else "Smoke certification failed: ${result.exceptionOrNull()?.message}"
                        } finally {
                            certificationJob = null
                        }
                    }
                }) { Text("Smoke certify") }
                Button(enabled = !controlsBusy && devState.androidProject, onClick = {
                    certificationJob = scope.launch {
                        try {
                            val result = runSuspendCatching { certificationManager.certify(AndroidToolchainCertificationManager.Level.FULL) }
                            message = if (result.isSuccess) "Full certification passed." else "Full certification failed: ${result.exceptionOrNull()?.message}"
                        } finally {
                            certificationJob = null
                        }
                    }
                }) { Text("Full certify") }
            }
            if (certificationState.running) {
                TextButton(onClick = { certificationJob?.cancel() }) { Text("Cancel certification") }
            }
            if (certificationState.latest != null) {
                TextButton(enabled = !controlsBusy, onClick = {
                    certificationExporter.launch("droide-toolchain-certification-${System.currentTimeMillis()}.json")
                }) { Text("Export report") }
            }
            if (!devState.androidProject) {
                Text(
                    "Open an Android project for Full certification. Smoke works without one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (devState.readiness == AndroidDevelopmentManager.Readiness.TOOLCHAIN_REQUIRED) {
            HorizontalDivider()
            Text("Managed toolchain", style = MaterialTheme.typography.labelMedium)
            toolchainCatalogError?.let { error ->
                Text("Catalog unavailable: $error", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            val entry = catalogEntry
            if (entry == null) {
                val revision = toolchainCatalog?.revision
                Text(
                    if (revision == null) {
                        "Managed toolchain catalog unavailable."
                    } else {
                        "No compatible managed toolchain for SDK ${devState.compileSdk ?: 36}."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text("${entry.version} · ${entry.abi} · SDK ${entry.compileSdk} · JDK ${entry.javaVersion}", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "${formatBytes(entry.sizeBytes)} · SHA-256 ${entry.sha256.take(12)}… · ${entry.provenance}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Review and accept the Android SDK license before install.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = !controlsBusy, onClick = {
                        scope.launch {
                            busy = true
                            val result = runSuspendCatching { licenseManager.fetchCurrent() }
                            if (result.isSuccess) {
                                val license = result.getOrThrow()
                                if (license.sha256 != entry.sdkLicenseSha256) {
                                    sdkLicense = null
                                    sdkLicenseAccepted = false
                                    message = "SDK license hash does not match the pinned toolchain catalog. Installation is blocked."
                                } else {
                                    sdkLicense = license
                                    sdkLicenseAccepted = runSuspendCatching { licenseManager.isAccepted(license) }.getOrDefault(false)
                                    showLicense = true
                                }
                            } else {
                                message = "Could not load Android SDK license: ${result.exceptionOrNull()?.message}"
                            }
                            busy = false
                        }
                    }) { Text(if (sdkLicenseAccepted) "License accepted" else "Review SDK license") }

                    if (managedInstallState.running) {
                        TextButton(onClick = { installJob?.cancel() }) { Text("Cancel") }
                    }
                }
                if (sdkLicenseAccepted && sdkLicense?.sha256 == entry.sdkLicenseSha256) {
                    Button(enabled = !controlsBusy, onClick = {
                        val license = sdkLicense ?: return@Button
                        installJob = scope.launch {
                            try {
                                val result = runSuspendCatching { managedInstaller.install(entry, license) }
                                if (result.isSuccess) {
                                    message = result.getOrThrow()
                                    downloadCached = true
                                    runSuspendCatching { manager.refresh() }
                                    runSuspendCatching { manager.remoteStorageUsage() }.onSuccess { storageUsage = it }
                                } else {
                                    message = "Managed toolchain install failed: ${result.exceptionOrNull()?.message}"
                                }
                            } finally {
                                installJob = null
                            }
                        }
                    }) { Text(if (downloadCached) "Install verified cache" else "Download & install") }
                }
                if (downloadCached && !managedInstallState.running) {
                    TextButton(enabled = !controlsBusy, onClick = {
                        scope.launch {
                            runSuspendCatching { managedInstaller.clearCachedDownload(entry) }
                                .onSuccess { downloadCached = false; message = "Verified toolchain download cache cleared." }
                                .onFailure { message = "Cache cleanup failed: ${it.message}" }
                        }
                    }) { Text("Clear download cache") }
                }
            }
        }
    }

    if (showCandidateLicense && candidateLicense != null) {
        val license = candidateLicense!!
        AlertDialog(
            onDismissRequest = { if (!busy) showCandidateLicense = false },
            title = { Text("Candidate Android SDK License") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text(license.text, style = MaterialTheme.typography.bodySmall)
                    Text("\nSHA-256: ${license.sha256}", style = MaterialTheme.typography.labelSmall)
                    Text(
                        "A changed SDK license must be accepted again.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                Button(enabled = !busy && !candidateLicenseAccepted, onClick = {
                    scope.launch {
                        busy = true
                        runSuspendCatching { licenseManager.accept(license) }
                            .onSuccess {
                                candidateLicenseAccepted = true
                                message = "Android SDK license accepted for this exact candidate revision."
                            }
                            .onFailure { message = "License acceptance failed: ${it.message}" }
                        busy = false
                    }
                }) { Text(if (candidateLicenseAccepted) "Accepted" else "Accept") }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { showCandidateLicense = false }) { Text("Close") } },
        )
    }

    if (confirmCandidateInstall && candidate != null) {
        val selected = candidate!!
        AlertDialog(
            onDismissRequest = { if (!controlsBusy) confirmCandidateInstall = false },
            title = { Text("Install developer candidate?") },
            text = {
                Column {
                    Text("Temporarily use this toolchain for device certification.")
                    Text("\nPack SHA-256: ${selected.packSha256}", style = MaterialTheme.typography.labelSmall)
                    Text("Source lock: ${selected.sourceLockSha256}", style = MaterialTheme.typography.labelSmall)
                }
            },
            confirmButton = {
                Button(enabled = !controlsBusy, onClick = {
                    confirmCandidateInstall = false
                    candidateJob = scope.launch {
                        try {
                            val result = runSuspendCatching { candidateManager.install(selected) }
                            if (result.isSuccess) {
                                message = result.getOrThrow()
                                runSuspendCatching { manager.refresh() }
                                runSuspendCatching { manager.remoteStorageUsage() }.onSuccess { storageUsage = it }
                            } else {
                                message = "Candidate install failed: ${result.exceptionOrNull()?.message}"
                            }
                        } finally {
                            candidateJob = null
                        }
                    }
                }) { Text("Install for certification") }
            },
            dismissButton = { TextButton(enabled = !controlsBusy, onClick = { confirmCandidateInstall = false }) { Text("Cancel") } },
        )
    }

    if (showLicense && sdkLicense != null) {
        val license = sdkLicense!!
        AlertDialog(
            onDismissRequest = { if (!busy) showLicense = false },
            title = { Text("Android SDK License") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text(license.text, style = MaterialTheme.typography.bodySmall)
                    Text("\nSHA-256: ${license.sha256}", style = MaterialTheme.typography.labelSmall)
                }
            },
            confirmButton = {
                Button(enabled = !busy && !sdkLicenseAccepted, onClick = {
                    scope.launch {
                        busy = true
                        runSuspendCatching { licenseManager.accept(license) }
                            .onSuccess {
                                sdkLicenseAccepted = true
                                message = "Android SDK license accepted for this exact license revision."
                            }
                            .onFailure { message = "License acceptance failed: ${it.message}" }
                        busy = false
                    }
                }) { Text(if (sdkLicenseAccepted) "Accepted" else "Accept") }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { showLicense = false }) { Text("Close") } },
        )
    }

    if (confirmClearCaches) {
        AlertDialog(
            onDismissRequest = { if (!controlsBusy) confirmClearCaches = false },
            title = { Text("Clear device build caches?") },
            text = {
                Text("Clears remote workspace and Gradle caches. Project files and installed toolchains stay intact.")
            },
            confirmButton = {
                Button(enabled = !controlsBusy, onClick = {
                    confirmClearCaches = false
                    scope.launch {
                        busy = true
                        runSuspendCatching { manager.clearRemoteBuildCaches(includeGradleCache = true) }
                            .onSuccess {
                                message = it
                                runSuspendCatching { manager.remoteStorageUsage() }.onSuccess { usage -> storageUsage = usage }
                            }
                            .onFailure { message = "Cache cleanup failed: ${it.message}" }
                        busy = false
                    }
                }) { Text("Clear caches") }
            },
            dismissButton = { TextButton(enabled = !controlsBusy, onClick = { confirmClearCaches = false }) { Text("Cancel") } },
        )
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { if (!operation.running) confirmRemove = false },
            title = { Text("Remove Android toolchain?") },
            text = { Text("Removes the managed JDK/SDK toolchain. Project files stay intact.") },
            confirmButton = {
                Button(enabled = !operation.running, onClick = {
                    confirmRemove = false
                    scope.launch {
                        runSuspendCatching { manager.removeToolchain() }
                            .onSuccess { message = it }
                            .onFailure { message = "Remove failed: ${it.message}" }
                    }
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(enabled = !operation.running, onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }
}
