#!/bin/bash
set -euo pipefail

NDK_DIR=$1
ABI=$2

if [ -z "$NDK_DIR" ] || [ -z "$ABI" ]; then
    echo "Usage: $0 <NDK_DIR> <ABI>"
    exit 1
fi

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
TALLOC_VER="2.4.2"
TALLOC_SRC_DIR="$ROOT_DIR/talloc-${TALLOC_VER}"
STATIC_ROOT="$ROOT_DIR/static-${ABI}"
PROOT_SRC_DIR="$ROOT_DIR/proot-src"

API_LEVEL=21
TOOLCHAIN=$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64

case $ABI in
    "arm64-v8a")   TARGET_TRIPLE=aarch64-linux-android
                   OBJCOPY_FMT="elf64-aarch64"
                   OBJCOPY_FMT_M32="elf32-littlearm" ;;
    "armeabi-v7a") TARGET_TRIPLE=armv7a-linux-androideabi
                   OBJCOPY_FMT="elf32-littlearm"
                   OBJCOPY_FMT_M32="elf32-littlearm" ;;
    "x86_64")      TARGET_TRIPLE=x86_64-linux-android
                   OBJCOPY_FMT="elf64-x86-64"
                   OBJCOPY_FMT_M32="elf32-i386" ;;
    "x86")         TARGET_TRIPLE=i686-linux-android
                   OBJCOPY_FMT="elf32-i386"
                   OBJCOPY_FMT_M32="elf32-i386" ;;
    *) echo "Unknown ABI: $ABI"; exit 1 ;;
esac

export AR="$TOOLCHAIN/bin/llvm-ar"
export CC="$TOOLCHAIN/bin/${TARGET_TRIPLE}${API_LEVEL}-clang"
export STRIP="$TOOLCHAIN/bin/llvm-strip"
export OBJCOPY="$TOOLCHAIN/bin/llvm-objcopy"
export OBJDUMP="$TOOLCHAIN/bin/llvm-objdump"

mkdir -p "$STATIC_ROOT/lib" "$STATIC_ROOT/include"

# ============================================================
# 1. 下载 Talloc
# ============================================================
if [ ! -d "$TALLOC_SRC_DIR" ]; then
    echo "Downloading Talloc ${TALLOC_VER}..."
    cd "$ROOT_DIR"
    curl -L -o talloc.tar.gz "https://www.samba.org/ftp/talloc/talloc-${TALLOC_VER}.tar.gz"
    tar -xzf talloc.tar.gz
    rm talloc.tar.gz
fi

# ============================================================
# 2. 手动编译 talloc.c
# ============================================================
echo "Building libtalloc.a for $ABI..."
cd "$TALLOC_SRC_DIR"

cat > replace.h << 'EOF'
#pragma once
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <stdbool.h>
#include <stdarg.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <sys/types.h>
#ifndef MIN
#define MIN(a,b) ((a)<(b)?(a):(b))
#endif
#ifndef MAX
#define MAX(a,b) ((a)>(b)?(a):(b))
#endif
#define HAVE_VA_COPY 1
#define HAVE_CONSTRUCTOR_ATTRIBUTE 1
#define HAVE_DESTRUCTOR_ATTRIBUTE 1
#define PRINTF_ATTRIBUTE(a,b) __attribute__((format(printf,a,b)))
#define _PUBLIC_
EOF

"$CC" \
    -fPIE -O2 \
    -D_FILE_OFFSET_BITS=64 \
    -D_GNU_SOURCE \
    -DTALLOC_BUILD_VERSION_MAJOR=2 \
    -DTALLOC_BUILD_VERSION_MINOR=4 \
    -DTALLOC_BUILD_VERSION_RELEASE=2 \
    -I. \
    -c talloc.c -o talloc.o

"$AR" rcs "$STATIC_ROOT/lib/libtalloc.a" talloc.o
cp -f talloc.h "$STATIC_ROOT/include/"
echo "libtalloc.a built OK"

# ============================================================
# 3. 下载 PRoot
# ============================================================
if [ ! -d "$PROOT_SRC_DIR" ]; then
    echo "Cloning Termux PRoot..."
    cd "$ROOT_DIR"
    git clone --depth 1 https://github.com/termux/proot.git proot-src
fi

# ============================================================
# 4. 编译 PRoot
# ============================================================
echo "Building proot for $ABI..."
cd "$PROOT_SRC_DIR/src"

echo "--- GNUmakefile objcopy lines (before patch) ---"
grep -n 'objcopy\|OBJCOPY\|output-target\|file format' GNUmakefile || true
echo "---"

python3 - "${OBJCOPY_FMT}" "${OBJCOPY_FMT_M32}" <<'PYEOF'
import sys, re

fmt      = sys.argv[1]
fmt_m32  = sys.argv[2]

with open('GNUmakefile', 'r') as f:
    lines = f.readlines()

out = []
for line in lines:
    if '--output-target' in line:
        is_m32 = 'm32' in line.lower() or 'M32' in line
        target = fmt_m32 if is_m32 else fmt
        line = re.sub(
            r'--output-target[= ]\$\(shell[^)]*\)',
            f'--output-target={target}',
            line
        )
        if '$(shell' in line and '--output-target' in line:
            line = re.sub(
                r'--output-target[=\s]+\S+',
                f'--output-target={target}',
                line
            )
    out.append(line)

with open('GNUmakefile', 'w') as f:
    f.writelines(out)

print('GNUmakefile patched OK')
PYEOF

echo "--- GNUmakefile objcopy lines (after patch) ---"
grep -n 'objcopy\|OBJCOPY\|output-target\|file format' GNUmakefile || true
echo "---"

PROOT_CFLAGS="-D_FILE_OFFSET_BITS=64 -D_GNU_SOURCE \
    -I. -I./ \
    -I$STATIC_ROOT/include \
    -Wall -Wextra -O2 -MD \
    -Wno-error=implicit-function-declaration"

make distclean || true

echo "Running make..."
make V=1 -j$(nproc) proot \
    CFLAGS="$PROOT_CFLAGS" \
    LDFLAGS="-L$STATIC_ROOT/lib -ltalloc" \
    OBJCOPY="$OBJCOPY" \
    OBJDUMP="$OBJDUMP"

"$STRIP" proot

# ============================================================
# 5. 部署
# ROOT_DIR = app/proot-builder
# 往上一级 = app/
# 目标     = app/src/main/assets/bin/<ABI>/proot
# ============================================================
ASSETS_DIR="$ROOT_DIR/../src/main/assets/bin/$ABI"
mkdir -p "$ASSETS_DIR"
cp proot "$ASSETS_DIR/proot"

echo ""
echo "==========================================================="
echo "Success: $ASSETS_DIR/proot"
echo "==========================================================="
