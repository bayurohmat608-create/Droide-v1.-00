#!/usr/bin/env python3
"""Verify packaged Linux inputs against the manifest and runtime admission constants."""
from pathlib import Path
import hashlib
import json
import lzma
import re

ROOT = Path(__file__).resolve().parents[1]


def digest(stream):
    value = hashlib.sha256()
    size = 0
    for block in iter(lambda: stream.read(1024 * 1024), b""):
        value.update(block)
        size += len(block)
    return size, value.hexdigest()


def constant(text, name):
    match = re.search(r'\bconst val ' + re.escape(name) + r'\s*=\s*"([^"]+)"', text)
    if not match:
        raise ValueError(f"Missing runtime constant: {name}")
    return match[1]


def verify(root=ROOT):
    assets = root / "app/src/main/assets/workstation"
    core = root / "app/src/main/java/com/baystudio/droide/core"
    manifest = json.loads((assets / "rootfs-repack-manifest.json").read_text())
    extractor = (core / "BundledRootfsExtractor.kt").read_text()
    declared = re.findall(r'Image\("([^"]+)",\s*([\d_]+),\s*"([a-f0-9]{64})",\s*([\d_]+),\s*"([a-f0-9]{64})"', extractor)
    expected = {name: (int(size.replace("_", "")), sha, int(tar_size.replace("_", "")), tar_sha)
                for name, size, sha, tar_size, tar_sha in declared}
    if len(expected) != 2 or {item["file"] for item in manifest} != set(expected):
        raise ValueError("Rootfs manifest does not match runtime image declarations")
    checked = []
    for item in manifest:
        name = item["file"]
        if Path(name).name != name:
            raise ValueError("Invalid rootfs filename")
        values = (item["bytes"], item["sha256"], item["tarBytes"], item["tarSha256"])
        if values != expected[name]:
            raise ValueError(f"Rootfs runtime/manifest mismatch: {name}")
        path = assets / name
        if path.is_symlink():
            raise ValueError(f"Symlink payload rejected: {name}")
        with path.open("rb") as stream:
            if digest(stream) != values[:2]:
                raise ValueError(f"Rootfs XZ integrity failure: {name}")
        with lzma.open(path) as stream:
            if digest(stream) != values[2:]:
                raise ValueError(f"Rootfs TAR integrity failure: {name}")
        checked.append(name)
    qemu = (core / "BundledQemuGuestRuntime.kt").read_text()
    name = constant(qemu, "FILE_NAME")
    size = re.search(r'\bconst val BYTES\s*=\s*([\d_]+)L', qemu)
    if not size or Path(name).name != name:
        raise ValueError("Missing or invalid QEMU bundle declaration")
    path = assets / name
    if path.is_symlink():
        raise ValueError("Symlink QEMU payload rejected")
    with path.open("rb") as stream:
        if digest(stream) != (int(size[1].replace("_", "")), constant(qemu, "SHA256")):
            raise ValueError("QEMU package integrity failure")
    checked.append(name)
    engine = (core / "PackagedLinuxEngine.kt").read_text()
    for label in ("LIBRARY", "LOADER", "LOADER32"):
        name = constant(engine, label + "_NAME")
        path = root / "app/src/main/jniLibs/arm64-v8a" / name
        if path.is_symlink():
            raise ValueError(f"Symlink native payload rejected: {name}")
        with path.open("rb") as stream:
            if digest(stream)[1] != constant(engine, label + "_SHA256"):
                raise ValueError(f"Native engine integrity failure: {name}")
        checked.append(name)
    return checked


if __name__ == "__main__":
    print(f"BUNDLED_RUNTIME_INTEGRITY_OK files={len(verify())}")
