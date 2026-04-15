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
TALLOC_DIR="$ROOT_DIR/talloc-${TALLOC_VER}"
PROOT_SRC_DIR="$ROOT_DIR/proot-src"

case $ABI in
    "arm64-v8a")
        TARGET_ARCH=aarch64-linux-android
        PROOT_ARCH="-DARCH_ARM64 -D__aarch64__"
        ;;
    "armeabi-v7a")
        TARGET_ARCH=armv7a-linux-androideabi
        PROOT_ARCH="-DARCH_ARM"
        ;;
    "x86_64")
        TARGET_ARCH=x86_64-linux-android
        PROOT_ARCH="-DARCH_X86_64"
        ;;
    "x86")
        TARGET_ARCH=i686-linux-android
        PROOT_ARCH="-DARCH_X86"
        ;;
    *)
        echo "Unknown ABI: $ABI"
        exit 1
        ;;
esac

API_LEVEL=28
TOOLCHAIN=$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64
CC=$TOOLCHAIN/bin/${TARGET_ARCH}${API_LEVEL}-clang
AR=$TOOLCHAIN/bin/llvm-ar
STRIP=$TOOLCHAIN/bin/llvm-strip
OBJCOPY=$TOOLCHAIN/bin/llvm-objcopy
OBJDUMP=$TOOLCHAIN/bin/llvm-objdump

# ============================================================
# 1. Talloc
# ============================================================
if [ ! -d "$TALLOC_DIR" ]; then
    echo "Downloading Talloc ${TALLOC_VER}..."
    cd "$ROOT_DIR"
    curl -L -o talloc.tar.gz "https://www.samba.org/ftp/talloc/talloc-${TALLOC_VER}.tar.gz"
    tar -xzf talloc.tar.gz
    rm talloc.tar.gz
fi

echo "Building libtalloc.a for $ABI..."
cd "$TALLOC_DIR"
cat > replace.h << 'REPLACE_EOF'
#pragma once
#include <stdio.h>
#include <stdint.h>
#include <stdlib.h>
#include <stdbool.h>
#include <string.h>
#include <stdarg.h>
#include <unistd.h>
#include <errno.h>
#include <sys/types.h>
#define HAVE_VA_COPY 1
#define HAVE_CONSTRUCTOR_ATTRIBUTE 1
#define HAVE_DESTRUCTOR_ATTRIBUTE 1
#define PRINTF_ATTRIBUTE(a,b) __attribute__((format(printf, a, b)))
#define _PUBLIC_
#ifndef MIN
#define MIN(a,b) ((a)<(b)?(a):(b))
#endif
#define TALLOC_BUILD_VERSION_MAJOR 2
#define TALLOC_BUILD_VERSION_MINOR 4
#define TALLOC_BUILD_VERSION_RELEASE 2
REPLACE_EOF
$CC -static -fPIE -O2 -c talloc.c -o talloc.o -I.
$AR rcs libtalloc.a talloc.o
echo "libtalloc.a built OK"

# ============================================================
# 2. Clone PRoot
# ============================================================
if [ ! -d "$PROOT_SRC_DIR" ]; then
    echo "Cloning Termux PRoot..."
    cd "$ROOT_DIR"
    git clone --depth 1 https://github.com/termux/proot.git proot-src
fi

# ============================================================
# 3. 【关键】打补丁 —— 必须在 make distclean 之前！
#
# GNUmakefile 在解析阶段会执行：
#   $(CC) -E -dM arch.h | grep HAS_LOADER_32BIT
# 来决定是否编译 assembly-m32.o。
# 因此必须在 make 读取 Makefile 之前就把 arch.h 改好。
# ============================================================
echo "Patching arch.h to disable 32-bit loader..."
sed -i 's/#define HAS_LOADER_32BIT true/#define HAS_LOADER_32BIT false/' \
    "$PROOT_SRC_DIR/src/arch.h"

# 验证补丁是否生效
if grep -q 'HAS_LOADER_32BIT false' "$PROOT_SRC_DIR/src/arch.h"; then
    echo "  [OK] arch.h patched successfully"
else
    echo "  [WARN] patch may not have applied, checking..."
    grep 'HAS_LOADER_32BIT' "$PROOT_SRC_DIR/src/arch.h" || true
fi

# ============================================================
# 4. 编译 PRoot
# ============================================================
echo "Building proot for $ABI..."
cd "$PROOT_SRC_DIR/src"

# distclean 在补丁之后，不会动 arch.h
make distclean || true

echo '#define VERSION "v5.1.107-static"' > build.h
echo '#define HAVE_PROCESS_VM 1' >> build.h
echo '#define HAVE_SECCOMP_FILTER 1' >> build.h

echo "Running make..."
make -j$(nproc) \
    CC="$CC" LD="$CC" AR="$AR" STRIP="$STRIP" OBJCOPY="$OBJCOPY" OBJDUMP="$OBJDUMP" \
    CPPFLAGS="-I$TALLOC_DIR -I. -D_FILE_OFFSET_BITS=64 -D_GNU_SOURCE -include $TALLOC_DIR/replace.h" \
    CFLAGS="-static -fPIE -O2 $PROOT_ARCH" \
    LDFLAGS="-static -L$TALLOC_DIR" \
    proot

$STRIP proot

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
