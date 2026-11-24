#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
LIBASS_DIR="$REPO_ROOT/libass"
BUILD_ROOT="${BUILD_ROOT:-"$REPO_ROOT/build/libass-android"}"
DIST_DIR="${DIST_DIR:-"$REPO_ROOT/build/libass-dist"}"
ANDROID_API="${ANDROID_API:-21}"
ANDROID_ABIS="${ANDROID_ABIS:-arm64-v8a armeabi-v7a x86 x86_64}"
ROOT="$BUILD_ROOT/src"
TOOLCHAIN=""
HOST_TAG="linux-x86_64"
[ "$(uname -s)" = "Darwin" ] && HOST_TAG="darwin-x86_64"

die() {
    echo "error: $*" >&2
    exit 1
}

[ -f "$LIBASS_DIR/RELEASEVERSION" ] || die "libass submodule is missing; run: git submodule update --init"
VERSION="$(tr -d '[:space:]' < "$LIBASS_DIR/RELEASEVERSION")-laskco-$(git -C "$LIBASS_DIR" rev-parse --short=7 HEAD)"
PACKAGE_ROOT="$BUILD_ROOT/package/libass-android-$VERSION"

quote_array() {
    local out=""
    local item

    for item in "$@"; do
        if [ -n "$out" ]; then
            out+=", "
        fi
        out+="'$item'"
    done

    printf '[%s]' "$out"
}

find_android_ndk() {
    local candidate

    for candidate in \
        "${ANDROID_NDK_HOME:-}" \
        "${ANDROID_NDK_ROOT:-}" \
        "${ANDROID_NDK_LATEST_HOME:-}"
    do
        if [ -n "$candidate" ] && [ -x "$candidate/toolchains/llvm/prebuilt/$HOST_TAG/bin/llvm-ar" ]; then
            printf '%s\n' "$candidate"
            return
        fi
    done

    if [ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME/ndk" ]; then
        candidate="$(find "$ANDROID_HOME/ndk" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1)"
        if [ -n "$candidate" ] && [ -x "$candidate/toolchains/llvm/prebuilt/$HOST_TAG/bin/llvm-ar" ]; then
            printf '%s\n' "$candidate"
            return
        fi
    fi

    die "Android NDK not found. Set ANDROID_NDK_HOME or install an NDK with sdkmanager."
}

prepare_source() {
    rm -rf "$ROOT"
    mkdir -p "$ROOT"
    git -C "$LIBASS_DIR" archive --format=tar HEAD | tar -x -C "$ROOT"
    mkdir -p "$ROOT/subprojects"
    cp "$SCRIPT_DIR"/wraps/*.wrap "$ROOT/subprojects/"
    (cd "$ROOT" && meson subprojects download freetype2 fribidi harfbuzz libunibreak fontconfig expat)
}

android_abi_settings() {
    ABI_C_ARGS=("-O3" "-fPIC" "-DASS_EVENT_THREADS" "-DASS_FONTCONFIG_ATOMIC_REFS=1")
    ABI_CPP_ARGS=("-O3" "-fPIC")

    case "$1" in
        arm64-v8a)
            CLANG_TRIPLE="aarch64-linux-android"
            CPU_FAMILY="aarch64"
            CPU="armv8-a"
            ;;
        armeabi-v7a)
            CLANG_TRIPLE="armv7a-linux-androideabi"
            CPU_FAMILY="arm"
            CPU="armv7"
            ABI_C_ARGS+=("-mfpu=neon")
            ABI_CPP_ARGS+=("-mfpu=neon")
            ;;
        x86)
            CLANG_TRIPLE="i686-linux-android"
            CPU_FAMILY="x86"
            CPU="i686"
            ;;
        x86_64)
            CLANG_TRIPLE="x86_64-linux-android"
            CPU_FAMILY="x86_64"
            CPU="x86_64"
            ;;
        *)
            die "unknown Android ABI: $1"
            ;;
    esac
}

write_cross_file() {
    local abi="$1"
    local cross_file="$2"

    android_abi_settings "$abi"

    cat > "$cross_file" <<EOF
[binaries]
c = '$TOOLCHAIN/bin/${CLANG_TRIPLE}${ANDROID_API}-clang'
cpp = '$TOOLCHAIN/bin/${CLANG_TRIPLE}${ANDROID_API}-clang++'
ar = '$TOOLCHAIN/bin/llvm-ar'
ranlib = '$TOOLCHAIN/bin/llvm-ranlib'
strip = '$TOOLCHAIN/bin/llvm-strip'
nasm = 'nasm'
pkgconfig = 'false'

[host_machine]
system = 'android'
cpu_family = '$CPU_FAMILY'
cpu = '$CPU'
endian = 'little'

[properties]
needs_exe_wrapper = true
pkg_config_libdir = []

[built-in options]
c_args = $(quote_array "${ABI_C_ARGS[@]}")
c_link_args = []
cpp_args = $(quote_array "${ABI_CPP_ARGS[@]}")
cpp_link_args = []
EOF
}

common_meson_options() {
    printf '%s\n' \
        --buildtype=release \
        --wrap-mode=default \
        -Ddefault_library=static \
        -Db_staticpic=true \
        -Dtest=disabled \
        -Dcompare=disabled \
        -Dprofile=disabled \
        -Dfuzz=disabled \
        -Dcheckasm=disabled \
        -Dfontconfig=enabled \
        -Ddirectwrite=disabled \
        -Dcoretext=disabled \
        -Dlibunibreak=enabled \
        -Drequire-system-font-provider=false \
        -Dfontconfig:doc=disabled \
        -Dfontconfig:nls=disabled \
        -Dfontconfig:tests=disabled \
        -Dfontconfig:tools=disabled \
        -Dfontconfig:cache-build=disabled \
        -Dfontconfig:iconv=disabled \
        -Dfontconfig:xml-backend=expat \
        -Dharfbuzz:chafa=disabled \
        -Dharfbuzz:docs=disabled \
        -Dharfbuzz:icu=disabled \
        -Dharfbuzz:raster=disabled \
        -Dharfbuzz:subset=disabled \
        -Dharfbuzz:utilities=disabled \
        -Dharfbuzz:vector=disabled \
        -Dharfbuzz:with_libstdcxx=false \
        -Dfreetype2:zlib=disabled \
        -Dfreetype2:bzip2=disabled \
        -Dfreetype2:png=disabled \
        -Dfreetype2:brotli=disabled \
        -Dfreetype2:harfbuzz=disabled \
        -Dfreetype2:tests=disabled \
        -Dfribidi:docs=false \
        -Dfribidi:tests=false \
        -Dlibunibreak:tests=disabled
}

combine_archives() {
    local out="$1"
    shift

    rm -f "$out"
    {
        printf 'CREATE %s\n' "$out"
        local archive
        for archive in "$@"; do
            printf 'ADDLIB %s\n' "$archive"
        done
        printf 'SAVE\nEND\n'
    } | "$TOOLCHAIN/bin/llvm-ar" -M
    "$TOOLCHAIN/bin/llvm-ranlib" "$out"
}

collect_archives() {
    local build_dir="$1"

    find "$build_dir" -type f -name '*.a' \
        ! -path '*/meson-private/*' \
        ! -path '*/meson-uninstalled/*' \
        | sort
}

write_pkg_config() {
    local abi="$1"
    local pc_file="$PACKAGE_ROOT/lib/$abi/pkgconfig/libass.pc"

    mkdir -p "$(dirname "$pc_file")"
    cat > "$pc_file" <<EOF
prefix=\${pcfiledir}/../..
includedir=\${prefix}/include
libdir=\${prefix}/lib/$abi

Name: libass
Description: libass is an SSA/ASS subtitles rendering library
Version: $VERSION
Libs: -L\${libdir} -lass -lm
Cflags: -I\${includedir}
EOF
}

copy_licenses() {
    local dest="$1"
    local subdir
    local name

    mkdir -p "$dest/libass"
    cp "$ROOT/COPYING" "$dest/libass/"

    for name in freetype fribidi harfbuzz libunibreak fontconfig expat; do
        subdir="$(find "$ROOT/subprojects" -mindepth 1 -maxdepth 1 -type d -name "$name*" | sort | head -n 1)"
        [ -n "$subdir" ] || continue
        mkdir -p "$dest/$name"
        find "$subdir" -maxdepth 3 -type f \( \
            -iname 'COPYING*' -o \
            -iname 'LICENSE*' -o \
            -iname 'AUTHORS*' -o \
            -iname 'FTL.TXT' -o \
            -iname 'GPLv2.TXT' \
        \) -exec cp {} "$dest/$name/" \;
    done
}

write_readme() {
    cat > "$PACKAGE_ROOT/README.md" <<EOF
# libass Android $VERSION

Static libass builds for Android API $ANDROID_API from
[Laskco/mpvNova-libass](https://github.com/Laskco/mpvNova-libass) at
\`$(git -C "$LIBASS_DIR" rev-parse HEAD)\`, built with \`ASS_EVENT_THREADS\`, for:

$(
    for abi in $ANDROID_ABIS; do
        printf -- '- `%s`\n' "$abi"
    done
)

Each \`libass.a\` is a combined static archive built from libass plus the
WrapDB FreeType, FriBidi, HarfBuzz, libunibreak, Fontconfig, and Expat
dependencies pinned in nuvio-libass. Public libass headers are in
\`include/ass\`, and per-ABI pkg-config files are in \`lib/<abi>/pkgconfig\`.
EOF
}

build_abi() {
    local abi="$1"
    local build_dir="$BUILD_ROOT/build-$abi"
    local install_dest="$BUILD_ROOT/install-$abi"
    local prefix="/libass-android-$abi"
    local cross_file="$BUILD_ROOT/cross-$abi.ini"
    local archive_list="$BUILD_ROOT/archives-$abi.txt"
    local out_dir="$PACKAGE_ROOT/lib/$abi"

    echo "Building Android $abi"
    mkdir -p "$BUILD_ROOT" "$out_dir"
    write_cross_file "$abi" "$cross_file"

    rm -rf "$build_dir" "$install_dest"
    meson setup "$build_dir" "$ROOT" \
        --cross-file "$cross_file" \
        --prefix "$prefix" \
        $(common_meson_options)
    meson compile -C "$build_dir"
    DESTDIR="$install_dest" meson install -C "$build_dir"

    collect_archives "$build_dir" > "$archive_list"
    if [ ! -s "$archive_list" ]; then
        die "no static archives were produced for $abi"
    fi

    archives=()
    while IFS= read -r archive; do
        archives+=("$archive")
    done < "$archive_list"
    combine_archives "$out_dir/libass.a" "${archives[@]}"
    "$TOOLCHAIN/bin/llvm-strip" -S "$out_dir/libass.a"

    if [ ! -d "$PACKAGE_ROOT/include" ]; then
        mkdir -p "$PACKAGE_ROOT"
        cp -R "$install_dest$prefix/include" "$PACKAGE_ROOT/"
    fi

    write_pkg_config "$abi"
}

main() {
    TOOLCHAIN="$(find_android_ndk)/toolchains/llvm/prebuilt/$HOST_TAG"
    [ -x "$TOOLCHAIN/bin/llvm-ar" ] || die "invalid Android LLVM toolchain: $TOOLCHAIN"

    rm -rf "$PACKAGE_ROOT"
    mkdir -p "$PACKAGE_ROOT"

    prepare_source

    local abi
    for abi in $ANDROID_ABIS; do
        build_abi "$abi"
    done

    copy_licenses "$PACKAGE_ROOT/licenses"
    write_readme

    mkdir -p "$DIST_DIR"
    rm -f "$DIST_DIR/libass-android-$VERSION.zip"
    (
        cd "$BUILD_ROOT/package"
        zip -qry "$DIST_DIR/libass-android-$VERSION.zip" "libass-android-$VERSION"
    )
    echo "version=$VERSION"
    echo "zip=$DIST_DIR/libass-android-$VERSION.zip"
}

main "$@"
