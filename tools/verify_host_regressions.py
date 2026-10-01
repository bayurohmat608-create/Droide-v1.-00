#!/usr/bin/env python3
"""Run host regression checks without Android assembly."""
import argparse
import os
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CORE = ROOT / "app/src/main/java/com/baystudio/droide/core"
TEST = ROOT / "app/src/test/java/com/baystudio/droide/core"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--jars", action="append", required=True, help="Directory of externally provisioned JVM jars; repeatable")
    args = parser.parse_args()
    jars = sorted({p.resolve() for directory in args.jars for p in Path(directory).glob("*.jar")})
    required = ["kotlin-compiler", "kotlin-stdlib", "kotlinx-coroutines", "junit", "hamcrest", "serialization-core", "serialization-json"]
    for name in required:
        if not any(name in p.name for p in jars):
            raise SystemExit(f"Missing host dependency: {name}")
    classpath = os.pathsep.join(map(str, jars))
    production = ["PathSecurity", "LocalPackageInstallJournal", "GradleTaskPath", "AndroidBuildArtifactPolicy",
                  "LocalAndroidBuildArtifactCollector", "LocalAndroidBuildEvidence", "WorkspacePathMapper", "BoundedOutput",
                  "ContentLengthProtocol", "JsonRpcProcess", "DapProcess", "ProcessSecurityPolicy", "TerminalLaunchSpec",
                  "LocalLinuxProcessHost", "LocalAndroidBuildCommand", "AgentIdeRequest", "AgentIdeResult",
                  "AgentToolResultSemantics", "RunnerExecutionResult", "Runner", "AndroidApkInspectionPolicy",
                  "AndroidRuntimeApkSnapshot", "AndroidDebugLaunchGuard", "ProjectWorkspacePolicy", "ProjectWorkspaceMaintenance",
                  "LocalUbuntuNpmPolicy", "AgentWorkspacePathPolicy", "SensitivePathPolicy", "WorkspaceSyncManifest", "LocalAgentWorkspaceMirror",
                  "AgentWorkspaceIo", "LocalUbuntuNpmRecipeInstaller", "PackageBackendContract", "LocalLinuxPackageEnvironment",
                  "WorkspaceAgentPluginHost", "WorkspaceAgentSessionBootstrap", "WorkspaceAgentAccessPolicy", "WorkspaceAgentUsageSnapshot",
                  "AcpTerminalController", "AcpPermissionIdentity", "AgentRequestProvenance", "PermissionApprovalGate", "ApprovalPolicyGuard", "CoroutineSafety"]
    tests = ["LocalPackageInstallJournalTest", "AndroidBuildArtifactPolicyTest", "LocalAndroidBuildArtifactCollectorTest",
             "ProtocolProcessLifecycleTest", "JsonRpcBackpressureTest", "LocalAndroidBuildEvidenceTest", "LocalAndroidBuildCommandTest",
             "AgentIdeRequestTest", "AgentToolResultSemanticsTest", "RunnerExecutionResultTest",
             "AndroidApkInspectionPolicyTest", "AndroidRuntimeApkSnapshotTest", "AndroidDebugLaunchGuardTest", "ProjectWorkspaceMaintenanceTest",
             "LocalUbuntuNpmPolicyTest", "LocalAgentWorkspaceMirrorTest"]
    with tempfile.TemporaryDirectory(prefix="droide-host-regressions-") as temp:
        out = Path(temp)
        contract = (CORE / "ExecutionProcessHost.kt").read_text().split("enum class ProcessExecutionScope")[1].split("class DeviceWorkstationProcessHost")[0]
        (out / "HostContracts.kt").write_text("package com.baystudio.droide.core\nimport java.io.*\nenum class ProcessExecutionScope" + contract)
        terminal_contract = (CORE / "TerminalSession.kt").read_text().split("class TerminalSession(")[0]
        (out / "TerminalContracts.kt").write_text(terminal_contract)
        run_plan = (CORE / "LanguageRegistry.kt").read_text().split("data class RunPlan", 1)[1].split("data class DeclarativeLanguageSpec", 1)[0]
        (out / "RunPlan.kt").write_text("package com.baystudio.droide.core\ndata class RunPlan" + run_plan)
        metadata = (CORE / "LocalUbuntuReviewedRawPolicy.kt").read_text().split("internal object LocalManagedPackageMetadata")[1]
        (out / "Metadata.kt").write_text("package com.baystudio.droide.core\ninternal object LocalManagedPackageMetadata" + metadata)
        recipes = (CORE / "ReviewedWorkstationRecipeInstaller.kt").read_text().split("data class WorkstationInstallRecipe(", 1)[1].split("@Serializable", 1)[0]
        (out / "NpmRecipes.kt").write_text("package com.baystudio.droide.core\ndata class WorkstationInstallRecipe(" + recipes)
        registry = (CORE / "ManagedPackageRegistry.kt").read_text()
        record = registry.split("data class ManagedPackageRecord(", 1)[1].split("internal object ManagedPackageMutationGate", 1)[0]
        health = (CORE / "ManagedPackageCatalog.kt").read_text().split("data class ManagedPackageHealthCheck(", 1)[1].split("@Serializable", 1)[0]
        bridge = (CORE / "DeviceBridgeManager.kt").read_text().split("data class BridgeShellResult(", 1)[1].split("private fun kotlinx.coroutines", 1)[0]
        scopes = (CORE / "DevelopmentExtensions.kt").read_text().split("enum class ExecutionScope {", 1)[1].split("}", 1)[0]
        enums = (CORE / "PackageRuntimeCompatibility.kt").read_text()
        profile = enums.split("enum class DeclarativeGuestProfile {", 1)[1].split("}", 1)[0]
        libc = enums.split("enum class DeclarativeLibcCompatibility {", 1)[1].split("}", 1)[0]
        (out / "AgentDataContracts.kt").write_text("package com.baystudio.droide.core\n" +
            "data class ManagedPackageRecord(" + record + "\ndata class ManagedPackageHealthCheck(" + health +
            "\ndata class BridgeShellResult(" + bridge + "\nenum class ExecutionScope {" + scopes + "}\n" +
            "enum class DeclarativeGuestProfile {" + profile + "}\nenum class DeclarativeLibcCompatibility {" + libc + "}\n")
        discovery = (CORE / "LocalAndroidToolchainDiscovery.kt").read_text()
        snapshot = discovery[discovery.index("    enum class Source"):discovery.index("    data class Resolution")]
        (out / "Snapshot.kt").write_text("package com.baystudio.droide.core\ninternal class LocalAndroidToolchainDiscovery {\n" + snapshot + "\n}")
        sources = [CORE / (name + ".kt") for name in production] + [TEST / (name + ".kt") for name in tests]
        host_sources = sorted((ROOT / "tools/host-regressions").glob("*.kt"))
        if any("kotlin-compiler-embeddable-2.0." in p.name for p in jars):
            syntax = (ROOT / "tools/host-regressions/KotlinSyntaxCheck.kt").read_text()
            syntax = syntax.replace("@OptIn(org.jetbrains.kotlin.K1Deprecation::class, CompilerConfiguration.Internals::class)", "")
            (out / "KotlinSyntaxCheck.kt").write_text(syntax)
            host_sources = [p for p in host_sources if p.name != "KotlinSyntaxCheck.kt"]
        sources += list(out.glob("*.kt")) + host_sources
        classes = out / "classes"
        subprocess.run(["java", "-cp", classpath, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect",
                        "-Xrender-internal-diagnostic-names", "-jvm-target", "17", "-classpath", classpath, "-d", str(classes), *map(str, sources)], check=True)
        runtime = str(classes) + os.pathsep + classpath
        subprocess.run(["java", "-cp", runtime, "org.junit.runner.JUnitCore",
                        *["com.baystudio.droide.core." + name for name in tests],
                        "com.baystudio.droide.core.LocalLinuxProcessHostRoutingTest",
                        "com.baystudio.droide.core.LocalAgentInstallBoundaryTest"], check=True)
        subprocess.run(["java", "-cp", runtime, "KotlinSyntaxCheckKt", str(ROOT / "app/src")], check=True)


if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError as failure:
        raise SystemExit(f"Host verification failed (exit {failure.returncode}); see compiler/test diagnostics above") from None
