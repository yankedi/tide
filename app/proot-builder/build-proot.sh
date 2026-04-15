#!/bin/bash
# 照抄 green-green-avk/build-proot-android
set -euo pipefail

NDK_DIR=$1
ABI=$2

if [ -z "$NDK_DIR" ] || [ -z "$ABI" ]; then
    echo "Usage: $0 <NDK_DIR> <ABI>"
    exit 1
fi

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
TALLOC_VER="2.1.14"
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
export CXX="$TOOLCHAIN/bin/${TARGET_TRIPLE}${API_LEVEL}-clang++"
export RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
export STRIP="$TOOLCHAIN/bin/llvm-strip"
export OBJCOPY="$TOOLCHAIN/bin/llvm-objcopy"
export OBJDUMP="$TOOLCHAIN/bin/llvm-objdump"

mkdir -p "$STATIC_ROOT/lib" "$STATIC_ROOT/include"

# ============================================================
# 1. 下载 Talloc 2.1.14（和 green-green-avk 一致）
# ============================================================
if [ ! -d "$TALLOC_SRC_DIR" ]; then
    echo "Downloading Talloc ${TALLOC_VER}..."
    cd "$ROOT_DIR"
    curl -L -o talloc.tar.gz "https://www.samba.org/ftp/talloc/talloc-${TALLOC_VER}.tar.gz"
    tar -xzf talloc.tar.gz
    rm talloc.tar.gz
fi

# ============================================================
# 2. 编译 libtalloc.a（直接照抄 green-green-avk/make-talloc-static.sh）
# ============================================================
echo "Building libtalloc.a for $ABI..."
cd "$TALLOC_SRC_DIR"

./configure distclean || true

cat > cross-answers.txt << 'EOF'
Checking uname sysname type: "Linux"
Checking uname machine type: "dontcare"
Checking uname release type: "dontcare"
Checking uname version type: "dontcare"
Checking simple C program: OK
rpath library support: OK
-Wl,--version-script support: FAIL
Checking getconf LFS_CFLAGS: OK
Checking for large file support without additional flags: OK
Checking for -D_FILE_OFFSET_BITS=64: OK
Checking for -D_LARGE_FILES: OK
Checking correct behavior of strtoll: OK
Checking for working strptime: OK
Checking for C99 vsnprintf: OK
Checking for HAVE_SHARED_MMAP: OK
Checking for HAVE_MREMAP: OK
Checking for HAVE_INCOHERENT_MMAP: OK
Checking for HAVE_SECURE_MKSTEMP: OK
Checking getconf large file support flags work: OK
Checking for HAVE_IFACE_IFCONF: FAIL
EOF

export CFLAGS="-fPIE -O2"

./configure build \
    "--prefix=$STATIC_ROOT" \
    --disable-rpath \
    --disable-python \
    --cross-compile \
    "--cross-answers=$TALLOC_SRC_DIR/cross-answers.txt"

"$AR" rcs "$STATIC_ROOT/lib/libtalloc.a" bin/default/talloc*.o
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
# 4. 编译 PRoot（照抄 make-proot.sh）
# ============================================================
echo "Building proot for $ABI..."
cd "$PROOT_SRC_DIR/src"

export CFLAGS="-I$STATIC_ROOT/include -Werror=implicit-function-declaration"
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
