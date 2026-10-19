#!/usr/bin/env bash
set -euo pipefail
[[ "$(uname -s)-$(uname -m)" == Linux-x86_64 ]] || { echo 'Linux x86_64 build host required.' >&2; exit 1; }

root="$(cd "$(dirname "$0")/.." && pwd)"
revision=086d05145f5aa711ac300d04edc19c26360bac29
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/sdk}"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/27.3.13750724"
export CARGO_TARGET_DIR="$root/companion/build/native-target"
source_dir="$root/companion/build/onionmasq"
output="$root/companion/build/generated/jniLibs"
metadata="$root/companion/build/cargo-metadata"
mkdir -p "$metadata"
rm -f "$metadata"/*.json
abis=("$@")
if [[ $# == 0 ]]; then abis=(arm64-v8a armeabi-v7a x86 x86_64); fi
for abi in "${abis[@]}"; do
    case "$abi" in arm64-v8a|armeabi-v7a|x86|x86_64) ;; *) echo "Unsupported ABI: $abi" >&2; exit 1 ;; esac
done
[[ -d "$ANDROID_NDK_HOME" ]] || { echo 'Run scripts/setup-tor-android.sh first.' >&2; exit 1; }
[[ "$(cargo ndk --version)" == 'cargo-ndk 4.1.2' ]] || { echo 'cargo-ndk 4.1.2 required.' >&2; exit 1; }
mkdir -p "$root/companion/build" "$output"
if [[ ! -d "$source_dir/.git" ]]; then
    git clone --no-checkout https://gitlab.torproject.org/tpo/core/onionmasq.git "$source_dir"
    git -C "$source_dir" checkout --detach "$revision"
fi
[[ "$(git -C "$source_dir" rev-parse HEAD)" == "$revision" ]] || { echo 'Unexpected Onionmasq revision.' >&2; exit 1; }
if git -C "$source_dir" diff --quiet; then
    git -C "$source_dir" apply "$root/companion/native/privacy.patch"
else
    git -C "$source_dir" diff --binary --full-index | cmp - "$root/companion/native/privacy.patch"
fi

export CARGO_PROFILE_RELEASE_LTO=true
export CARGO_PROFILE_RELEASE_DEBUG=0
export RUSTFLAGS="-C link-arg=-Wl,-z,max-page-size=16384 --remap-path-prefix=$source_dir=/onionmasq --remap-path-prefix=$HOME/.cargo=/cargo --remap-path-prefix=$HOME/.rustup=/rustup"
export SOURCE_DATE_EPOCH=0
cd "$source_dir"
for abi in "${abis[@]}"; do
    env -i HOME="$HOME" PATH="$PATH" ANDROID_NDK_HOME="$ANDROID_NDK_HOME" \
        CARGO_TARGET_DIR="$CARGO_TARGET_DIR" CARGO_PROFILE_RELEASE_LTO=true \
        CARGO_PROFILE_RELEASE_DEBUG=0 RUSTFLAGS="$RUSTFLAGS" SOURCE_DATE_EPOCH=0 \
        cargo +1.97.1 ndk -t "$abi" --platform 29 \
        build --locked --release -p onionmasq-mobile --lib
    case "$abi" in
        arm64-v8a) target=aarch64-linux-android ;;
        armeabi-v7a) target=armv7-linux-androideabi ;;
        x86) target=i686-linux-android ;;
        x86_64) target=x86_64-linux-android ;;
    esac
    mkdir -p "$output/$abi"
    cp "$CARGO_TARGET_DIR/$target/release/libonionmasq_mobile.so" "$output/$abi/"
    "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" "$output/$abi/libonionmasq_mobile.so"
    env -i HOME="$HOME" PATH="$PATH" cargo +1.97.1 metadata --locked --format-version 1 --filter-platform "$target" \
        > "$metadata/$abi.json"
done
python3 "$root/scripts/verify-tor-native.py" "$output" "$ANDROID_NDK_HOME" "${abis[@]}"
python3 "$root/scripts/tor-notices.py" "$metadata" \
    "$root" "$root/companion/build/generated/assets/licenses"
printf '%s\n' "$revision" > "$output/onionmasq-revision.txt"
sha256sum "$root/companion/native/privacy.patch" | cut -d ' ' -f 1 > "$output/privacy-patch.sha256"
