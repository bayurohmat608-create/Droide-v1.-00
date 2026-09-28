#!/usr/bin/env python3
from __future__ import annotations
import json, sys
from pathlib import Path
sys.dont_write_bytecode = True
import generate_runtime_legal_inventory as gen

ROOT = Path(__file__).resolve().parents[1]
LEGAL = ROOT / "app/src/main/assets/legal"

def fail(msg: str) -> None:
    raise SystemExit(f"RUNTIME_LICENSE_FAILED: {msg}")

def main() -> None:
    inventory = LEGAL / "runtime_legal_inventory.json"
    if not inventory.is_file(): fail("runtime legal inventory missing")
    expected = json.loads(inventory.read_text(encoding="utf-8"))
    actual = gen.build()
    if expected != actual: fail("runtime legal inventory is stale; run tools/generate_runtime_legal_inventory.py")
    for rel in [
        "licenses/GPL-2.0.txt", "licenses/GPL-3.0.txt", "licenses/LGPL-2.1.txt", "licenses/LGPL-3.0.txt",
        "licenses/0BSD.txt", "COPYLEFT_SOURCE_OFFER.md", "THIRD_PARTY_NOTICES.md",
    ]:
        p = LEGAL / rel
        if not p.is_file() or p.stat().st_size == 0: fail(f"missing legal asset: legal/{rel}")
    srcs = {x["component"]: ROOT / x["path"] for x in expected["sourceArchives"]}
    for component, path in srcs.items():
        if not path.is_file(): fail(f"missing corresponding source archive for {component}")
    alpine = expected["aggregates"]["alpineMinirootfs"]["packages"]
    qemu = expected["aggregates"]["qemuOfflinePack"]["packages"]
    ubuntu = expected["aggregates"]["ubuntuBase"]["packages"]
    if len(alpine) < 10: fail("Alpine inventory unexpectedly small")
    if len(qemu) < 40: fail("QEMU package inventory unexpectedly small")
    if len(ubuntu) < 50: fail("Ubuntu package inventory unexpectedly small")
    for pkg in alpine + qemu:
        if not pkg.get("license") or pkg.get("license") == "NOASSERTION": fail(f"missing Alpine package license: {pkg.get('name')}")
        if not str(pkg.get("sourceRecipe", "")).startswith("https://"): fail(f"missing Alpine source locator: {pkg.get('name')}")
    for pkg in ubuntu:
        if not pkg.get("sourcePackage") or not pkg.get("sourceVersion"): fail(f"missing Ubuntu source identity: {pkg.get('name')}")
        if not str(pkg.get("sourcePage", "")).startswith("https://launchpad.net/ubuntu/+source/"): fail(f"missing Ubuntu source locator: {pkg.get('name')}")
    print(
        "RUNTIME_LICENSE_OK "
        f"alpine={len(expected['aggregates']['alpineMinirootfs']['packages'])} "
        f"qemu={len(expected['aggregates']['qemuOfflinePack']['packages'])} "
        f"ubuntu={len(expected['aggregates']['ubuntuBase']['packages'])}"
    )

if __name__ == "__main__": main()
