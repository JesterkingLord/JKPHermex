# Changelog

Notable changes to Hermex. Version headings correspond to GitHub releases;
unreleased changes accumulate at the top. Format follows
[Keep a Changelog](https://keepachangelog.com/) with Added / Changed / Fixed /
Security sections per release.

## [Unreleased]

### Added
- TBD (next iteration).

## [1.5.0] - 2026-07-14

### Added (iOS parity with Android v0.2.0)
- **App label renamed** to "JKP Mobile" in both `APP_DISPLAY_NAME`
  build settings (Debug + Release) and the side-by-side
  `BranchTestFlight.xcconfig`. Internal bundle ID
  (`com.uzairansar.hermesmobile`) unchanged so existing installs
  upgrade in place.
- **GitHub update check** in Settings → About → "Check for updates":
  queries `api.github.com/repos/JesterkingLord/JKPHermex/releases/latest`
  and shows one of three sheets (update available, up to date, failed).
  Uses `AppUpdateChecker` (typed actor) + `GitHubRelease` Codable model
  + `SemanticVersion` parser with the same "release > rc" rule as
  Android. 10-second timeout. **Unverified on a Mac — code was written
  on Windows; needs `xcodebuild test` on macOS before tagging.**
- **16 accent presets** (was 6) in `HeaderLogoColor.presets`. New
  options: Amber, Cyan, Magenta, Orange, Teal, Indigo, Pink, EFER Crypt,
  EFURC Cyan, EFEMM Purple, JKP Void. The Settings picker switched
  from `HStack` (clipped at ~7 items) to `LazyVGrid` so all 16 fit
  cleanly on a phone width.
- **iOS release workflow** (`.github/workflows/ios-release.yml`):
  triggers on `vMAJOR.MINOR.PATCH` tag push, builds an `.ipa` on
  `macos-14`, attaches it to a GitHub Release. Requires 5 secrets
  (`IOS_DEVELOPER_TEAM_ID`, `IOS_CODE_SIGNING_CERT_P12`,
  `IOS_CODE_SIGNING_CERT_PASSWORD`, `IOS_PROVISIONING_PROFILE`,
  `KEYCHAIN_PASSWORD`) — see workflow comments for setup.
- **Unit tests** for the new code:
  `HermesMobileTests/AppUpdateCheckerTests.swift` (11 tests covering
  JSON decoding, unknown-field tolerance, asset filter, semver parse
  + compare). Unverified on a Mac from Windows.

### Changed
- `MARKETING_VERSION` 1.4 → 1.5.0 across all 8 build configurations
  (app, share extension, widget, tests × Debug/Release).
- `Bundle.main.infoDictionary["CFBundleShortVersionString"]` is the
  source of truth for the About card's "App version" row.

### Notes
- **iOS code is unverified on this Windows host.** Xcode isn't
  available; the Swift files were written following existing patterns
  (`APIClient` actor, `SettingsCard`, `String(localized:)`) but a Mac
  run of `xcodebuild test` is required before tagging. The release
  workflow will produce a signed .ipa when run on GitHub Actions if
  the 5 signing secrets are configured.
- Internal identifiers (`com.uzairansar.hermesmobile` bundle ID,
  `hermesmobile` Keychain service, `Hermex` accessibility labels in
  the localization table) deliberately left unchanged. Renaming any
  of them would force an uninstall + re-pair on upgrade.

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