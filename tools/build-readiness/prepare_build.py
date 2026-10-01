#!/usr/bin/env python3
"""Fail-closed build-readiness inspection for the Droide application itself.

This contract is intentionally project-specific. It does not constrain user projects or runtime capability discovery
runtime toolchain versions. It proves that a fresh build session knows exactly which Droide build
inputs are required and which large/network inputs are expected to be provisioned on demand.
"""
from __future__ import annotations
import argparse, hashlib, json, os, re, subprocess, sys
from pathlib import Path
from urllib.parse import urlparse

ROOT = Path(__file__).resolve().parents[2]
CONTRACT_PATH = ROOT / "tools/build-readiness/droide-build-bootstrap.json"
HEX64 = re.compile(r"[0-9a-f]{64}")


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def wrapper_properties() -> dict[str, str]:
    result: dict[str, str] = {}
    for raw in (ROOT / "gradle/wrapper/gradle-wrapper.properties").read_text("utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        result[key.strip()] = value.strip().replace("\\:", ":")
    return result


def load_contract() -> dict:
    doc = json.loads(CONTRACT_PATH.read_text("utf-8"))
    if doc.get("schema") != 1 or doc.get("kind") != "droide-build-bootstrap-contract":
        raise ValueError("unsupported build bootstrap contract")
    return doc


def require_file(rel: str) -> Path:
    p = ROOT / rel
    if not p.is_file() or p.is_symlink():
        raise ValueError(f"required build file is missing or unsafe: {rel}")
    return p


def parse_int(text: str, pattern: str, label: str) -> int:
    m = re.search(pattern, text)
    if not m:
        raise ValueError(f"cannot read {label} from app/build.gradle.kts")
    return int(m.group(1))


def parse_str(text: str, pattern: str, label: str) -> str:
    m = re.search(pattern, text)
    if not m:
        raise ValueError(f"cannot read {label}")
    return m.group(1)


def source_report() -> dict:
    contract = load_contract()
    wrapper = wrapper_properties()
    expected = contract["gradleWrapper"]
    url = wrapper.get("distributionUrl", "")
    parsed = urlparse(url)
    if parsed.scheme != "https" or not parsed.netloc:
        raise ValueError("Gradle Wrapper distribution URL must be absolute HTTPS")
    if url != expected["distributionUrl"]:
        raise ValueError("Gradle Wrapper distribution URL drifted from the reviewed build contract")
    dist_sha = wrapper.get("distributionSha256Sum", "").lower()
    if not HEX64.fullmatch(dist_sha) or dist_sha != expected["distributionSha256"]:
        raise ValueError("Gradle Wrapper distribution SHA-256 is missing or drifted")
    wrapper_jar = require_file("gradle/wrapper/gradle-wrapper.jar")
    if sha256(wrapper_jar) != expected["wrapperJarSha256"]:
        raise ValueError("Gradle Wrapper JAR does not match the reviewed checksum")

    root_build = require_file("build.gradle.kts").read_text("utf-8")
    app_build = require_file("app/build.gradle.kts").read_text("utf-8")
    settings = require_file("settings.gradle.kts").read_text("utf-8")
    android = contract["android"]
    observed_android = {
        "compileSdk": parse_int(app_build, r"\bcompileSdk\s*=\s*(\d+)", "compileSdk"),
        "targetSdk": parse_int(app_build, r"\btargetSdk\s*=\s*(\d+)", "targetSdk"),
        "minSdk": parse_int(app_build, r"\bminSdk\s*=\s*(\d+)", "minSdk"),
        "buildTools": parse_str(app_build, r'\bbuildToolsVersion\s*=\s*"([^"]+)"', "buildToolsVersion"),
    }
    if observed_android != android:
        raise ValueError(f"Android build requirement drift: expected={android} observed={observed_android}")
    if "JavaVersion.VERSION_17" not in app_build or "JvmTarget.JVM_17" not in app_build:
        raise ValueError("Droide build Java/Kotlin target is no longer JDK 17")

    plugins = contract["buildPlugins"]
    if f'id("com.android.application") version "{plugins["androidGradlePlugin"]}"' not in root_build:
        raise ValueError("Android Gradle Plugin version drift")
    for plugin_id in ("org.jetbrains.kotlin.android", "org.jetbrains.kotlin.plugin.compose", "org.jetbrains.kotlin.plugin.serialization"):
        if f'id("{plugin_id}") version "{plugins["kotlin"]}"' not in root_build:
            raise ValueError(f"Kotlin plugin version drift: {plugin_id}")
    if f'classpath("com.android.tools:r8:{plugins["r8"]}")' not in settings:
        raise ValueError("R8 override version drift")

    for repository in ("google()", "mavenCentral()"):
        if repository not in settings:
            raise ValueError(f"required build repository missing: {repository}")
    if 'url = uri("https://storage.googleapis.com/r8-releases/raw")' not in settings:
        raise ValueError("scoped official R8 repository missing")
    if "RepositoriesMode.FAIL_ON_PROJECT_REPOS" not in settings:
        raise ValueError("repository ownership is not fail-closed")
    if "dependencyLocking" not in app_build or "lockAllConfigurations()" not in app_build:
        raise ValueError("dependency locking policy is missing")

    
    
    dynamic = []
    for line in app_build.splitlines() + root_build.splitlines():
        if not any(token in line for token in ("implementation(\"", "testImplementation(\"", " version \"")):
            continue
        if re.search(r'[:\"](?:latest|release|\+|\[[^\]]*|\([^\)]*)', line, re.I):
            dynamic.append(line.strip())
    if dynamic:
        raise ValueError(f"dynamic dependency/plugin selector(s) are forbidden: {dynamic[:3]}")

    for rel in contract["bootstrap"].values():
        require_file(rel)
    require_file("third_party/ARTIFACTS_SHA256.txt")
    require_file("app/libs/terminal-emulator-v0.118.0.aar")
    require_file("app/libs/terminal-view-v0.118.0.aar")
    trust = subprocess.run(
        [sys.executable, str(require_file("tools/verify_dependency_trust.py"))],
        text=True, capture_output=True, timeout=20,
    )
    if trust.returncode != 0:
        raise ValueError(trust.stderr.strip() or "Dependency trust controls are incomplete")

    return {
        "schema": 1,
        "sourceReady": True,
        "wrapper": {
            "distributionUrl": url,
            "distributionSha256": dist_sha,
            "wrapperJarSha256": sha256(wrapper_jar),
        },
        "android": observed_android,
        "javaMajor": contract["javaMajor"],
        "buildPlugins": plugins,
        "dependencyLockPolicy": True,
        "dependencyLockSnapshotPresent": (ROOT / "app/gradle.lockfile").is_file() or (ROOT / "gradle.lockfile").is_file(),
        "dependencyVerificationMetadataPresent": (ROOT / "gradle/verification-metadata.xml").is_file(),
        "dependencyTrustControlsReady": True,
        "networkNeededForUncachedDependencies": True,
        "heavyRuntimePayloadsBundled": True,
        "heavyRuntimeProvisioning": "APK-packaged PRoot, Ubuntu/Alpine rootfs and QEMU engine pack; activation extracts on demand",
        "developmentToolchainsBundled": False,
        "bundledRuntimeIntegrityGate": "tools/verify_bundled_runtime.py",
    }


def java_major() -> int | None:
    try:
        p = subprocess.run(["java", "-version"], text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=10)
    except (OSError, subprocess.TimeoutExpired):
        return None
    m = re.search(r'version\s+"(?:1\.)?(\d+)', p.stdout)
    return int(m.group(1)) if m else None


def environment_report(sdk_arg: str | None) -> dict:
    source = source_report()
    expected_java = source["javaMajor"]
    observed_java = java_major()
    sdk_raw = sdk_arg or os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    missing: list[str] = []
    if observed_java != expected_java:
        missing.append(f"JDK {expected_java} (observed {observed_java if observed_java is not None else 'none'})")
    sdk = Path(sdk_raw).expanduser().resolve() if sdk_raw else None
    if sdk is None:
        missing.append("ANDROID_SDK_ROOT")
    else:
        compile_sdk = source["android"]["compileSdk"]
        build_tools = source["android"]["buildTools"]
        if not (sdk / f"platforms/android-{compile_sdk}/android.jar").is_file():
            missing.append(f"Android platform {compile_sdk}")
        aapt2 = sdk / f"build-tools/{build_tools}/aapt2"
        if not aapt2.is_file():
            missing.append(f"Android Build Tools {build_tools}")
    return {
        "schema": 1,
        "environmentReady": not missing,
        "javaMajor": observed_java,
        "androidSdkRoot": str(sdk) if sdk else None,
        "missing": missing,
    }


def main() -> None:
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="command", required=True)
    sub.add_parser("source")
    env = sub.add_parser("environment")
    env.add_argument("--android-sdk-root")
    args = ap.parse_args()
    try:
        report = source_report() if args.command == "source" else environment_report(args.android_sdk_root)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        raise SystemExit(f"BUILD_READINESS_FAILED {exc}") from exc
    print(json.dumps(report, indent=2, sort_keys=True))
    marker = "SOURCE_BUILD_READY_OK" if args.command == "source" else (
        "BUILD_ENVIRONMENT_READY_OK" if report["environmentReady"] else "BUILD_ENVIRONMENT_BLOCKED"
    )
    print(marker)
    if args.command == "environment" and not report["environmentReady"]:
        raise SystemExit(2)


if __name__ == "__main__":
    main()
