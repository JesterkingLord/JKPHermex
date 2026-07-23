# Changelog

Notable changes to Hermex. Version headings correspond to GitHub releases;
unreleased changes accumulate at the top. Format follows
[Keep a Changelog](https://keepachangelog.com/) with Added / Changed / Fixed /
Security sections per release.

## [v0.6.2-stable] - 2026-07-23

### Fixed
- **Grey band between composer and keyboard — second variant.** v0.6.1 zeroed
  the chat Scaffold's `contentWindowInsets`, which removed the nav-bar inset
  leak through `innerPadding`. But the band was still visible because
  `MainActivity.enableEdgeToEdge()` (without arguments) defaults to drawing
  **scrim under the navigation bar** (`EdgeToEdge.DefaultDarkScrim` =
  `0x66CDCDCD` on Android 12+), and that scrim shows through the area between
  the IME-pushed composer and the keyboard's top edge as a visible medium-gray
  band. Fix: pass `SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT)`
  for **both** `statusBarStyle` and `navigationBarStyle` — removes the scrim
  entirely on both light and dark variants. Combined with the v0.6.1 Scaffold
  change, the composer now sits flush above the IME with zero residual gap.

### Added
- **Structural regression test #4** (`ChatScreenImeInsetsLayoutTest#MainActivity
  enables edge-to-edge with both system bars fully transparent`) — fails loudly
  if anyone reverts to the no-argument `enableEdgeToEdge()` call (which would
  re-introduce the scrim) or uses anything other than `Color.TRANSPARENT` for
  one of the four scrim slots.

### Verified
- 2026-07-23: **327 unit tests, 0 failures, 0 errors, 0 skipped** across
  40 test suites (`./gradlew.bat testDebugUnitTest` BUILD SUCCESSFUL). New
  test added: 1 in `ChatScreenImeInsetsLayoutTest`.
- Debug APK assembles clean at `app/build/outputs/apk/debug/app-debug.apk`
- APK version bumped: `versionName 0.6.1 → 0.6.2`, `versionCode 14 → 15`

Server-side dependencies unchanged: this still pairs against the JKP
`v1.12.1-stable` host. **If you installed v0.6.1-stable and still saw the
band, install v0.6.2-stable** — the v0.6.1 fix only addressed the
contentWindowInsets half, not the SystemBarStyle scrim half.

Full release notes: [`RELEASE_NOTES_v0.6.2-stable.md`](RELEASE_NOTES_v0.6.2-stable.md).

## [v0.6.1-stable] - 2026-07-22

### Fixed
- **Grey band between composer and keyboard when typing.** When the IME
  (software keyboard) was up, the chat screen showed a dark band of empty
  space between the composer's bottom row of chips (`📁 workspace`, `👤 profile`,
  `🧠 show thinking`) and the keyboard. Root cause: `MainActivity.enableEdgeToEdge()`
  is on, and M3 `Scaffold`'s default `contentWindowInsets` was
  `WindowInsets.systemBars` — that re-applied the nav-bar bottom inset on top
  of the already-applied `.imePadding()`, so the inner PaddingValues consumed
  ~48 dp of vertical space the keyboard already occupies. Fix: zero out
  `contentWindowInsets` on the chat Scaffold (`WindowInsets(0, 0, 0, 0)`);
  status-bar inset remains owned by `HermexHeader.statusBarsPadding()`. IME
  inset remains owned by `.imePadding()` on the Scaffold root. Composer now
  sits flush above the keyboard, every dp of screen real estate is usable.

### Added
- **Structural regression test** (`ChatScreenImeInsetsLayoutTest`) — three
  source-parse assertions that fail loudly if anyone removes the fix or
  re-introduces `systemBars`/`navigationBarsPadding` inside the Scaffold body.
  This is the cheapest possible sentinel: no Robolectric/compose-ui-test deps
  needed.

### Verified
- 2026-07-22: **326 unit tests, 0 failures, 0 errors, 0 skipped** across
  40 test suites (`./gradlew.bat testDebugUnitTest` BUILD SUCCESSFUL). New
  test added: 3 tests in `ChatScreenImeInsetsLayoutTest`.
- Debug APK assembles clean: 13.4 MB at `app/build/outputs/apk/debug/app-debug.apk`
- APK version bumped: `versionName 0.6.0 → 0.6.1`, `versionCode 13 → 14`

No operator-blocking work. APK functionally identical except the keyboard
positioning. Server-side dependencies unchanged: this still pairs against the
JKP `v1.12.1-stable` host.

Full release notes: [`RELEASE_NOTES_v0.6.1-stable.md`](RELEASE_NOTES_v0.6.1-stable.md).

## [v0.6.0-stable] - 2026-07-22

First stable tag cut since 0.6.0 final. Coordinated with JKP `v1.12.1-stable` (same stable branch name on both repos: `stable/v1.12.1-jkphermex-0.6.0`). APK already at `versionCode 13` on the operator's Tailscale phone — no rebuild required for the tag.

Added in this cut:
- 323/323 unit tests passing (verified 2026-07-22; `./gradlew.bat testDebugUnitTest` BUILD SUCCESSFUL in 14s across 39 test suites; exceeds the 255+ plan baseline)
- Reasoning controls UX fix from `39aa194`: two reasoning controls (xhi picker + on/off pill) clarified so they no longer look like duplicates

Carries from 0.6.0 base: Bearer pairing + hang honesty + client-error catalog + 7.3 model parity + 7.4 full auto-reconnect (1s/2s/4s backoff + best-effort session re-fetch + auth → re-pair) + 6.6 live camera QR (CameraX 1.4.1 + ZXing 3.5.3) + 13.10 stream recovery.

Server-side improvements from `JKP v1.12.1-stable` apply automatically:
- Telegram stuck-typing fix (JKP commit `c0a9f935d`) — multi-chunk replies no longer leak the typing bubble past the last message
- Hermes v0.19.0 doc/version sync (JKP commit `81a4b527a`) — release lineage now agrees on `v0.19.0`
- 90-second cloud-provider chat hang eliminated (JKP commit `cca2bf606` + `api_max_retries: 1`)

Operator-blocked items unchanged: Play Store upload (6.1, secrets in 1Password), physical device QA matrix (6.8).

Full release notes: [`RELEASE_NOTES_v0.6.0-stable.md`](RELEASE_NOTES_v0.6.0-stable.md).

## [Unreleased]

### Verified
- 2026-07-22: 323 unit tests, 0 failures, 0 errors, 0 skipped (across 39 test suites; ./gradlew.bat testDebugUnitTest BUILD SUCCESSFUL in 14s; exceeds 255+ plan baseline). Includes `AuthManagerPairingTest`, `PairingIntentParserTest`, `HangHonestyTest`, `UpdateCheckerTest`, `AuthManagerTest` from the 7.4 / 6.6 / 7.3 ship slices.

### Planned (operator-blocked)
- Play Store upload: Developer account, real release keystore (1Password),
  mipmap icons, feature graphic, screenshots, listing from
  `docs/PLAY_STORE_LISTING.md`, privacy URL from `PRIVACY.md`
- Physical QA matrix on device

## [0.6.0] - 2026-07-21

### Added
- **Full auto-reconnect (7.4)** — `ConnectionSupervisor` + `ReconnectController` drive the full reconnect lifecycle end-to-end with backoff (1s/2s/4s), jitter, and host-grant refresh on transport drops. Best-effort session re-fetch recovers a turn that completed while the wire was down (same-turn guard); auth errors clear grant + route to re-pair; never re-POSTs the user message. Pure `reconnectPolicy` shared contract with desktop + PWA. Shipped in **v0.5.0**.
- **Live camera QR scanner (6.6)** — commit `fd50355`. Local-only decode via **CameraX 1.4.1** + **ZXing 3.5.3** (no network round-trip for the QR contents). `CameraQrScannerView` wires preview + `ImageAnalysis` frame decode + runtime `CAMERA` permission + torch toggle + viewfinder reticle, with a paste fallback when the user prefers the clipboard or the device has no camera. `QrDecoderTest` adds 8 unit tests covering decode happy-path, malformed payloads, and empty frames; camera-less devices route straight to the paste dialog.
- **Session model preference parity (7.3)** — the chat composer seeds the model/provider from the host session when the user has not picked one yet (user pick still wins; catalog default stays display-only). Model switch propagates to the active session without re-pair.
- **Stream recovery offer UI** (excellence 13.10) — after transport/stream drops, `streamRecoveryOffer` + tip "Partial reply kept. Edit or resend…" on chat screen; pure helpers from 13.9 drive the decision.
- **SSE error catalog honesty + stream-drop recovery helpers** (13.9) — `HangHonesty.streamErrorMessage` / `ClientErrorCatalog` routing; `streamDropRecovery` + `shouldKeepPartialTranscript` pure helpers.
- **Bearer pairing on all API/SSE** (0.6.0-rc1) — 401 clears grant; full Bearer auth across every endpoint.
- **Hang honesty stall tip** (0.6.0-rc2) — ≥15s silent stream shows honest stall tip; `ClientErrorCatalog` aligned with host `jkp.client_errors`.
- **Forget this JKP device** (0.6.0-rc2) — Settings local-only tip + grant revocation UX.

### Changed
- `versionName` **0.6.0** (`versionCode` 13).

### Verification
- Unit tests: **519 passed / 0 failures** (BUILD SUCCESSFUL, 2026-07-21)
- Release APK: unsigned (Play Store keystore is operator-blocked; signing config
  in `app/build.gradle.kts` is ready — set `HERMEX_RELEASE_*` env vars or
  `android/local.properties` entries to produce a signed build)

## [0.6.0-rc6] - 2026-07-18

### Added
- **Stream recovery offer UI** (excellence 13.10) — after transport/stream
  drops, `streamRecoveryOffer` + tip "Partial reply kept. Edit or resend…"
  on chat screen; pure helpers from 13.9 drive the decision.

### Changed
- `versionName` **0.6.0-rc6** (`versionCode` 12).

## [0.6.0-rc5] - 2026-07-18

### Added
- **Stream-drop recovery pure helpers** (`HangHonesty.streamDropRecovery`,
  `shouldKeepPartialTranscript`) — excellence 13.9 / 7.4 partial.

### Fixed
- **SSE in-band error honesty** — stream `error` events route through
  `HangHonesty.streamErrorMessage` / `ClientErrorCatalog` (not raw JSON);
  auth failures clear local grant via `onAuthError`.
- **Catalog classify** — free-text payloads that embed catalog codes
  (e.g. `invalid_api_key expired`) no longer fall through to `unknown`;
  auth heuristic only forces re-pair on invalid+key/grant/token signals
  (bare/status-403 policy bodies stay `policy_blocked`, not re-pair).

### Changed
- `versionName` **0.6.0-rc5** (`versionCode` 11).

## [0.6.0-rc4] - 2026-07-18

### Added
- **Session model preference parity (7.3)** — pure
  `resolveSessionModelSelection` seeds the chat composer from the host
  session’s model/provider when the user has not picked a model yet
  (user pick still wins over session; catalog default is display-only).
- **Catalog lock** — `ClientErrorCatalog.REQUIRED_HOST_CODES` +
  `invalid_api_key` entry aligned with host `jkp.client_errors`
  (excellence 13.8a).

### Changed
- `versionName` **0.6.0-rc4** (`versionCode` 10).

## [0.6.0-rc3] - 2026-07-18

### Added
- **Stream transport honesty** (`HangHonesty.transportFailureMessage`): SSE
  drops map to network catalog copy + resend/session tip instead of raw OkHttp
  exceptions (7.4 slice early).

### Changed
- `versionName` **0.6.0-rc3** (`versionCode` 9).

## [0.6.0-rc2] - 2026-07-18

### Added
- **Hang honesty** for long silent streams (`HangHonesty` + ChatViewModel
  stall watch ≥15s). Surfaces the same “approve on host / YOLO” tip as the
  Hermex PWA so a host approval wait does not look like a crashed agent.
- **`ClientErrorCatalog`** aligned with host `jkp.client_errors` codes
  (auth, quota, rate_limit, policy, server, network, approval_wait).
- Settings **“Forget this JKP device”** label + copy that local forget ≠
  host revoke (`python -m jkp pair revoke` on the laptop).

### Changed
- `ApiError.userMessage` routes Network / Unauthorized / many HTTP statuses
  through the shared catalog.
- `versionName` **0.6.0-rc2** (`versionCode` 8).

## [0.6.0-rc1] - 2026-07-16

### Added
- **Bearer pairing** on API/SSE via `BearerAuthInterceptor`; 401
  `invalid_device_grant` clears the local grant.

## [0.5.0] - 2026-07-15

### Added
- **G-lite math rendering (Markdown)**. The chat composer / message
  timeline now recognise `$...$` inline math and `$$...$$` display
  math in assistant responses, replace the LaTeX commands with their
  Unicode equivalents, and render the result in italic serif so the
  math is visually distinct from prose. Examples:
  - `$a^2 + b^2 = c^2$` → `a²+b²=c²`
  - `$\alpha + \beta = \gamma$` → `α+β=γ`
  - `$\sum_{i=1}^n x_i$` → `Σᵢ₌₁ⁿxᵢ`
  - `$\int_0^1 f(x)\,dx$` → `∫₀¹f(x)dx`
  - `$\mathbb{R}, \mathbb{N}, \mathbb{Z}$` → `ℝ, ℕ, ℤ`
  - `$\vec{F} = m\vec{a}$` → `F⃗ = ma⃗` (combining arrow above)
  - `$\hat{x}, \tilde{x}, \bar{x}$` → `x̂, x̃, x̄` (combining marks)
  - `$\frac{a+b}{c}$` → `(a+b)/c` (parens disambiguate the division)
  - `$\sqrt{x}$` → `√x`
  - Currency-style `$5 and got $3 back` is correctly **NOT** matched
    as math — the inline matcher requires at least one math
    character AND no common English words in the body.

  Implementation: `ui/markdown/MathLite.kt` (pure object, no Compose
  deps, no extra runtime deps). 25 unit tests in
  `MathLiteTest` lock in the behaviour — the test suite went from
  230 to 255 tests with all green. The renderer is wired in via a
  tagged-token pass in `Markdown.kt` — math regions become
  `⟦display:...⟧` or `⟦inline:...⟧` markers that the inline
  formatter recognises and styles with `FontStyle.Italic +
  FontFamily.Serif`.

- **Auto-update check on app launch**. `MainActivity` now runs
  `UpdateChecker.check()` once when the chat screen mounts, and if
  GitHub has a newer release it shows a non-intrusive Snackbar at
  the bottom: `JKP Mobile X.Y.Z is available` with a "View" action
  that opens the GitHub release page in the browser. The check is
  silent (no spinner, no modal) — if the network fails or the API
  returns malformed JSON, nothing is shown and the user can still
  tap "Check for updates" in Settings for a manual retry.

- **In-app About links**. The Settings → About section now lists:
  - **Privacy policy** → `github.com/JesterkingLord/JKPHermex/blob/master/PRIVACY.md`
  - **Source on GitHub** → `github.com/JesterkingLord/JKPHermex`
  - **Security policy** → `github.com/JesterkingLord/JKPHermex/blob/master/SECURITY.md`

  Each is a tappable row with a subtitle, a `↗` icon, and uses the
  platform's default browser via the existing
  `openUrlInBrowser(context, url)` helper. The new `AboutLinkRow`
  composable lives in `SettingsScreen.kt`.

- **Play Store listing draft** at `docs/PLAY_STORE_LISTING.md` —
  copy-paste-ready short description (68 chars), full description
  (~1,650 chars), "What's new in this release" block (~470 chars),
  categorisation, and a checklist of operator-side blockers
  (Play Console account, mipmap launcher icons, feature graphic,
  screenshots). The code-side work is done; the upload itself
  is operator-side.

- **Privacy policy** at `PRIVACY.md` — published in the source
  repository so the version in the Play Console listing is always
  the same as the version in the code that the user can audit.
  Covers: what the app stores on device, what it sends over the
  network, what it does NOT do (no analytics, no crash reporting,
  no advertising, no FCM), third-party services (GitHub API for
  the in-app update check), children's privacy, data retention,
  the user's rights, change-control process, and contact info.

### Changed
- `MainActivity` now wraps its `Crossfade` in a `Box` that overlays
  a `SnackbarHost` so the auto-update prompt can be shown over
  any screen (session list, chat, settings) without needing to
  thread a state through each screen's Composable signature.
- `MarkdownText` (composable) now runs the math pre-processor
  before the block parser; the inline renderer (`inlineAnnotated`)
  recognises the `⟦display:...⟧` / `⟦inline:...⟧` tagged tokens
  emitted by `MathLite.replaceWithTags`. The math style is
  italic + serif, visually distinct from prose.

### Tests
- **+25 unit tests, 255/255 green** (verified via JUnit XML):
  - `ui/markdown/MathLiteTest` (25): parser cases (inline / display
    / currency / unclosed / multiple / shadowed), command cases
    (Greek, operators, fractions, sup/sub, integrals, sets, trig,
    arrows, accents, ellipses, `\dots` / `\ldots` / `\cdots`),
    tag-emission cases (display / inline / mixed / no-op), and
    end-to-end LLM-output shapes (Pythagoras, Gaussian integral,
    quadratic formula).
- 230 pre-existing tests still green.
- `./gradlew :app:assembleRelease` still produces a 1.7 MB signed
  R8-minified APK (R8 keeps `kotlinx.serialization` and
  `kotlinx.coroutines` clean — verified with the existing
  `proguard-rules.pro`).

## [0.4.1] - 2026-07-15

### Changed
- **Release build pipeline hardened for Play Store upload.** `assembleRelease`
  now produces an R8-minified, resource-shrunk, properly-signed APK ready
  to upload to Google Play (the operator still needs the Play Developer
  account and the actual `.aab`, both operator-side):
  - `app/build.gradle.kts` release buildType:
    `isMinifyEnabled = true`, `isShrinkResources = true`, and the new
    `proguard-rules.pro` keep-set. Result: 11 MB debug → **1.6 MB unsigned
    release, 1.7 MB signed release** (6.5× reduction).
  - **Release signing creds** resolve from environment variables
    (`HERMEX_RELEASE_STORE_FILE / _PASSWORD / _KEY_ALIAS / _KEY_PASSWORD`)
    first, then `android/local.properties` (`hermex.release.storeFile =
    ...`, etc.). Both forms are gitignored. If neither is set, the build
    still succeeds and emits a clear warning that the release APK is
    unsigned. The CI release workflow reads the env-var form from
    GitHub Secrets.
  - `local.properties.example` documents the four required keys and the
    `keytool -genkey` command for generating a new keystore.

### Added
- **`app/proguard-rules.pro`** — minimum safe R8 keep-set, verified by
  the working `assembleRelease` run. Covers:
  - `kotlinx.serialization` `$$serializer` companions (would otherwise
    produce `SerializationException: Serializer for class X is not found`
    at runtime — the most common R8 footgun for Kotlin apps).
  - `kotlinx.coroutines` `AndroidDispatcherFactory` (ServiceLoader).
  - `OkHttp` / SSE (platform reflection, `Conscrypt`, `BouncyCastle`).
  - `Room` generated `*_Impl` classes and `@Entity` / `@Dao` annotations.
  - `ViewModel` / `AndroidViewModel` constructors.
  - Manifest-referenced entry points (`MainActivity`, `HermexApp`,
    `HermexWidgetProvider`, `ActiveRunService`).
  - Source line numbers for crash-report readability, with source-file
    renaming so we still ship obfuscated bytecode.
- **`res/xml/backup_rules.xml`** (Android <12) and
  **`res/xml/data_extraction_rules.xml`** (Android 12+) — exclude
  `hermex_secrets`, `hermex_prefs`, `hermex_servers`, and `hermex.db`
  from BOTH cloud backup AND device-transfer flows. The pairing grant
  is a bearer credential; a phone restore must NOT silently trust a
  different physical device. Referenced from `<application>` via
  `android:fullBackupContent` and `android:dataExtractionRules`.
- **Manifest hardening** (`AndroidManifest.xml`):
  - `tools:targetApi="34"` for clean lint output.
  - `configChanges` on `MainActivity` so rotation / dark-mode toggle
    doesn't tear down the share-intent target.
  - `directBootAware="false"` on the widget receiver (Hermex is
    encrypted-pre-boot; the widget doesn't need direct-boot state).
  - In-line comments justify every `exported="true"` surface.

### Tests
- 230/230 unit tests still green (verified via JUnit XML).
- New smoke test: `./gradlew :app:assembleRelease` succeeds with
  a synthesized JKS keystore → `apksigner verify` reports v2 signing
  valid with 1 signer.

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
