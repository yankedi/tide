#!/bin/bash
# build-proot.sh - 完全照抄 green-green-avk 思路
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
# 1. 准备 Talloc 静态库
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
# 2. 准备 PRoot 源码
# ============================================================
if [ ! -d "$PROOT_SRC_DIR" ]; then
    echo "Cloning Termux PRoot..."
    cd "$ROOT_DIR"
    git clone --depth 1 https://github.com/termux/proot.git proot-src
fi

# ============================================================
# 3. 编译 PRoot
#
# 核心思路（完全照抄 green-green-avk/build-proot-android）：
# 不禁用 32 位 loader，而是用 PROOT_UNBUNDLE_LOADER 让它
# 作为独立文件输出。loader-m32 需要宿主机的 gcc -m32
# 来编译，与交叉编译完全隔离。
# ============================================================
echo "Building proot for $ABI..."
cd "$PROOT_SRC_DIR/src"

make distclean || true

echo '#define VERSION "v5.1.107-static"' > build.h
echo '#define HAVE_PROCESS_VM 1' >> build.h
echo '#define HAVE_SECCOMP_FILTER 1' >> build.h

# loader 输出目录：和 proot 同级
LOADER_DIR="$(pwd)"

export CFLAGS="-I$TALLOC_DIR -Werror=implicit-function-declaration"
export LDFLAGS="-static -L$TALLOC_DIR"
export PROOT_UNBUNDLE_LOADER="$LOADER_DIR"

echo "Running make..."
make -j$(nproc) \
    CC="$CC" LD="$CC" AR="$AR" STRIP="$STRIP" OBJCOPY="$OBJCOPY" OBJDUMP="$OBJDUMP" \
    CPPFLAGS="-I$TALLOC_DIR -I. -D_FILE_OFFSET_BITS=64 -D_GNU_SOURCE -include $TALLOC_DIR/replace.h" \
    CFLAGS="-static -fPIE -O2 $PROOT_ARCH" \
    LDFLAGS="-static -L$TALLOC_DIR" \
    PROOT_UNBUNDLE_LOADER="$LOADER_DIR" \
    proot

$STRIP proot

# ============================================================
# 4. 部署： proot 主二进制 + loader 文件
# ============================================================
ASSETS_DIR="$ROOT_DIR/../../src/main/assets/bin/$ABI"
LIBEXEC_DIR="$ASSETS_DIR/libexec/proot"
mkdir -p "$LIBEXEC_DIR"

cp proot "$ASSETS_DIR/proot"

# 拷贝 loader 文件（如果存在）
[ -f loader/loader ]    && $STRIP loader/loader    && cp loader/loader    "$LIBEXEC_DIR/loader"
[ -f loader/loader-m32 ] && $STRIP loader/loader-m32 && cp loader/loader-m32 "$LIBEXEC_DIR/loader-m32"

echo ""
echo "==========================================================="
echo "Success: $ASSETS_DIR/proot"
echo "==========================================================="
