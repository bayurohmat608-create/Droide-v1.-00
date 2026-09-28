#!/usr/bin/env python3
from __future__ import annotations
import hashlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
THIRD = ROOT / "third_party"
MANIFEST = THIRD / "ARTIFACTS_SHA256.txt"


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def tracked_files() -> list[Path]:
    result: list[Path] = []
    for p in THIRD.rglob("*"):
        if not p.is_file() or p == MANIFEST:
            continue
        if "__pycache__" in p.parts or p.suffix == ".pyc":
            continue
        result.append(p)
    return sorted(result, key=lambda p: p.relative_to(ROOT).as_posix())


def parse_manifest() -> dict[str, str]:
    if not MANIFEST.is_file():
        raise SystemExit("THIRD_PARTY_INTEGRITY_FAILED: missing third_party/ARTIFACTS_SHA256.txt")
    out: dict[str, str] = {}
    for n, line in enumerate(MANIFEST.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        try:
            digest, rel = line.split(None, 1)
        except ValueError as exc:
            raise SystemExit(f"THIRD_PARTY_INTEGRITY_FAILED: malformed manifest line {n}") from exc
        rel = rel.strip()
        if len(digest) != 64 or any(c not in "0123456789abcdef" for c in digest):
            raise SystemExit(f"THIRD_PARTY_INTEGRITY_FAILED: invalid SHA-256 on line {n}")
        path = Path(rel)
        if path.is_absolute() or ".." in path.parts or not rel.startswith("third_party/"):
            raise SystemExit(f"THIRD_PARTY_INTEGRITY_FAILED: unsafe path on line {n}: {rel}")
        if rel in out:
            raise SystemExit(f"THIRD_PARTY_INTEGRITY_FAILED: duplicate manifest path: {rel}")
        out[rel] = digest
    return out


def main() -> None:
    expected = parse_manifest()
    actual_paths = {p.relative_to(ROOT).as_posix(): p for p in tracked_files()}
    missing_manifest = sorted(set(actual_paths) - set(expected))
    missing_files = sorted(set(expected) - set(actual_paths))
    if missing_manifest:
        raise SystemExit("THIRD_PARTY_INTEGRITY_FAILED: untracked third-party files: " + ", ".join(missing_manifest[:10]))
    if missing_files:
        raise SystemExit("THIRD_PARTY_INTEGRITY_FAILED: manifest references missing files: " + ", ".join(missing_files[:10]))
    for rel, p in actual_paths.items():
        got = sha256(p)
        if got != expected[rel]:
            raise SystemExit(f"THIRD_PARTY_INTEGRITY_FAILED: hash mismatch: {rel}")
    print(f"THIRD_PARTY_INTEGRITY_OK files={len(actual_paths)}")


if __name__ == "__main__":
    main()
