#!/usr/bin/env python3
from __future__ import annotations
import json, re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def fail(message: str) -> None:
    raise SystemExit(f"DEPENDENCY_POLICY_FAILED: {message}")

def text(path: str) -> str:
    p = ROOT / path
    if not p.is_file(): fail(f"missing {path}")
    return p.read_text(encoding="utf-8")

def direct_runtime_dependencies(app: str) -> set[str]:
    out: set[str] = set()
    for value in re.findall(r'implementation\(\s*"([^"]+)"\s*\)', app): out.add(value)
    for value in re.findall(r'implementation\(\s*platform\(\s*"([^"]+)"\s*\)\s*\)', app): out.add(value)
    for value in re.findall(r'implementation\(\s*files\(\s*"libs/([^"]+)"\s*\)\s*\)', app): out.add(f"local:{value}")
    return out

def main() -> None:
    gradle_files = list(ROOT.glob("*.gradle.kts")) + list((ROOT / "app").glob("*.gradle.kts"))
    body = "\n".join(p.read_text(encoding="utf-8") for p in gradle_files)
    for pattern, message in {
        r"(?i)jitpack": "JitPack repository must not be enabled at build time",
        r"(?i)(?:latest\.|-SNAPSHOT|:\+\b|\[[^\]]*,[^\]]*\])": "dynamic or snapshot dependency version detected",
        r"(?i)http://": "cleartext repository URL detected",
    }.items():
        if re.search(pattern, body): fail(message)
    settings = text("settings.gradle.kts")
    for token in ["RepositoriesMode.FAIL_ON_PROJECT_REPOS", 'name = "DroideLocalCompat"', 'includeGroup("com.baystudio.compat")', 'excludeGroup("com.baystudio.compat")']:
        if token not in settings: fail(f"settings.gradle.kts missing repository hardening token: {token}")
    app = text("app/build.gradle.kts")
    for token in ["dependencyLocking {", "lockAllConfigurations()", "org.eclipse.jgit:org.eclipse.jgit:6.10.1.202505221210-r", "io.github.rosemoe:editor:0.24.6", "io.github.rosemoe:language-textmate:0.24.6"]:
        if token not in app: fail(f"app/build.gradle.kts missing required pin/control: {token}")
    legal_path = ROOT / "app/src/main/assets/legal/open_source_licenses.json"
    if not legal_path.is_file(): fail("open-source license catalog missing")
    try: legal = json.loads(legal_path.read_text(encoding="utf-8"))
    except Exception as exc: fail(f"invalid open-source license catalog: {exc}")
    entries = legal.get("entries") or []
    if not entries: fail("open-source license catalog empty")
    mapped: dict[str, str] = {}
    for entry in entries:
        asset = ROOT / "app/src/main/assets" / entry.get("licenseTextAsset", "")
        if not asset.is_file() or asset.stat().st_size == 0: fail(f"missing license text for {entry.get('id')}: {entry.get('licenseTextAsset')}")
        for key in entry.get("dependencyKeys", []):
            if key in mapped: fail(f"dependency mapped twice: {key}")
            mapped[key] = entry.get("id", "")
    declared = direct_runtime_dependencies(app)
    missing = sorted(declared - set(mapped))
    stale = sorted(set(mapped) - declared)
    if missing: fail("direct runtime dependency has no license mapping: " + ", ".join(missing))
    if stale: fail("license catalog has stale direct dependency mapping: " + ", ".join(stale))
    if not (ROOT / "third_party/ARTIFACTS_SHA256.txt").is_file(): fail("third-party integrity manifest missing")
    workflows = sorted((ROOT / ".github/workflows").glob("*.yml"))
    if not workflows: fail("no GitHub Actions workflow found")
    for path in workflows:
        workflow = path.read_text(encoding="utf-8")
        for use in re.findall(r"\buses:\s*([^\s#]+)", workflow):
            if "@" not in use or not re.fullmatch(r"[0-9a-f]{40}", use.rsplit("@",1)[1]): fail(f"GitHub Action is not pinned to an immutable commit SHA in {path.name}: {use}")
    print(f"DEPENDENCY_POLICY_OK direct_runtime={len(declared)} mapped={len(mapped)}")

if __name__ == "__main__": main()
