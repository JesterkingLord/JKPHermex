# Changelog

Notable changes to Hermex. Version headings correspond to GitHub releases;
unreleased changes accumulate at the top. Format follows
[Keep a Changelog](https://keepachangelog.com/) with Added / Changed / Fixed /
Security sections per release.

## [Unreleased]

### Added
- TBD (next iteration).

## [0.2.0] - 2026-07-14

### Added
- **GitHub update check**: Settings → About → "Check for updates" queries
  `api.github.com/repos/JesterkingLord/JKPHermex/releases/latest` and
  compares the tag against the installed version. When a newer release
  is available, the dialog shows the version, APK size, and release notes
  with a "Download" button that opens the APK in the browser. The
  network call is unauthenticated, timeouts in 10 s, and never blocks
  the rest of the screen.
- **GitHub release workflow** (`.github/workflows/android-release.yml`):
  pushing a `vMAJOR.MINOR.PATCH` tag builds the debug APK and attaches
  it to a GitHub Release named after the tag. Pre-release tags
  (`-rc1`, `-beta.2`) publish as `prerelease: true`.
- **16 accent presets** (was 6): added Amber, Cyan, Magenta, Orange,
  Teal, Indigo, Pink, EFER Crypt (blood-red), EFURC Cyan, EFEMM Purple,
  and JKP Void. The picker now wraps via `FlowRow` so all 16 fit on a
  phone screen; selected presets show their name underneath the circle.
- **About section** in Settings showing installed app version + the
  update channel label.

### Changed
- App rebranded from "Hermex" to "JKP Mobile" (launcher label, in-app
  chat subtitle, onboarding wordmark, widget description). Internal
  identifiers (package ID, SharedPreferences keys, DB names) unchanged
  so existing installs update in place.
- Bumped `versionCode` 1 → 2.

### Notes
- The in-app update check returns "You're up to date" until you push
  the `v0.2.0` tag and the workflow publishes the first GitHub Release.
- APK is signed with the debug keystore — fine for sideloading to your
  own devices, NOT for Play Store distribution.

## [0.1.0] - 2026-07-08

### Added
- Public open-source release of the Hermex codebase.
- First debug APK built via the standalone toolchain installer
  (`scripts/install-toolchain.sh`).