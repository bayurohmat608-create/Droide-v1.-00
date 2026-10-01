#!/usr/bin/env python3
from __future__ import annotations
import hashlib, io, json, re, tarfile
from pathlib import Path
from urllib.parse import quote

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app/src/main/assets/workstation"
THIRD = ROOT / "third_party/native-engines"
OUT = ROOT / "app/src/main/assets/legal/runtime_legal_inventory.json"


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def parse_records(raw: str) -> list[dict[str, str]]:
    records = []
    for block in re.split(r"\n\s*\n", raw.strip()):
        rec: dict[str, str] = {}
        key = None
        for line in block.splitlines():
            if line.startswith(" ") and key:
                rec[key] += "\n" + line[1:]
                continue
            if ":" in line:
                key, value = line.split(":", 1)
                rec[key] = value.strip()
        if rec:
            records.append(rec)
    return records


def tar_text(path: Path, candidates: list[str]) -> str:
    with tarfile.open(path, "r:*") as tf:
        names = set(tf.getnames())
        for name in candidates:
            if name in names:
                member = tf.extractfile(name)
                if member:
                    return member.read().decode("utf-8", "replace")
    raise RuntimeError(f"missing expected member in {path}: {candidates}")


def alpine_installed() -> list[dict]:
    path = ASSETS / "alpine-minirootfs-3.24.2-aarch64.tar.xz"
    raw = tar_text(path, ["./lib/apk/db/installed", "lib/apk/db/installed"])
    out = []
    for r in parse_records(raw):
        if "P" not in r:
            continue
        origin = r.get("o", r["P"])
        commit = r.get("c", "")
        repo = "main"
        out.append({
            "name": r["P"], "version": r.get("V", ""), "license": r.get("L", "NOASSERTION"),
            "origin": origin, "homepage": r.get("U", ""), "aportsCommit": commit,
            "sourceRecipe": f"https://gitlab.alpinelinux.org/alpine/aports/-/tree/{commit}/{repo}/{origin}" if commit else r.get("U", ""),
        })
    return sorted(out, key=lambda x: x["name"])


def apk_indexes() -> dict[tuple[str, str], dict]:
    out: dict[tuple[str, str], dict] = {}
    for repo in ("main", "community"):
        path = THIRD / "qemu" / f"alpine-v3.24-{repo}-aarch64-APKINDEX.tar.gz"
        raw = tar_text(path, ["APKINDEX"])
        for r in parse_records(raw):
            if "P" not in r or "V" not in r:
                continue
            origin = r.get("o", r["P"])
            commit = r.get("c", "")
            out[(r["P"], r["V"])] = {
                "name": r["P"], "version": r["V"], "license": r.get("L", "NOASSERTION"),
                "origin": origin, "repository": repo, "homepage": r.get("U", ""), "aportsCommit": commit,
                "sourceRecipe": f"https://gitlab.alpinelinux.org/alpine/aports/-/tree/{commit}/{repo}/{origin}" if commit else r.get("U", ""),
            }
    return out


def qemu_packages() -> tuple[dict, list[dict]]:
    pack = ASSETS / "qemu-offline-alpine-v3.24-arm64.tar"
    with tarfile.open(pack, "r:") as tf:
        manifest = json.load(tf.extractfile("MANIFEST.json"))
        names = sorted(n for n in tf.getnames() if n.endswith(".apk"))
    index = apk_indexes()
    packages = []
    for item in manifest["packages"]:
        key = (item["name"], item["version"])
        if key not in index:
            raise RuntimeError(f"QEMU package missing from pinned APKINDEX: {key}")
        meta = dict(index[key])
        meta.update({"file": item["file"], "sha256": item["sha256"], "bytes": item["bytes"], "binaryUrl": item["url"]})
        packages.append(meta)
    if names != sorted(p["file"] for p in packages):
        raise RuntimeError("QEMU package tar and manifest disagree")
    return manifest, sorted(packages, key=lambda x: x["name"])


def ubuntu_packages() -> list[dict]:
    path = ASSETS / "ubuntu-base-24.04.5-base-arm64.tar.xz"
    with tarfile.open(path, "r:*") as tf:
        names = set(tf.getnames())
        raw = None
        for candidate in ("var/lib/dpkg/status", "./var/lib/dpkg/status"):
            if candidate in names:
                raw = tf.extractfile(candidate).read().decode("utf-8", "replace")
                break
        if raw is None:
            raise RuntimeError("Ubuntu dpkg status missing")
        out = []
        for r in parse_records(raw):
            if "Package" not in r or r.get("Status") != "install ok installed":
                continue
            package = r["Package"]
            source = r.get("Source", package)
            m = re.fullmatch(r"([^\s(]+)(?:\s*\(([^)]+)\))?", source)
            source_name = m.group(1) if m else source.split()[0]
            source_version = (m.group(2) if m and m.group(2) else r.get("Version", ""))
            launchpad = f"https://launchpad.net/ubuntu/+source/{quote(source_name)}/{quote(source_version, safe=':+~.-')}"
            doc = f"usr/share/doc/{package}"
            notice = f"{doc}/copyright"
            notice_target = notice if notice in names else ""
            if not notice_target and doc in names:
                member = tf.getmember(doc)
                if member.issym() or member.islnk():
                    target = member.linkname.strip("/")
                    candidate = f"usr/share/doc/{target}/copyright"
                    if candidate in names:
                        notice_target = candidate
            if not notice_target:
                raise RuntimeError(f"Ubuntu package copyright notice missing from rootfs: {package}")
            out.append({
                "name": package, "version": r.get("Version", ""), "architecture": r.get("Architecture", ""),
                "sourcePackage": source_name, "sourceVersion": source_version, "sourcePage": launchpad,
                "license": "SEE_EMBEDDED_DEBIAN_COPYRIGHT", "copyrightNoticePath": notice_target,
            })
    return sorted(out, key=lambda x: x["name"])


def file_record(path: Path, **extra) -> dict:
    return {"path": path.relative_to(ROOT).as_posix(), "bytes": path.stat().st_size, "sha256": sha256(path), **extra}


def build() -> dict:
    qemu_manifest, qemu = qemu_packages()
    runtime_files = [
        file_record(ROOT / "app/src/main/jniLibs/arm64-v8a/libdroide_proot.so", component="PRoot/OpenMinis", license="GPL-2.0-or-later"),
        file_record(ROOT / "app/src/main/jniLibs/arm64-v8a/libproot-loader.so", component="PRoot loader", license="GPL-2.0-or-later"),
        file_record(ROOT / "app/src/main/jniLibs/arm64-v8a/libproot-loader32.so", component="PRoot loader32", license="GPL-2.0-or-later"),
        file_record(ROOT / "app/libs/xz-1.12.jar", component="XZ for Java", license="0BSD"),
        file_record(ASSETS / "alpine-minirootfs-3.24.2-aarch64.tar.xz", component="Alpine minirootfs aggregate", license="NOASSERTION"),
        file_record(ASSETS / "ubuntu-base-24.04.5-base-arm64.tar.xz", component="Ubuntu Base aggregate", license="NOASSERTION"),
        file_record(ASSETS / "qemu-offline-alpine-v3.24-arm64.tar", component="Alpine QEMU package aggregate", license="NOASSERTION"),
    ]
    return {
        "schemaVersion": 1,
        "policy": "Every bundled runtime binary and aggregate is hash-bound; copyleft source obligations are tracked by component/package source locators and the packaged source offer.",
        "runtimeFiles": runtime_files,
        "sourceArchives": [
            file_record(THIRD / "proot/proot-openminis-8cf13e99-source.tar.gz", component="PRoot/OpenMinis corresponding source", license="GPL-2.0-or-later"),
            file_record(THIRD / "proot/talloc-2.4.2-source.tar.gz", component="talloc corresponding source", license="LGPL-3.0-or-later"),
            file_record(THIRD / "qemu/qemu-v11.0.3-source.tar.xz", component="QEMU corresponding source", license="GPL-2.0-only"),
        ],
        "aggregates": {
            "alpineMinirootfs": {
                "id": "alpine-minirootfs-3.24.2-aarch64", "distribution": "Alpine Linux v3.24", "packages": alpine_installed(),
                "sourcePolicy": "Exact aports commit links are recorded per installed package; copyleft source is available under the written source offer.",
            },
            "qemuOfflinePack": {
                "id": "qemu-offline-alpine-v3.24-arm64", "distribution": qemu_manifest.get("distribution", "Alpine v3.24 aarch64"),
                "packages": qemu, "sourcePolicy": "Exact aports commit links are recorded per package; QEMU upstream source is vendored and other copyleft package source is covered by the written source offer.",
            },
            "ubuntuBase": {
                "id": "ubuntu-base-24.04.5-arm64", "distribution": "Ubuntu Base 24.04.5 arm64", "packages": ubuntu_packages(),
                "upstream": "https://cdimage.ubuntu.com/ubuntu-base/releases/noble/release/ubuntu-base-24.04.5-base-arm64.tar.gz",
                "sourcePolicy": "Exact binary/source package versions are recorded. Package license texts and corresponding source are identified by each Ubuntu source package and covered by the written source offer.",
            },
        },
    }


def main() -> None:
    payload = build()
    OUT.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"RUNTIME_LEGAL_INVENTORY_WRITTEN {OUT.relative_to(ROOT)}")

if __name__ == "__main__":
    main()
