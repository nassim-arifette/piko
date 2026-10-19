# Supplementary license sources

`sources.json` records URLs and SHA-256 hashes for texts missing from published
Cargo packages. Most are original upstream workspace notices at the crate's
`.cargo_vcs_info.json` revision. They are copied unchanged. The build verifies
their hashes and incorporates them with locally shipped crate notices, including
`LICENSES/`, SPDX-named files and `COPYRIGHT*`.

Special cases:

- `void` 1.0.2 declares MIT in Cargo metadata and its tagged README but supplies no
  full template. Its README/author attribution and the SPDX MIT text are included.
- `cesu8` 1.1.0 includes Rust copyright notices and declares Apache-2.0/MIT. Those
  notices, its v1.1.0 source README and the SPDX MIT text are preserved. The README
  commit was located from its version-bump history, not a nonexistent v1.1.0 tag.
- `tor-geoip-db` 0.1.0-pre2 declares MIT/Apache-2.0 for its code. Its advertised Git
  commit was unavailable upstream during preparation. Its published Cargo manifest
  and source/export notices are retained instead, with the SPDX MIT terms. Cargo's
  locked registry checksum authenticates the downloaded crate, not that Git commit.
- **Embedded GeoIP data is separately CC BY-SA 4.0**, attributed to the IPFire
  Project, from https://location.ipfire.org/, export date 2026-03-19. The original
  IPv4/IPv6 conversion/export headers and full CC BY-SA 4.0 terms are included.
  Piko does not modify that embedded database. The original conversion is described
  by those headers; its exact packaged data is available in the pinned crate.

The generated inventory is a conservative union of non-dev dependency graphs for
the selected Android targets, including build dependencies. It is not a measured
symbol-level bill of materials. Do not mistake “all packages have notice text” for
a complete legal or security audit. Review the actual licenses and corresponding
source obligations before redistribution or production release.
