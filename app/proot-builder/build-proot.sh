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
#
# NDK llvm-objdump 输出 LLVM 风格的格式名（elf64-little），
# 但 llvm-objcopy --output-target 需要 GNU 风格（elf64-aarch64）。
# GNUmakefile 里用 \$(shell objdump ...) 动态探测格式，就会拿到错误的字符串。
# 修复方式：直接将 objcopy 调用中的 --output-target= 后面这段
# \$(shell LANG=C ...) 字面替换掉。
# ============================================================
echo "Building proot for $ABI..."
cd "$PROOT_SRC_DIR/src"

# 先打印原始 objcopy 相关行，帮助调试
echo "--- GNUmakefile objcopy lines (before patch) ---"
grep -n 'objcopy\|OBJCOPY\|output-target\|file format' GNUmakefile || true
echo "---"

# 用 python3 逻行替换：
# 对于包含 --output-target= 的行，将其中的 \$(shell...) 或 \$(... objdump ...) 次展开
# 替换为硬编码的 GNU ELF 格式名。
# 策略：如果行内含 m32 相关关键词，用 M32 格式；否则用主格式。
python3 - "${OBJCOPY_FMT}" "${OBJCOPY_FMT_M32}" <<'PYEOF'
import sys, re

fmt      = sys.argv[1]   # e.g. elf64-aarch64
fmt_m32  = sys.argv[2]   # e.g. elf32-littlearm

with open('GNUmakefile', 'r') as f:
    lines = f.readlines()

out = []
for line in lines:
    # 只处理包含 --output-target 的行
    if '--output-target' in line:
        # 判断是 m32 还是主 loader
        is_m32 = 'm32' in line.lower() or 'M32' in line
        target = fmt_m32 if is_m32 else fmt
        # 替换 --output-target=\$(shell ...) 或 --output-target \$(shell ...)
        # 支持带等号和不带等号两种写法
        line = re.sub(
            r'--output-target[= ]\$\(shell[^)]*\)',
            f'--output-target={target}',
            line
        )
        # 如果 shell 子命令跨行（尾部展展的 shell 表达式），
        # 用更宿的方式: 将整个 --output-target=... 参数直接替换
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
# ============================================================
ASSETS_DIR="$ROOT_DIR/../../src/main/assets/bin/$ABI"
mkdir -p "$ASSETS_DIR"
cp proot "$ASSETS_DIR/proot"

echo ""
echo "==========================================================="
echo "Success: $ASSETS_DIR/proot"
echo "==========================================================="
