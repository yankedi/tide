#!/bin/bash
# build-proot.sh - NDK 28+ 兼容版
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

# 架构映射
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
# NDK_DIR is passed as $1
TOOLCHAIN=$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64
CC=$TOOLCHAIN/bin/${TARGET_ARCH}${API_LEVEL}-clang
AR=$TOOLCHAIN/bin/llvm-ar
STRIP=$TOOLCHAIN/bin/llvm-strip
OBJCOPY=$TOOLCHAIN/bin/llvm-objcopy
OBJDUMP=$TOOLCHAIN/bin/llvm-objdump

# 1. 准备 Talloc (静态库)
if [ ! -d "$TALLOC_DIR" ]; then
    echo "Downloading Talloc ${TALLOC_VER}..."
    cd "$ROOT_DIR"
    curl -L -o talloc.tar.gz "https://www.samba.org/ftp/talloc/talloc-${TALLOC_VER}.tar.gz"
    tar -xzf talloc.tar.gz
    rm talloc.tar.gz
fi

echo "Building libtalloc.a for $ABI..."
cd "$TALLOC_DIR"
# 构造 assembly-safe 和 loader-safe 的 replace.h
cat <<EOF > replace.h
#pragma once

#if !defined(__ASSEMBLER__) && !defined(NO_LIBC_HEADER)
#include <stdio.h>
#include <stdint.h>
#include <stdlib.h>
#include <stdbool.h>
#include <string.h>
#include <stdarg.h>
#include <unistd.h>
#include <errno.h>
#include <sys/types.h>
#endif

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
EOF
$CC -static -fPIE -O2 -c talloc.c -o talloc.o -I.
$AR rcs libtalloc.a talloc.o

# 2. 准备 PRoot 源码
if [ ! -d "$PROOT_SRC_DIR" ]; then
    echo "Cloning Termux PRoot..."
    cd "$ROOT_DIR"
    git clone --depth 1 https://github.com/termux/proot.git proot-src
fi

# 3. 编译 PRoot
echo "Building proot for $ABI..."
cd "$PROOT_SRC_DIR/src"
make clean || true

# 预生成 build.h
echo '#define VERSION "v5.1.107-static"' > build.h
echo '#define HAVE_PROCESS_VM 1' >> build.h
echo '#define HAVE_SECCOMP_FILTER 1' >> build.h

# --- 关键：分两步走，避免宏污染系统头文件 ---

# 第一步：只为 loader.c 修改源码（避开系统冲突，这是最稳妥的办法）
# 避免使用 -Dbasename，因为它会污染所有包含 string.h 的文件
sed -i 's/\bbasename\b/proot_basename/g' loader/loader.c

# 第二步：正常编译所有组件
# 恢复使用 -include，通过在 replace.h 中添加卫语句来避免汇编和 loader 的冲突
echo "Running make..."
# 注意：loader 的编译规则在 GNUmakefile 中定义，它会使用 CFLAGS。
# 我们通过 -DNO_LIBC_HEADER 配合 replace.h 中的 #ifndef 来保护 loader 的 freestanding 编译。
# 禁用 HAS_LOADER_32BIT，因为 32 位编译在 NDK 28+ 中更复杂且在 arm64 上通常不是必需的
make -j$(nproc) \
    CC="$CC" LD="$CC" AR="$AR" STRIP="$STRIP" OBJCOPY="$OBJCOPY" OBJDUMP="$OBJDUMP" \
    CPPFLAGS="-I$TALLOC_DIR -I. -D_FILE_OFFSET_BITS=64 -D_GNU_SOURCE -DHAS_LOADER_32BIT=0" \
    CFLAGS="-static -fPIE -O2 $PROOT_ARCH -include $TALLOC_DIR/replace.h" \
    LDFLAGS="-static -L$TALLOC_DIR" \
    proot

$STRIP proot

# 4. 部署
ASSETS_DIR="$ROOT_DIR/../../src/main/assets/bin/$ABI"
mkdir -p "$ASSETS_DIR"
cp proot "$ASSETS_DIR/proot"

echo "Success: $ASSETS_DIR/proot"
