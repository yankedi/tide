#!/bin/bash
# build-proot.sh - NDK r27 兼容版
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
# 不依赖 waf，直接单文件编译，仿照 green-green-avk 的思路
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

# 侟造最小化的 replace.h，断开对整个 Samba 依赖树的引用
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
# 3. 打补丁：直接修改源码中的硬编码宏
#
# 问题根源： arch.h 里面无条件地写死了：
#   #define HAS_LOADER_32BIT true
# 这会覆盖命令行传入的任何宏定义。
# 解决办法：直接用 sed 将这行改成 false。
# ============================================================
echo "Patching proot sources..."
cd "$PROOT_SRC_DIR/src"

# 补丁 1：禁用 32 位 loader（核心修复）
# 将 arch.h 里 #define HAS_LOADER_32BIT true 改成 false
sed -i 's/#define HAS_LOADER_32BIT true/#define HAS_LOADER_32BIT false/' arch.h
echo "  [patch 1] HAS_LOADER_32BIT -> false in arch.h"

# 补丁 2：修正 loader.c 里的 basename 与系统头文件冲突
sed -i 's/\bbasename\b/proot_basename/g' loader/loader.c
echo "  [patch 2] basename -> proot_basename in loader.c"

# ============================================================
# 4. 编译 PRoot
# ============================================================
make clean || true

echo '#define VERSION "v5.1.107-static"' > build.h
echo '#define HAVE_PROCESS_VM 1' >> build.h
echo '#define HAVE_SECCOMP_FILTER 1' >> build.h

echo "Running make..."
make -j$(nproc) \
    CC="$CC" LD="$CC" AR="$AR" STRIP="$STRIP" OBJCOPY="$OBJCOPY" OBJDUMP="$OBJDUMP" \
    CPPFLAGS="-I$TALLOC_DIR -I. -D_FILE_OFFSET_BITS=64 -D_GNU_SOURCE" \
    CFLAGS="-static -fPIE -O2 $PROOT_ARCH -include $TALLOC_DIR/replace.h" \
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
