#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/app/src/main/cpp/droide_cli_client.c"
OUT="$ROOT/app/src/main/jniLibs/arm64-v8a/libdroide_cli.so"
mkdir -p "$(dirname "$OUT")"
: "${CLANG:=clang}"
"$CLANG" --target=aarch64-linux-gnu -nostdlib -static -ffreestanding -fno-builtin -fno-stack-protector -fuse-ld=lld -Os -Wall -Wextra -Werror \
  -Wl,--build-id=none -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 -Wl,-e,_start \
  -o "$OUT" "$SRC"
chmod 0644 "$OUT"
sha256sum "$OUT"
readelf -h "$OUT" | grep -E 'Class:|Data:|Type:|Machine:'
readelf -l "$OUT" | grep -E 'LOAD|0x4000'
