#!/usr/bin/env python3
from __future__ import annotations
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LOCK = ROOT / "app/gradle.lockfile"
OUT = ROOT / "app/src/main/assets/legal/resolved_runtime_licenses.json"

# Ordered rules for the exact families currently present in releaseRuntimeClasspath.
# Keep sources human-reviewable and fail closed for an unknown coordinate.
RULES = [
    (lambda c: c.startswith("androidx."), "Apache-2.0", "legal/licenses/APACHE-2.0.txt", "https://developer.android.com/jetpack/androidx"),
    (lambda c: c.startswith("com.baystudio.compat:kadb-"), "Apache-2.0", "legal/licenses/APACHE-2.0.txt", "third_party/kadb/README.md"),
    (lambda c: c.startswith("com.baystudio.compat:spake2-boringssl-compat:"), "BSD-3-Clause", "legal/licenses/BSD-3-Clause.txt", "third_party/kadb/spake2-boringssl-compat/"),
    (lambda c: c.startswith("com.google.accompanist:"), "Apache-2.0", "legal/licenses/APACHE-2.0.txt", "https://github.com/google/accompanist"),
    (lambda c: c.startswith("com.google.code.gson:gson:"), "Apache-2.0", "legal/licenses/APACHE-2.0.txt", "https://github.com/google/gson"),
    (lambda c: c.startswith("com.google.errorprone:error_prone_annotations:"), "Apache-2.0", "legal/licenses/APACHE-2.0.txt", "https://github.com/google/error-prone"),
    (lambda c: c.startswith("com.google.guava:listenablefuture:"), "Apache-2.0", "legal/licenses/APACHE-2.0.txt", "https://github.com/google/guava"),
    (lambda c: c.startswith("com.googlecode.javaewah:JavaEWAH:"), "Apache-2.0", "legal/licenses/APACHE-2.0.txt", "https://github.com/lemire/javaewah"),
    (lambda c: c.startswith("com.squareup.okhttp3:"), "Apache-2.0", "legal/licenses/APACHE-2.0.txt", "https://github.com/square/okhttp"),
    (lambda c: c.startswith("com.squareup.okio:"), "Apache-2.0", "legal/licenses/APACHE-2.0.txt", "https://github.com/square/okio"),
    (lambda c: c.startswith("commons-codec:commons-codec:"), "Apache-2.0", "legal/licenses/APACHE-2.0.txt", "https://commons.apache.org/proper/commons-codec/"),
    (lambda c: c.startswith("io.coil-kt:"), "Apache-2.0", "legal/licenses/APACHE-2.0.txt", "https://github.com/coil-kt/coil"),
    (lambda c: c.startswith("io.github.rosemoe:"), "LGPL-2.1-or-later", "legal/licenses/LGPL-2.1.txt", "https://github.com/Rosemoe/sora-editor"),
    (lambda c: c.startswith("org.bouncycastle:"), "MIT", "legal/licenses/Bouncy-Castle-LICENSE.txt", "https://www.bouncycastle.org/about/license/"),
    (lambda c: c.startswith("org.eclipse.jdt:org.eclipse.jdt.annotation:"), "EPL-2.0", "legal/licenses/EPL-2.0.txt", "https://github.com/eclipse-jdt/eclipse.jdt.core"),
    (lambda c: c.startswith("org.eclipse.jgit:org.eclipse.jgit:"), "BSD-3-Clause", "legal/licenses/JGit-EDL-1.0.txt", "https://github.com/eclipse-jgit/jgit"),
    (lambda c: c.startswith("org.jetbrains.kotlin:"), "Apache-2.0", "legal/licenses/APACHE-2.0.txt", "https://github.com/JetBrains/kotlin"),
    (lambda c: c.startswith("org.jetbrains.kotlinx:"), "Apache-2.0", "legal/licenses/APACHE-2.0.txt", "https://github.com/Kotlin"),
    (lambda c: c.startswith("org.jetbrains:annotations:"), "Apache-2.0", "legal/licenses/APACHE-2.0.txt", "https://github.com/JetBrains/java-annotations"),
    (lambda c: c.startswith("org.jruby.jcodings:jcodings:"), "MIT", "legal/licenses/MIT.txt", "https://github.com/jruby/jcodings"),
    (lambda c: c.startswith("org.jruby.joni:joni:"), "MIT", "legal/licenses/MIT.txt", "https://github.com/jruby/joni"),
    (lambda c: c.startswith("org.jsoup:jsoup:"), "MIT", "legal/licenses/jsoup-MIT.txt", "https://jsoup.org/"),
    (lambda c: c.startswith("org.jspecify:jspecify:"), "Apache-2.0", "legal/licenses/APACHE-2.0.txt", "https://github.com/jspecify/jspecify"),
    (lambda c: c.startswith("org.lsposed.hiddenapibypass:hiddenapibypass:"), "Apache-2.0", "legal/licenses/APACHE-2.0.txt", "https://github.com/LSPosed/AndroidHiddenApiBypass"),
    (lambda c: c.startswith("org.slf4j:slf4j-api:"), "MIT", "legal/licenses/MIT.txt", "https://www.slf4j.org/license.html"),
    (lambda c: c.startswith("org.snakeyaml:snakeyaml-engine:"), "Apache-2.0", "legal/licenses/APACHE-2.0.txt", "https://github.com/snakeyaml/snakeyaml-engine"),
]

def resolved_release_runtime() -> list[str]:
    out = []
    for raw in LOCK.read_text(encoding="utf-8").splitlines():
        if not raw or raw.startswith("#") or "=" not in raw:
            continue
        coordinate, scopes = raw.split("=", 1)
        if "releaseRuntimeClasspath" in scopes.split(","):
            out.append(coordinate)
    return sorted(set(out))

def classify(coordinate: str) -> dict[str, str]:
    for predicate, spdx, asset, source in RULES:
        if predicate(coordinate):
            return {"coordinate": coordinate, "spdx": spdx, "licenseTextAsset": asset, "source": source}
    raise RuntimeError(f"unmapped release runtime dependency: {coordinate}")

def build() -> dict:
    components = [classify(c) for c in resolved_release_runtime()]
    return {
        "schemaVersion": 1,
        "scope": "Gradle app releaseRuntimeClasspath from app/gradle.lockfile",
        "policy": "Every resolved release runtime module must map to one packaged license family; unknown coordinates fail closed.",
        "components": components,
    }

def main() -> None:
    payload = build()
    OUT.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"RESOLVED_RUNTIME_LICENSES_WRITTEN components={len(payload['components'])}")

if __name__ == "__main__":
    main()
