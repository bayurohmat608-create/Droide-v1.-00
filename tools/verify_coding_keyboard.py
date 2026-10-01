#!/usr/bin/env python3
"""Run the production keyboard preference regression with JDK 17, without Android builds."""
from pathlib import Path
import shutil
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parents[1]


def main():
    java = shutil.which("java")
    if not java:
        raise SystemExit("CODING_KEYBOARD_POLICY_FAILED: JDK 17 java is required")
    with tempfile.TemporaryDirectory(prefix="droide-keyboard-policy-") as directory:
        subprocess.run([
            java, "--module", "jdk.compiler/com.sun.tools.javac.Main", "--release", "17",
            "-d", directory,
            str(ROOT / "app/src/main/java/com/baystudio/droide/core/CodingKeyboardMode.java"),
            str(ROOT / "tools/tests/CodingKeyboardModeRegression.java"),
        ], check=True)
        subprocess.run([java, "-cp", directory, "CodingKeyboardModeRegression"], check=True)


if __name__ == "__main__":
    main()
