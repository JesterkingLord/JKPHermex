# Changelog

Notable changes to Hermex. Version headings correspond to GitHub releases;
unreleased changes accumulate at the top. Format follows
[Keep a Changelog](https://keepachangelog.com/) with Added / Changed / Fixed /
Security sections per release.

## [Unreleased]

### Verified
- Android `0.4.0` reasoning-effort selector compiles clean, passes 230/230
  unit tests, and produces a 11 MB debug APK. Pairs symmetrically with the
  iOS `0.4.x` effort selector and the desktop CLI's `/reasoning` command.

### Planned
- Android `0.5.0` (next): Play Store prep — release signing config that reads
  from `local.properties` / env vars, ProGuard/R8 rules for Compose +
  Serialization + OkHttp + Kotlinx Coroutines, manifest hardening audit
  (`android:exported` review, intent filter consolidation, app links
  verification), and an end-to-end "no-cleartext on real device" check.

## [0.4.0] - 2026-07-15

### Added
- **Reasoning-effort selector on Android, mirroring the iOS `0.4.x` feature.**
  Symmetric with the desktop CLI's `/reasoning` command — pick an effort
  in the mobile app, the next `/api/chat/start` request carries
  `reasoning_effort: "high"` (etc.), and the server is the source of truth:
  - New "Reasoning effort" selector chip in the chat composer (next to the
    model selector). Seven options matching the iOS sheet: `auto` (local
    sentinel — never round-trips as the literal string "auto" because the
    server rejects it), `none`, `minimal`, `low`, `medium`, `high`, `xhigh`.
  - New "🧠 reasoning on / off" pill in the composer that toggles whether
    the `ThinkingCard` is shown or hidden for the current and future
    assistant turns.
  - Optimistic local update on tap; rollback on API failure. Server
    clamping (e.g. operator asked for `xhigh` but the gateway downgraded
    to `medium` for this model) is reflected back into the UI with a
    user-visible banner: "Server adjusted to MEDIUM".
  - "Wait for current response" guard matches iOS — you can't change
    effort mid-run; the chip is disabled until the assistant finishes
    its current turn.
  - `loadReasoningState()` runs on every chat open, so opening the app
    on a different device picks up whatever the desktop set last.

### Changed
- `ChatStartRequest` now accepts an optional `reasoningEffort: ReasoningEffort?`.
  The field is emitted to the wire as `reasoning_effort: "..."` only when
  the user picked a non-`AUTO` value; `AUTO` is a local-only sentinel.
- `ApiClient.startChat()` signature: `startChat(prompt, sessionId?, modelId?,
  effort?)` — `effort` is null when `AUTO` so existing call sites in
  `ChatViewModel.sendNow()` and the tests behave identically.

### Tests
- **+34 unit tests, 230/230 green** (verified via JUnit XML, not summary):
  - `model/ReasoningStatusTest` (9): parser is case-insensitive, trims
    whitespace, accepts both `snake_case` and `camelCase` from the
    gateway, `AUTO` never round-trips, unknown values fall back to
    `null` (server is the source of truth, never lie about the model).
  - `network/ApiClientReasoningTest` (11): GET/POST wire protocol,
    `AUTO` short-circuits the POST (no network call), 401 → `Unauthorized`,
    500 → `Http`, network failure → `Network`, plus 13 wire-level
    invariants.
  - `features/chat/ChatViewModelReasoningTest` (11): hydration from
    prefs, optimistic updates, server-clamp handling, rollback on
    error, `null` prefs safety, `reasoning_effort` included in
    `/api/chat/start` when non-`AUTO`, omitted when `AUTO`.
  - `config/AppPrefsTest` (+3): `AUTO` round-trips as empty string
    (never the literal `"auto"`), corrupt / empty persisted values
    fall back to `AUTO`, prefs survive process restart.
- Added `kotlinx-coroutines-test:1.9.0` (test-only dep) for the
  viewmodel tests. Production code uses the existing `ioDispatcher`
  abstraction and real `Dispatchers.IO`.

## [0.3.0] - 2026-07-14

### Added
- **"Scan QR or paste pairing URL" on Connect page.** Pasting the URL
  produced by the desktop's `python -m jkp pair` (or any QR scan) now
  registers the phone as a paired device on the server:
    - `PairingIntentParser` decodes the URL (both `?query` and `#fragment`
      forms supported — desktop uses the fragment so pair_id/token
      never appear in HTTP request lines) into a typed outcome:
      `CompletePairing`, `ServerUrlOnly`, or `Invalid`.
    - `AuthManager.pairAndConfigure(parsed, deviceName)` POSTs to
      `/v1/pair/complete` and persists the returned `grant` and
      `device_id` into `SecretStore` under the matching server key.
    - `OnboardingViewModel.pairFromText(rawText)` wires the two together
      and falls back to clipboard when the field is blank.
    - The Connect screen adds a "Scan QR or paste pairing URL" entry
      point below the existing Connect section, opening an `AlertDialog`
      with a text field, paste-from-clipboard, Pair + Cancel.
- Cleartext policy (`CleartextPolicy`) shared between the parser and
  the existing URL normalizer, so QR flows and typed URLs agree on
  what's allowed (RFC1918 + ts.net + loopback; public hosts must be
  https).
- **48 new tests** (196/196 green across 30 test classes):
    - `PairingIntentParserTest` (18) — query + fragment, defaults,
      cleartext allowlist, malformed inputs, case-insensitive scheme.
    - `ApiClientPairingTest` (8) — happy, 400/401/410/409, network
      failure, decoding failure, forward-compat with unknown fields.
    - `AuthManagerPairingTest` (9) — happy, idempotent re-pair,
      error → no secret writes, empty grant → failed, signOut
      drops pair fields, `forgetServer(id)` drops the scoped row.

### Notes
- The grant is **stored but not yet used as Bearer auth** — v0.4.0 will
  switch `ApiClient` to read `PAIR_GRANT` from `SecretStore` and send
  `Authorization: Bearer …`. For now, after a successful pair, the
  server URL is filled in and the user proceeds with the typed-password
  flow as before. The pair record still exists on the server and the
  grant is held locally so a future upgrade picks up seamlessly.
- Camera/QR scanner UI is **deferred to v0.4.0** — paste-first so the
  same code path works on any device without runtime permission prompts.
- iOS parity for this feature is staged in the working tree but **not
  shipped in v0.3.0** (will be cut as iOS v1.6.0 in a follow-up).

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
