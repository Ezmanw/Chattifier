#!/usr/bin/env bash
# Rebuilds app/src/main/jniLibs/arm64-v8a from Termux's own packages. PRoot runs from
# nativeLibraryDir (the only place Android lets an app execute files), so every file must be
# named lib*.so and find its dependencies there instead of under /data/data/com.termux.
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=app/src/main/jniLibs/arm64-v8a
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
REPO=https://packages.termux.dev/apt/termux-main
declare -A SHA=(
  [pool/main/p/proot/proot_5.1.107.96_aarch64.deb]=8199dca06dccb693ec09fb1759e3e1ad08b4863f0c11c612f89c20bd9ecdc1a0
  [pool/main/libt/libtalloc/libtalloc_2.5.0_aarch64.deb]=556591f43bb773ad8777e1a29522640866a55f95dab71914418b94a8c58ad5a7
  [pool/main/liba/libandroid-shmem/libandroid-shmem_0.7_aarch64.deb]=0da3a24d558b93c92bcf8d611e0826a99ff96e396b148e6cdf33b47c47c57ff6
)
for f in "${!SHA[@]}"; do
  curl -sfL "$REPO/$f" -o "$WORK/$(basename "$f")"
  echo "${SHA[$f]}  $WORK/$(basename "$f")" | sha256sum -c --quiet
  (cd "$WORK" && mkdir -p x && cd x && ar x "../$(basename "$f")" && tar xf data.tar.xz && rm -f data.tar.xz control.tar.xz debian-binary)
done
python3 -m venv "$WORK/venv" && "$WORK/venv/bin/pip" install -q patchelf
P="$WORK/venv/bin/patchelf"
U="$WORK/x/data/data/com.termux/files/usr"
mkdir -p "$OUT"
cp "$U/bin/proot" "$OUT/libproot.so"
cp "$U/libexec/proot/loader" "$OUT/libproot_loader.so"
cp "$U/lib/libtalloc.so.2.5.0" "$OUT/libtalloc.so"
cp "$U/lib/libandroid-shmem.so" "$OUT/libandroid-shmem.so"
"$P" --replace-needed libtalloc.so.2 libtalloc.so "$OUT/libproot.so"
"$P" --set-soname libtalloc.so "$OUT/libtalloc.so"
"$P" --remove-rpath "$OUT/libproot.so"
echo "PRoot libraries written to $OUT"
