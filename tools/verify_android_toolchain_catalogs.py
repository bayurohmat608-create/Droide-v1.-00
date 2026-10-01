#!/usr/bin/env python3
"""Verify Android toolchain catalog integrity, host admission, and local SDK provenance."""
from __future__ import annotations

from pathlib import Path
from urllib.parse import urlparse
import hashlib
import json
import re

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app/src/main/assets"
CORE = ROOT / "app/src/main/java/com/baystudio/droide/core"
HEX64 = re.compile(r"[0-9a-f]{64}")
HEX40 = re.compile(r"[0-9a-f]{40}")
VERSION = re.compile(r"[0-9][A-Za-z0-9+_.-]{0,79}")
ID = re.compile(r"[A-Za-z0-9._-]{1,100}")


def fail(message: str) -> None:
    raise SystemExit(f"ANDROID_TOOLCHAIN_CATALOGS_FAILED: {message}")


def read_json(name: str) -> tuple[Path, dict]:
    path = ASSETS / name
    if not path.is_file() or path.is_symlink():
        fail(f"missing or symlinked catalog: {name}")
    try:
        return path, json.loads(path.read_text(encoding="utf-8"))
    except Exception as exc:
        fail(f"invalid JSON in {name}: {exc}")


def pinned_sha(source_name: str) -> str:
    path = CORE / source_name
    if not path.is_file():
        fail(f"missing catalog loader: {source_name}")
    body = path.read_text(encoding="utf-8")
    match = re.search(r'\bconst val PINNED_ASSET_SHA256\s*=\s*"([0-9a-f]{64})"', body)
    if not match:
        fail(f"missing PINNED_ASSET_SHA256 in {source_name}")
    return match.group(1)


def verify_pin(asset: Path, source_name: str) -> None:
    actual = hashlib.sha256(asset.read_bytes()).hexdigest()
    expected = pinned_sha(source_name)
    if actual != expected:
        fail(f"catalog pin mismatch for {asset.name}: {actual} != {expected}")


def https(value: object, label: str, *, host: str | None = None) -> str:
    if not isinstance(value, str) or len(value) > 500:
        fail(f"invalid {label}")
    parsed = urlparse(value)
    if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password:
        fail(f"{label} must be credential-free HTTPS")
    if host is not None and parsed.hostname != host:
        fail(f"unexpected {label} host: {parsed.hostname}")
    return value


def verify_toolchain_catalog(document: dict) -> int:
    if document.get("schema") != 2:
        fail("android-toolchain-catalog.json must use schema 2")
    revision = document.get("revision")
    if not isinstance(revision, str) or not re.fullmatch(r"[A-Za-z0-9._+-]{1,100}", revision):
        fail("invalid Android toolchain catalog revision")
    entries = document.get("entries")
    if not isinstance(entries, list) or len(entries) > 128:
        fail("invalid Android toolchain entry list")

    ids: set[str] = set()
    digests: set[str] = set()
    allowed = {
        "ANDROID_NATIVE": "ANDROID_ARM64",
        "LINUX_ARM64_PROOT": "LINUX_ARM64",
        "LINUX_X86_64_PROOT_QEMU": "LINUX_X86_64",
    }
    for entry in entries:
        if not isinstance(entry, dict):
            fail("Android toolchain entry is not an object")
        ident = entry.get("id")
        digest = entry.get("sha256")
        mode = entry.get("executionMode", "ANDROID_NATIVE")
        artifact_host = entry.get("artifactHost", "ANDROID_ARM64")
        abi = entry.get("abi")
        if not isinstance(ident, str) or not ID.fullmatch(ident) or ident in ids:
            fail(f"invalid or duplicate Android toolchain id: {ident}")
        ids.add(ident)
        if not isinstance(digest, str) or not HEX64.fullmatch(digest) or digest in digests:
            fail(f"invalid or duplicate Android toolchain SHA-256: {ident}")
        digests.add(digest)
        if mode not in allowed or artifact_host != allowed[mode]:
            fail(f"executionMode/artifactHost mismatch: {ident}")
        if abi != "arm64-v8a":
            fail(f"managed Android toolchains are currently admitted only for arm64-v8a: {ident}")
        if not isinstance(entry.get("version"), str) or len(entry["version"]) > 100:
            fail(f"invalid Android toolchain version: {ident}")
        if not isinstance(entry.get("compileSdk"), int) or not 1 <= entry["compileSdk"] <= 999:
            fail(f"invalid compileSdk: {ident}")
        if not isinstance(entry.get("javaVersion"), int) or not 8 <= entry["javaVersion"] <= 99:
            fail(f"invalid Java version: {ident}")
        if not isinstance(entry.get("gradleVersion"), str) or not VERSION.fullmatch(entry["gradleVersion"]):
            fail(f"invalid Gradle version: {ident}")
        if not isinstance(entry.get("sizeBytes"), int) or entry["sizeBytes"] <= 0:
            fail(f"invalid Android toolchain size: {ident}")
        if not isinstance(entry.get("sdkLicenseSha256"), str) or not HEX64.fullmatch(entry["sdkLicenseSha256"]):
            fail(f"invalid SDK license hash: {ident}")
        https(entry.get("downloadUrl"), f"toolchain download URL for {ident}")
        https(entry.get("provenanceUrl"), f"toolchain provenance URL for {ident}")
    return len(entries)


def verify_local_catalog(document: dict) -> int:
    if document.get("schema") != 2:
        fail("android-local-component-catalog.json must use schema 2")
    revision = document.get("revision")
    if not isinstance(revision, str) or not re.fullmatch(r"[A-Za-z0-9._+-]{1,100}", revision):
        fail("invalid local Android component revision")
    entries = document.get("entries")
    if not isinstance(entries, list) or not entries or len(entries) > 128:
        fail("local Android component catalog must contain 1..128 entries")

    ids: set[str] = set()
    family_versions: set[tuple[str, str]] = set()
    digests: set[str] = set()
    by_id: dict[str, dict] = {}
    for entry in entries:
        if not isinstance(entry, dict):
            fail("local Android component entry is not an object")
        ident = entry.get("id")
        if not isinstance(ident, str) or not ID.fullmatch(ident) or ident in ids:
            fail(f"invalid or duplicate local Android component id: {ident}")
        ids.add(ident)
        by_id[ident] = entry
        family = entry.get("familyId")
        version = entry.get("version")
        kind = entry.get("kind")
        if not isinstance(family, str) or not isinstance(version, str):
            fail(f"invalid local Android component family/version: {ident}")
        family_version = (family, version)
        if family_version in family_versions:
            fail(f"duplicate local Android component family/version: {family_version}")
        family_versions.add(family_version)
        filename = entry.get("fileName")
        if not isinstance(filename, str) or not re.fullmatch(r"[A-Za-z0-9._+-]{1,180}", filename):
            fail(f"invalid local Android component filename: {ident}")
        if not isinstance(entry.get("sizeBytes"), int) or entry["sizeBytes"] <= 0:
            fail(f"invalid local Android component size: {ident}")
        digest = entry.get("sha256")
        if not isinstance(digest, str) or not HEX64.fullmatch(digest) or digest in digests:
            fail(f"invalid or duplicate local Android component SHA-256: {ident}")
        digests.add(digest)
        upstream_sha1 = entry.get("upstreamSha1")
        if not isinstance(upstream_sha1, str) or not HEX40.fullmatch(upstream_sha1):
            fail(f"invalid upstream SHA-1: {ident}")
        if not isinstance(entry.get("maxFiles"), int) or not 1 <= entry["maxFiles"] <= 8192:
            fail(f"invalid maxFiles bound: {ident}")
        if not isinstance(entry.get("maxUnpackedBytes"), int) or not 1 <= entry["maxUnpackedBytes"] <= 1_073_741_824:
            fail(f"invalid maxUnpackedBytes bound: {ident}")
        provenance = entry.get("provenance")
        if not isinstance(provenance, str) or not provenance.strip() or len(provenance) > 500:
            fail(f"invalid provenance: {ident}")
        download = https(entry.get("downloadUrl"), f"local component download URL for {ident}", host="dl.google.com")
        provenance_url = https(entry.get("provenanceUrl"), f"local component provenance URL for {ident}", host="dl.google.com")
        if urlparse(provenance_url).path != "/android/repository/repository2-3.xml":
            fail(f"unexpected Google SDK repository metadata URL: {ident}")

        if kind == "SDK_PLATFORM":
            if family != "sdk.android" or not re.fullmatch(r"[0-9]{1,3}", version):
                fail(f"invalid SDK Platform identity: {ident}")
            api = int(version)
            if entry.get("archiveRoot") != f"android-{api}" or entry.get("guestTarget") != f"platforms/android-{api}":
                fail(f"SDK Platform root/target mismatch: {ident}")
            if filename != f"platform-{api}_r02.zip":
                fail(f"unexpected Google SDK Platform archive name: {ident}")
            parsed = urlparse(download)
            if parsed.path != f"/android/repository/{filename}" or parsed.query or parsed.fragment:
                fail(f"unexpected Google SDK Platform URL: {ident}")
            if entry.get("nativeTools") not in (None, []):
                fail(f"SDK Platform must not declare native overlays: {ident}")
        elif kind == "BUILD_TOOLS_ARM64":
            if family != "build.android-tools" or not re.fullmatch(r"[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}", version):
                fail(f"invalid Build Tools identity: {ident}")
            major = version.split(".", 1)[0]
            if filename != f"build-tools_r{major}_linux.zip":
                fail(f"unexpected Google Build Tools archive name: {ident}")
            if entry.get("archiveRoot") != "android-16" or entry.get("guestTarget") != f"build-tools/{version}":
                fail(f"Build Tools root/target mismatch: {ident}")
            parsed = urlparse(download)
            if parsed.path != f"/android/repository/{filename}" or parsed.query or parsed.fragment:
                fail(f"unexpected Google Build Tools URL: {ident}")
            tools = entry.get("nativeTools")
            if not isinstance(tools, list) or len(tools) != 4:
                fail(f"Build Tools native overlay must contain exactly four files: {ident}")
            required = {"aapt2", "aidl", "zipalign", "split-select"}
            names = {tool.get("name") for tool in tools if isinstance(tool, dict)}
            if names != required:
                fail(f"Build Tools native overlay set mismatch: {ident}")
            native_digests: set[str] = set()
            for tool in tools:
                name = tool.get("name")
                if not isinstance(tool.get("sizeBytes"), int) or tool["sizeBytes"] <= 0:
                    fail(f"invalid Build Tools native tool size: {name}")
                sha = tool.get("sha256")
                if not isinstance(sha, str) or not HEX64.fullmatch(sha) or sha in native_digests:
                    fail(f"invalid/duplicate Build Tools native digest: {name}")
                native_digests.add(sha)
                url = https(tool.get("downloadUrl"), f"Build Tools native URL for {name}", host="github.com")
                parsed_tool = urlparse(url)
                expected_path = f"/Commit451/android-arm-build-tools/releases/download/platform-tools-{version}/{name}"
                if parsed_tool.path != expected_path or parsed_tool.query or parsed_tool.fragment:
                    fail(f"unexpected Build Tools native URL: {name}")
        else:
            fail(f"unsupported local Android component kind: {ident}")

    platform = by_id.get("google-sdk-platform-36-r02")
    if platform is None:
        fail("certified Platform 36 entry is missing")
    expected_platform = {
        "familyId": "sdk.android",
        "version": "36",
        "kind": "SDK_PLATFORM",
        "fileName": "platform-36_r02.zip",
        "sizeBytes": 65_878_410,
        "sha256": "37607369a28c5b640b3a7998868d45898ebcb777565a0e85f9acf36f29631d2e",
        "upstreamSha1": "2c1a80dd4d9f7d0e6dd336ec603d9b5c55a6f576",
    }
    for key, value in expected_platform.items():
        if platform.get(key) != value:
            fail(f"certified Platform 36 provenance pin changed: {key}")

    build_tools = by_id.get("android-build-tools-36.0.0-linux-arm64")
    if build_tools is None:
        fail("certified Build Tools 36.0.0 ARM64 entry is missing")
    expected_build_tools = {
        "familyId": "build.android-tools",
        "version": "36.0.0",
        "kind": "BUILD_TOOLS_ARM64",
        "fileName": "build-tools_r36_linux.zip",
        "sizeBytes": 63_737_259,
        "sha256": "5d9ac77fb6ff43d9da518a337b4fcf8f9097113df531d99ccefe80ef7ce8250b",
        "upstreamSha1": "b0b6376977657e8ad9b969bacf4093601da2c6fb",
        "archiveRoot": "android-16",
        "guestTarget": "build-tools/36.0.0",
    }
    for key, value in expected_build_tools.items():
        if build_tools.get(key) != value:
            fail(f"certified Build Tools 36.0.0 provenance pin changed: {key}")
    expected_native = {
        "aapt2": (6_661_672, "7512ff7e381bea6fd310b6f6e347422c8fda21e07c6e3f0162742e95eb9d7f98"),
        "aidl": (2_234_776, "8c97356b8bba8f7aad44cfd408e3ee24c66a1258244b5d08af1c9249f50dd659"),
        "zipalign": (199_824, "e8856fb24b10095eb6e940c577ce96d89ddbfc45aa0f7eeaef1597ef68f11a12"),
        "split-select": (1_707_816, "fb7f0c3c87dbd4243d7e1277ba64ded2399389244058b6a4c969cc615360766c"),
    }
    actual_native = {tool["name"]: (tool["sizeBytes"], tool["sha256"]) for tool in build_tools["nativeTools"]}
    if actual_native != expected_native:
        fail("certified Build Tools native overlay provenance pins changed")
    return len(entries)


def main() -> None:
    toolchain_path, toolchains = read_json("android-toolchain-catalog.json")
    local_path, local_components = read_json("android-local-component-catalog.json")
    verify_pin(toolchain_path, "AndroidToolchainCatalog.kt")
    verify_pin(local_path, "AndroidLocalComponentCatalog.kt")
    toolchain_count = verify_toolchain_catalog(toolchains)
    local_count = verify_local_catalog(local_components)
    print(f"ANDROID_TOOLCHAIN_CATALOGS_OK toolchains={toolchain_count} local_components={local_count}")


if __name__ == "__main__":
    main()
