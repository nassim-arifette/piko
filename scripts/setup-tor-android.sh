#!/usr/bin/env bash
set -euo pipefail

[[ "$(uname -s)-$(uname -m)" == Linux-x86_64 ]] || {
    echo 'This setup script supports Linux x86_64. See companion/README.md.' >&2
    exit 1
}
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/sdk}"
tools="$ANDROID_HOME/cmdline-tools/19.0"
mkdir -p "$ANDROID_HOME/cmdline-tools"
if [[ ! -x "$tools/bin/sdkmanager" ]]; then
    archive="$(mktemp)"
    staging="$(mktemp -d)"
    trap 'rm -f "$archive"; rm -rf "$staging"' EXIT
    curl --fail --location --retry 3 -o "$archive" \
        https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip
    echo "5fdcc763663eefb86a5b8879697aa6088b041e70  $archive" | sha1sum --check -
    unzip -q "$archive" -d "$staging"
    mv "$staging/cmdline-tools" "$tools"
fi

# sdkmanager presents the Android SDK licence before installation.
"$tools/bin/sdkmanager" --sdk_root="$ANDROID_HOME" \
    'platform-tools' 'platforms;android-36' 'build-tools;36.0.0' 'ndk;27.3.13750724'

rustup toolchain install 1.97.1 --profile minimal --component rustfmt
rustup target add --toolchain 1.97.1 \
    aarch64-linux-android armv7-linux-androideabi i686-linux-android x86_64-linux-android
cargo +1.97.1 install cargo-ndk --version 4.1.2 --locked
printf '\nANDROID_HOME=%s\nANDROID_NDK_HOME=%s/ndk/27.3.13750724\n' "$ANDROID_HOME" "$ANDROID_HOME"
