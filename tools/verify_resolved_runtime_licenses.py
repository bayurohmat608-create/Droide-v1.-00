#!/usr/bin/env python3
from __future__ import annotations
import json, sys
from pathlib import Path
sys.dont_write_bytecode = True
import generate_resolved_runtime_licenses as gen

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app/src/main/assets"
INV = ASSETS / "legal/resolved_runtime_licenses.json"

def fail(message: str) -> None:
    raise SystemExit(f"RESOLVED_RUNTIME_LICENSE_FAILED: {message}")

def main() -> None:
    if not INV.is_file():
        fail("resolved runtime license inventory missing")
    expected = json.loads(INV.read_text(encoding="utf-8"))
    try:
        actual = gen.build()
    except RuntimeError as exc:
        fail(str(exc))
    if expected != actual:
        fail("resolved runtime license inventory is stale; run tools/generate_resolved_runtime_licenses.py")
    components = expected.get("components") or []
    if not components:
        fail("resolved runtime inventory empty")
    seen = set()
    for item in components:
        c = item.get("coordinate")
        if not c or c in seen:
            fail(f"duplicate or missing coordinate: {c}")
        seen.add(c)
        spdx = item.get("spdx")
        if not spdx or spdx == "NOASSERTION":
            fail(f"missing license mapping: {c}")
        asset = ASSETS / item.get("licenseTextAsset", "")
        if not asset.is_file() or asset.stat().st_size == 0:
            fail(f"missing packaged license text for {c}: {item.get('licenseTextAsset')}")
        if not item.get("source"):
            fail(f"missing source/evidence locator: {c}")
    print(f"RESOLVED_RUNTIME_LICENSE_OK components={len(components)}")

if __name__ == "__main__":
    main()
