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
# proot GNUmakefile 用 OBJCOPY 把 loader.exe 转成 .o，
# host 的 objcopy 不认识 Android ELF 架构，必须用 NDK 的 llvm-objcopy
export OBJCOPY="$TOOLCHAIN/bin/llvm-objcopy"

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
#
# NDK 的 llvm-objdump 输出的格式字符串是 "elf64-little" 之类 LLVM 风格，
# 而 llvm-objcopy --output-target 需要 GNU 风格（如 "elf64-aarch64"）。
# 所以在 make 前直接用 sed patch GNUmakefile，
# 把动态探测的 \$(shell objdump...) 替换为硬编码的 GNU ELF 格式名。
# ============================================================
echo "Building proot for $ABI..."
cd "$PROOT_SRC_DIR/src"

# Patch GNUmakefile: 替换 objdump 格式探测为硬编码值
# 匹配两处 \$(shell ... objdump ... loader[-m32] ...)
# 并分别替换为 $OBJCOPY_FMT 和 $OBJCOPY_FMT_M32
python3 - <<PYEOF
import re, sys

with open('GNUmakefile', 'r') as f:
    content = f.read()

# 匹配类似: \$(shell LANG=C \$(OBJDUMP) -f \$(firstword \$(LOADER_OBJS)) | grep ... | awk ...)
# 注意: 不同版本可能用 LOADER_OBJS 或 LOADER_M32_OBJS
content = re.sub(
    r'\\\$\(shell[^)]*LOADER_M32_OBJS[^)]*\)',
    '${OBJCOPY_FMT_M32}',
    content
)
content = re.sub(
    r'\\\$\(shell[^)]*LOADER_OBJS[^)]*\)',
    '${OBJCOPY_FMT}',
    content
)

with open('GNUmakefile', 'w') as f:
    f.write(content)

print('GNUmakefile patched OK')
PYEOF

PROOT_CFLAGS="-D_FILE_OFFSET_BITS=64 -D_GNU_SOURCE \
    -I. -I./ \
    -I$STATIC_ROOT/include \
    -Wall -Wextra -O2 -MD \
    -Wno-error=implicit-function-declaration"

make distclean || true

echo "Running make..."
make V=1 -j$(nproc) proot \
    CFLAGS="$PROOT_CFLAGS" \
    LDFLAGS="-L$STATIC_ROOT/lib" \
    OBJCOPY="$OBJCOPY"

"$STRIP" proot

# ============================================================
# 5. 部署
# ============================================================
ASSETS_DIR="$ROOT_DIR/../../src/main/assets/bin/$ABI"
mkdir -p "$ASSETS_DIR"
cp proot "$ASSETS_DIR/proot"

echo ""
echo "==========================================================="
echo "Success: $ASSETS_DIR/proot"
echo "==========================================================="
