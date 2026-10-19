# Piko Tor — experimental milestone

**Not validated for sensitive use. There is no “protected” indicator.** Building an APK,
an open TUN, or Arti's bootstrap-ready event does not prove that X cannot leak traffic.

## Architecture and boundaries

- X + the optional **Tor network companion** Morphe patch: a settings section and an
  explicit, visible handoff to `app.piko.tor.MainActivity`. Disabled by default. No
  VPN service, Tor native library, control broadcast, or trusted remote status in X.
- Piko Tor (`app.piko.tor`, Android 10/API 29+): its own APK/UID, `VpnService`, and
  Onionmasq/Arti JNI runtime. No shared signing certificate is required.
- The user confirms X's actual installed package, signing-certificate SHA-256, and
  distinct UID in the companion. Re-signing/reinstalling with a new UID invalidates
  that association. Shared-UID targets are rejected. The picker supports renamed
  packages; it cannot certify that the selected app really is X.
- `startActivityForResult` lets Android identify the caller. Caller-supplied extras
  are never trusted. Sensitive actions require the visible companion UI; its
  service requires the system `BIND_VPN_SERVICE` permission. No IPC API can stop the
  VPN or disable lockdown. X reports only installation/version, never protection.

The companion routes the selected application's UID through an IPv4/IPv6 TUN
using `addAllowedApplication`, with Onionmasq's synthetic DNS addresses
`169.254.42.53` and `fe80::53`. Onionmasq forwards supported TCP/DNS traffic via Arti.
UDP/QUIC relaying and bypass UID APIs are not exposed; the pinned upstream defaults
to UDP rejection, not a direct fallback. Media and auxiliary/isolated processes
may therefore fail; coverage must be observed on Android, not inferred from hooks.

Only the Tor engine requests `protect(socket)`; it is refused unless this engine
generation is current and Android always-on plus lockdown are detected. A retained
TUN descriptor is intended to blackhole traffic while the engine is stopped.
Native execution owns a separate duplicate descriptor. Worker shutdown is
asynchronous; another worker cannot begin until the previous one exits.

## Setup on a test device

1. Use a spare device or dedicated Android profile. Install the patched X APK and
   companion from a source you trust; confirm their signing identities separately.
2. Do not use X while configuring protection. In Piko settings, open **Privacy —
   Tor network**, or open the companion from the launcher. Confirm the target app.
3. Prepare the VPN and grant Android's VPN consent. A TUN alone is not a kill switch.
4. In Android VPN settings, enable **Always-on VPN** AND **Block connections without
   VPN** for **Piko Tor**. The engine refuses to start without both. These settings
   cannot be silently enabled by the app. Check they remain enabled after upgrades.
5. The companion distinguishes missing VPN, missing lockdown, unsupported strict
   Private DNS, absent network, connection in progress, engine failure, and Tor's
   reported readiness. None is a verified end-to-end protection claim.
6. “Retry Tor / refresh circuits” retries a failed engine or requests new circuits;
   it does not promise an immediate new exit IP or reset X's account identity.

**Important limits:** one VPN per Android profile. Per-app VPN plus lockdown can
block the OTHER applications in that profile rather than letting them bypass.
Strict Private DNS is intentionally unsupported in this milestone: engine paused,
TUN retained. Do not silently turn off Android's DNS policy as a workaround.
Stopping Piko Tor does not turn off Android lockdown. Revoking consent, disabling
lockdown, changing provider, or uninstalling the companion can expose direct
connectivity; the companion cannot override the user's system administrator choices.
Before first setup, during consent, and without system lockdown, no protection is
claimed. An authenticated X account remains identifiable; the ISP normally sees Tor.

## Build (Linux x86_64)

Prerequisites: JDK 17, Rustup, Python 3, git, curl, unzip, Android SDK licenses, and the
repository's usual GitHub Packages read credentials. Never put tokens in source.
`GITHUB_TOKEN`/`GITHUB_ACTOR` are read by the existing Gradle configuration.

```sh
# Installs pinned Android SDK/NDK, Rust and cargo-ndk. Review Android license prompts.
scripts/setup-tor-android.sh
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/sdk}"
export PATH="$HOME/.cargo/bin:$PATH"

# If cleaning, clean BEFORE building native libraries (they live under build/).
./gradlew clean
scripts/build-tor-native.sh
./gradlew buildAndroid :companion:assembleDebug :companion:testDebugUnitTest :companion:lintDebug
```

Outputs: `patches/build/libs/*.mpp` and
`companion/build/outputs/apk/debug/companion-debug.apk`. Debug keys are test-only,
not a stable production signing identity. No production release is configured.
For a smaller development APK, use matching native/Gradle ABI selections, e.g.
`scripts/build-tor-native.sh arm64-v8a` and `-PtorAbis=arm64-v8a`.

Native inputs: Onionmasq revision `086d05145f5aa711ac300d04edc19c26360bac29`,
its locked Cargo dependency graph (Arti 0.45.0), Rust 1.97.1, cargo-ndk 4.1.2,
NDK 27.3.13750724, Android native platform 29. `native/privacy.patch` disables
upstream tracing (including verbose builds). No PCAP/bridge/TURN controls are exposed.
Native compilation receives an allowlisted environment, not ambient credentials.
The build copies upstream's `dylib` output, strips symbols, checks every selected
ABI's ELF machine, 16 KiB LOAD alignment, non-executable stack, Android dependencies,
and JNI exports, then records SHA-256 hashes. Gradle refuses missing/wrong-revision
or changed binaries and missing/unresolved notices. Pinning is not a reproducibility
measurement or a substitute for dependency/security review.

To apply only the new optional patch to the supported X bundle (Morphe Desktop
1.18.1 requires Java 21+):

```sh
java -jar morphe-desktop-1.18.1-all.jar patch --exclusive \
  -p patches/build/libs/patches-3.9.0.mpp -e 'Tor network companion' \
  -o x-piko-tor.apk X-12.19.1-release.0.apkm
```

Use the bundle generated by YOUR build if the project version changes. Other
patches are intentionally excluded from this smoke check, not declared compatible.

## Validation evidence and remaining gates

Executed in the development VM (2026-10-19):

- `buildAndroid`; companion Java compilation, debug APK assembly and Android lint.
- JUnit state-policy checks, including all 512 combinations of nine prerequisite
  signals. These do **not** exercise Android lifecycle or actual native callbacks.
- Real native compilation for arm64-v8a, armeabi-v7a, x86, x86_64; ELF/JNI/dependency
  verification and packaging of all four libraries in the companion APK.
- `zipalign -c -P 16 4` and `apksigner verify` on the companion; signature verification
  on the patched X APK. Debug signing is not publisher authentication.
- Real Morphe patch application to X 12.19.1-release.0 / 312191000 (input APKM SHA-256
  `e288abb4b3bdf94c3d0397137b42cbf27a8f90049276166aee0383ad694dbd2c`). Static output
  inspection: distinct package names, companion visibility query, protected service
  only in the companion, no Onionmasq library injected into X.

Not yet demonstrated on Android: installation/actual UID separation, JNI startup,
real Tor bootstrap, X login/media/DMs/notifications, observed exit IP, DNS/IPv4/IPv6,
UDP/QUIC, native/WebView/auxiliary processes, lockdown interaction with other apps,
Tor failure while retaining TUN, service kill/TUN loss, reboot, permission revocation,
provider change, uninstall, network transitions, background/battery restrictions,
battery/memory/latency, and non-regression of other patches. The Android foreground
service exemption for a configured VPN is documented but not runtime-verified here.

Before describing a tested scenario as protected, run it with independent packet
capture (including DNS and IPv6), record the target's real UID and Android lockdown
settings, test cold start and each negative transition, and show both the successful
Tor path AND the absence of direct target traffic. A Tor exit IP alone is insufficient.
Do not log credentials, browsing destinations, packet payloads, or account tokens.

## Licenses / corresponding source

Piko is GPLv3 with its root `NOTICE` preserved. Onionmasq/Arti offer MIT/Apache-2.0;
individual transitive components retain their own licenses. Generated APK assets
contain Piko notices, a Cargo inventory with original texts and supplement provenance,
and Rust standard-library notices. See `native/licenses/README.md`, in particular
the separate IPFire GeoIP database attribution/license. These are attribution and
source records, not an automated legal opinion. A distributor must provide the
corresponding source/build instructions and preserve all applicable obligations;
do not distribute only a binary or present a debug prototype as production-ready.
