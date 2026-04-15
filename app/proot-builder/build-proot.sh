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
    "arm64-v8a")   TARGET_TRIPLE=aarch64-linux-android ;;
    "armeabi-v7a") TARGET_TRIPLE=armv7a-linux-androideabi ;;
    "x86_64")      TARGET_TRIPLE=x86_64-linux-android ;;
    "x86")         TARGET_TRIPLE=i686-linux-android ;;
    *) echo "Unknown ABI: $ABI"; exit 1 ;;
esac

export AR="$TOOLCHAIN/bin/llvm-ar"
export CC="$TOOLCHAIN/bin/${TARGET_TRIPLE}${API_LEVEL}-clang"
export STRIP="$TOOLCHAIN/bin/llvm-strip"

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
# 去掉 -Werror=implicit-function-declaration：
# proot 源码（ashmem_memfd.c 等）缺少部分 #include，NDK r27 严格模式下会报错
# ============================================================
echo "Building proot for $ABI..."
cd "$PROOT_SRC_DIR/src"

export CFLAGS="-I$STATIC_ROOT/include"
export LDFLAGS="-L$STATIC_ROOT/lib"

make distclean || true

echo "Running make..."
make V=1 -j$(nproc) proot

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
