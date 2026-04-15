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
# 3. 打补丁 —— 必须在 make distclean 之前
# ============================================================
echo "Patching proot sources..."

# 补丁 A：禁用 32 位 loader（必须在 make 读 Makefile 之前）
sed -i 's/#define HAS_LOADER_32BIT true/#define HAS_LOADER_32BIT false/' \
    "$PROOT_SRC_DIR/src/arch.h"
grep -q 'HAS_LOADER_32BIT false' "$PROOT_SRC_DIR/src/arch.h" \
    && echo "  [OK] arch.h: HAS_LOADER_32BIT -> false" \
    || { echo "  [FAIL] arch.h patch failed!"; exit 1; }

# 补丁 B：修正 loader.c 中 basename 与 NDK string.h 的冲突
# NDK r27 的 string.h 已包含公共 basename 声明，而 loader.c
# 里面局部定义了同名 static inline 函数。
# 解决：把 loader.c 里的 basename 改名为 proot_loader_basename
sed -i 's/\bbasename\b/proot_loader_basename/g' \
    "$PROOT_SRC_DIR/src/loader/loader.c"
grep -q 'proot_loader_basename' "$PROOT_SRC_DIR/src/loader/loader.c" \
    && echo "  [OK] loader.c: basename -> proot_loader_basename" \
    || { echo "  [FAIL] loader.c patch failed!"; exit 1; }

# ============================================================
# 4. 编译 PRoot
# ============================================================
echo "Building proot for $ABI..."
cd "$PROOT_SRC_DIR/src"

make distclean || true

echo '#define VERSION "v5.1.107-static"' > build.h
echo '#define HAVE_PROCESS_VM 1' >> build.h
echo '#define HAVE_SECCOMP_FILTER 1' >> build.h

echo "Running make..."
# 注意：-include replace.h 只放在 CFLAGS 里（对 .c 文件）
# .S 汇编文件不能用 -include C 头文件，否则会把头文件内容当汇编语句解析
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
