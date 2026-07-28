# JKPHermex — Plan & Roadmap

**Product:** Native **Android** control surface for a self-hosted **JKP / Hermes** agent  
**Repo:** `E:\JKPHermex` · GitHub: `JesterkingLord/JKPHermex`  
**Current Android development version:** **`0.8.14`** (`versionCode` 37) on `feat/wave-9-premium-polish`
**Latest documented stable line:** **`0.7.2-stable`** (`versionCode` 18), tag `v0.7.2-stable`
**Stable line:** tag `v0.7.2-stable`, branch `stable/v1.12.1-jkphermex-0.7.2` (coordinated with `JKP v1.12.1-stable`)
**Last roadmap refresh:** `2026-07-28` (0.8.14 device-verified scrollbar, accessibility-target, migration, and IME stabilization)
**Authoritative for “what’s next” on the phone.** Port history: [`ANDROID_PORT_PLAN.md`](ANDROID_PORT_PLAN.md).  
**Host roadmap (laptop agent):** [`E:\JKP\Jester-King-Prime-with-Hermes-Base-Fork\docs\PLAN_AND_ROADMAP.md`](file:///E:/JKP/Jester-King-Prime-with-Hermes-Base-Fork/docs/PLAN_AND_ROADMAP.md) · excellence: [`2026-07-18-jkp-overall-excellence-program.md`](file:///E:/JKP/Jester-King-Prime-with-Hermes-Base-Fork/docs/superpowers/plans/2026-07-18-jkp-overall-excellence-program.md) · v1.13: [`2026-07-16-jkp-v1.13-roadmap.md`](file:///E:/JKP/Jester-King-Prime-with-Hermes-Base-Fork/docs/superpowers/plans/2026-07-16-jkp-v1.13-roadmap.md)

---

## 0. Mental model

| Layer | Who | Where |
|---|---|---|
| **Execution plane** | JKP agent + gateway + MCPs | Operator laptop (`Jester-King-Prime-with-Hermes-Base-Fork`) |
| **Control plane (native)** | **JKPHermex** | This repo (`android/`) |
| **Control plane (browser)** | Hermex PWA | Host `/mobile` (same-origin pairing) — not this APK |

The phone **does not** run the agent. It authenticates, lists/opens sessions, streams chat, and recovers offline. Inventing API paths or JSON shapes is forbidden — verify against the live JKP host or pinned contracts.

---

## 1. Current state (2026-07-28)

### Active 0.8.14 quality pass

The source tree is substantially ahead of the latest documented stable tag. The
0.8.14 development build includes the drawer/sidebar, grouped sessions,
pull-to-refresh, Notes, Prompts, native markdown, voice dictation, workspace/Git
tools, and the Wave 9 composer features. The current pass is hardening those
features rather than adding unverified server contracts.

Completed in source and JVM/build verified:

- Pixel-based, draggable chat scrollbar with a measured-height cache, inverse
  scroll targeting, edge snapping, multi-frame target refinement, locale-stable
  accessibility progress, 48 dp hit area, and throttled debug-only logging.
- Cold-start session reliability: valid network results survive Room/cache write
  failures; unexpected refresh errors always clear loading and remain retryable;
  the visible list now derives directly from observed Compose state.
- Non-destructive Room v2→v3 migration preserving local Notes and Prompts.
- One consistent phone drawer across primary screens; duplicate chat navigation
  controls removed; Projects, Tasks, Skills, Memory, and Insights are first-class
  drawer destinations.
- Compact session header and composer controls with vector icons, 48 dp targets,
  stable semantic labels, and no forced primary-row clipping.
- Debug lint succeeds; the remaining lint warnings are dependency-update notices,
  intentional cleartext opt-in for operator-selected self-hosted HTTP servers,
  legacy launcher/widget compatibility, and non-blocking resource suggestions.

The 2026-07-28 connected-phone pass completed the identified remediation:

- One contextual 48 dp jump-to-latest control replaces the stacked arrows and
  settles at the true end even when the final message is taller than the viewport.
- The right-edge scrollbar exposes a named adjustable SeekBar node, a progress
  range/action, and locale-stable percentage state while preserving direct tap
  and drag behavior.
- Shared accent swatches, bulk actions, project/session rows, attachments,
  suggestions, workspace rows, and transcript actions maintain 48 dp targets.
- A real isolated Room v2 database was opened and migrated on the OPPO without
  touching `hermex.db`; the note and prompt survived and the note status defaulted
  to `idea`.

Remaining release operations are intentionally operator-gated: configure the
release keystore, sign, update the final release version/notes as chosen, then
approve push, tag, PR, and distribution. Manual TalkBack traversal was stopped at
the operator's request; the service was restored to its original disabled state.

### Shipped

| Version | Highlights |
|---|---|
| **0.8.14 (development)** | Drawer/navigation consolidation; Notes + Prompts; composer feature rail; pixel scrollbar rewrite; cold-start/cache reliability; data-preserving Room migration; accessibility and density pass |
| **0.6.0-rc6** | Full auto-reconnect (7.4) + live camera QR scanner (6.6, commit `fd50355` — CameraX 1.4.1 + ZXing 3.5.3) + stream recovery offer UI (13.10) |
| **0.6.0-rc5** | SSE error catalog honesty + free-text classify + streamDropRecovery pure helpers (13.9) |
| **0.6.0-rc4** | Session model preference parity (7.3) — composer seeds from host `session.model`; `invalid_api_key` + required-code catalog lock (host excellence 13.8a/b) |
| **0.6.0-rc3** | SSE transport failure → network honesty + resend tip (7.4 early) |
| **0.6.0-rc2** | Hang honesty stall tip (≥15s silent stream); `ClientErrorCatalog` aligned with host `jkp.client_errors`; Settings “Forget this JKP device” local-only tip; ApiError catalog routing |
| **0.6.0-rc1** | Bearer pairing on all API/SSE; 401 clears grant |
| **0.5.0** | G-lite math in chat markdown; auto-update check → GitHub releases Snackbar; 255 unit tests green; debug + signed release APKs verified |
| **0.4.x** | Release signing / Play prep scaffolding; proguard; manifest hardening |
| **0.3.0** | Physical phone ↔ live JKP: sessions, stream, MiniMax response (2026-07-14 evidence) |
| Phases 0–3, 5–9 | Setup, networking/auth, onboarding, session list, composer, workspace/git, server panels, settings, platform hooks — see ANDROID_PORT_PLAN |

### Open from ANDROID_PORT_PLAN (reconcile)

| Phase | Status note |
|---|---|
| **4 – Chat + SSE** | Functionally shipped on device (0.3+ evidence); hang honesty added in 0.6.0-rc2 |
| **10 – Polish + release prep** | **Operator-blocked** Play assets / keystore / console (see §2) |
| **11 – Native JKP pairing completion** | Bearer **done** in 0.6.0-rc1; QR optional remains P1 |

### Boundaries (locked)

- Kotlin + Jetpack Compose only for shipping Android UI (no RN/Flutter).  
- No new third-party deps without approval (`libs.versions.toml`).  
- Tolerant decoding for all upstream JSON.  
- iOS tree is **reference / upstream parity** unless explicitly scheduled.  
- Do not commit secrets, release keystore, or phone screenshots with paths/session metadata.

---

## 2. Roadmap by version

### 0.8.14 — Active completion gate

| Gate | Status |
|---|---|
| Unit tests + debug APK | **GREEN**: 547 debug + 547 release tests, zero failures/errors/skips; both APK variants assemble |
| Android lint | **GREEN** (`lintDebug`; 0 errors, 42 reviewed warnings) |
| Scrollbar math, dragging, semantics | **GREEN on device**: top `0.001`, midpoint `0.494`, bottom `1.000`, and continuous drag `0.665` verified on a 132-message chat; adjustable SeekBar semantics visible in the Android hierarchy |
| Cold-start sessions | **GREEN**: three force-stop launches returned all 37 live conversations on OPPO CPH2343 |
| Notes/Prompts upgrade safety | **GREEN on device**: isolated real Room v2→v3 open preserved note/prompt data; production DB untouched |
| Navigation + composer density | **GREEN on OPPO**: 360 dp and prior 1.35x audit findings remediated; 48 dp controls/rows verified; Gboard meets the metadata rail with no gap |
| Release signing / tag / publish | **Operator-blocked / approval-gated** |

Do not implement any endpoint mentioned only in an old excellence plan. Verify
new API work using the precedence in `AGENTS.md` and open an issue before coding.

### 0.5.x — Prior stable baseline

- Keep 255+ unit tests green on every change.  
- Auto-update check + About / privacy links stay honest.  
- Document install path for operator phone.

### 0.6.0 — SHIPPED (2026-07-21, tag `v0.6.0`, APK on GitHub Releases)

| # | Work | Priority |
|---|---|---|
| 6.1 | Google Play Developer account + real release keystore (1Password) | **BLOCKED — operator secrets** |
| 6.2 | Launcher mipmaps, feature graphic, screenshots | **BLOCKED — operator assets** |
| 6.3 | Store listing from `docs/PLAY_STORE_LISTING.md` + `PRIVACY.md` URL | **BLOCKED — Play console** |
| 6.4 | **`ApiClient` prefers JKP pairing grant** → `Authorization: Bearer <grant>` (no log/URL leak). **Host freeze:** JKP `docs/PAIRING_CONTRACT.md` + `python -m jkp pair contract` / `GET /v1/pair/contract` | **SHIPPED in 0.6.0-rc1** |
| 6.5 | Password/cookie fallback for non-JKP hermes-webui servers | **SHIPPED** (AuthManager password + SessionCookieJar) |
| 6.6 | Camera QR for `PairingIntentParser` (paste/manual fallback remains) | **SHIPPED (operator-approved 2026-07-21).** Live CameraX scanner: `QrDecoder` (ZXing, local-only decode) + `CameraQrScannerView` (preview + `ImageAnalysis` frame decode + runtime permission + torch + reticle + paste fallback) wired into the Connect page's "Scan QR or paste pairing URL" affordance; decoded code posts to the existing tested `pairFromText` seam. Paste/manual fallback preserved (camera-less devices route to paste dialog). JVM-testable `decodeArgb` seam: `QrDecoderTest` round-trips pairing URLs (https/Tailscale/unicode) + rejects non-QR/noise. Deps added: CameraX (core/camera2/lifecycle/view 1.4.1) + ZXing core 3.5.3; `CAMERA` permission + `camera.any` feature `required=false`. |
| 6.7 | Device UI: local “Forget this JKP device” (local grant clear only — **not** host revoke) | **SHIPPED in 0.6.0-rc2** (Settings copy + host-revoke tip) |
| 6.8 | Physical QA matrix: revoke, expired pair, offline reconnect, model switch, multi-device isolation | **BLOCKED — physical device session** |

**Depends on host JKP:** stable `/v1/pair/*`, grant revoke, session ownership (v1.12 track); freeze contract **v1.13.1**; host revoke UX **v1.13.1c**; host discovery **v1.13.2a** (`python -m jkp pair probe`, `GET /v1/pair/contract`, `GET /v1/client-errors`, capabilities advertises device grant). Before phone QA: `python -m jkp pair probe --url <host>`. Mirror: local forget ≠ host revoke; on 401 `invalid_device_grant` clear grant and re-pair.

### 0.7.0 — Operator quality (7.1–7.4 SHIPPED across 0.6.0-rc2..final; 7.5 P2 open)

| # | Work | Priority |
|---|---|---|
| 7.1 | Hang honesty: surface host approval waits (mirror Hermex PWA “Still working / approve or YOLO on host”) | **SHIPPED in 0.6.0-rc2** (`HangHonesty` + ChatViewModel stall watch) |
| 7.2 | Categorized errors aligned with JKP gateway categories (auth, quota, rate-limit, offline) | **SHIPPED in 0.6.0-rc2** (`ClientErrorCatalog` + ApiError routing) |
| 7.3 | Model preference parity with host session model | **SHIPPED in 0.6.0-rc4** (`resolveSessionModelSelection` + ChatViewModel seed) |
| 7.4 | Streaming resilience (reconnect, partial render) | **SHIPPED** — full auto-reconnect (`ConnectionSupervisor` + `ReconnectController`, v0.5.0) + transport honesty (rc3) + SSE error catalog (rc5) + `streamDropRecovery` pure helpers (host 13.9) + recovery offer UI (13.10) |
| 7.5 | Optional: share sheet / deep link polish | P2 |

### Later / explicit non-goals

| Item | Status |
|---|---|
| Background agent execution on phone | **Out of scope** |
| Push notifications as first-class agent channel | Deferred |
| Full iOS feature parity push in this fork | Deferred (upstream iOS remains reference) |
| KMP shared networking rewrite | Deferred (§7 ANDROID_PORT_PLAN) |

---

## 3. Relationship to JKP host

```
┌─────────────────────────┐         HTTPS / Tailscale / Tunnel
│  JKPHermex (Android)    │ ───────────────────────────────┐
│  control plane          │                                 │
└─────────────────────────┘                                 ▼
┌─────────────────────────┐         same-origin            ┌──────────────────┐
│  Browser /mobile PWA    │ ─────────────────────────────► │  JKP host        │
└─────────────────────────┘                                │  gateway + agent │
                                                           │  MCPs Synapse…   │
┌─────────────────────────┐                                └──────────────────┘
│  Telegram / Desktop     │ ───────────────────────────────────────┘
└─────────────────────────┘
```

| Concern | Host (JKP) | Phone (JKPHermex) |
|---|---|---|
| Pairing token issue | `python -m jkp pair` | Completes via `/v1/pair/complete` |
| Approvals / YOLO | Telegram `/yolo`, desktop YOLO | **Show tip only** — approve on host |
| Tools / Synapse | Host MCP | Not on phone |
| Model routing | Host config | Display + select if API allows |

When either side changes pairing or stream contracts, update **both** roadmaps (this file + JKP `2026-07-16-jkp-v1.13-roadmap.md`).

---

## 4. Verification gates

```powershell
cd E:\JKPHermex\android
.\gradlew.bat test
.\gradlew.bat assembleDebug
# Optional release:
# .\gradlew.bat assembleRelease
```

Live device:

1. Install debug/release APK.  
2. Reach host (Tailscale recommended).  
3. Pair or password login.  
4. List sessions → open → send → stream response.  
5. Airplane mode → offline UX → reconnect.

Host must be green:

```bat
cd /d E:\JKP\Jester-King-Prime-with-Hermes-Base-Fork
python -m jkp gate --no-selftest
```

---

## 5. Operator commands

```powershell
# Build
cd E:\JKPHermex\android
.\gradlew.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk

# Host pairing (laptop)
cd E:\JKP\Jester-King-Prime-with-Hermes-Base-Fork
python -m jkp pair
```

---

## 6. Success metrics

| Metric | Target |
|---|---|
| Unit tests | ≥ 547 green for the current 0.8.14 quality pass (was ≥ 255 at 0.5.0 baseline) |
| Install → first chat on physical phone | &lt; 10 minutes on known host |
| Pairing secret leakage | Never in logs, UI state, backups, or non-fragment URLs |
| False “agent crashed” while host waits on approval | 0 (honest status copy) |
| Play track | Internal/closed testing by end of 0.6.0 operator work |

---

## 7. Doc map

| Doc | Role |
|---|---|
| **This file** | What’s next (versions 0.6+) |
| `ANDROID_PORT_PLAN.md` | Historical phases 0–11 + architecture |
| `PLAY_STORE_LISTING.md` | Store copy for 0.6 |
| `PRIVACY.md` | Privacy policy URL target |
| `PROJECT_SPEC.md` | Product/API truth (upstream-shaped) |
| `PROJECT_INTENT.md` | Short intent |
| `AGENTS.md` | Agent working agreement |
| `CHANGELOG.md` | Release notes |

---

## 8. Revision log

| Date | Change |
|---|---|
| 2026-07-28 | Completed the connected-device accessibility/scroll pass: one true-end jump control, adjustable scrollbar semantics, shared 48 dp swatches/actions/rows, real isolated on-device Room v2→v3 migration, and final Gboard verification. Debug/release each run 547 tests with zero failures. |
| 2026-07-27 | Replaced the stale USB-reconnect screenshot gate with physical dark/light and 1.35x font-scale evidence; recorded the remaining 48 dp, swatch-label, TalkBack-adjustment, and stacked-jump remediation instead of treating the accessibility pass as complete. |
| 2026-07-27 | Reconciled roadmap with active 0.8.14 development state; recorded scrollbar/cold-start/navigation/composer/persistence hardening and the remaining physical-device/release gates. |
| 2026-07-21 | **6.6 camera QR scanner SHIPPED (operator-approved).** Live CameraX scanner: `QrDecoder` (ZXing local-only decode, JVM-testable `decodeArgb` seam) + `CameraQrScannerView` (preview + `ImageAnalysis` frame decode + runtime permission + torch + reticle + paste fallback) wired into the Connect page. Deps added: CameraX 1.4.1 (core/camera2/lifecycle/view) + ZXing core 3.5.3; `CAMERA` permission + `camera.any` feature `required=false`. `QrDecoderTest` round-trips pairing URLs + rejects non-QR/noise. Paste/manual fallback preserved. |
| 2026-07-21 | **6.6 status:** paste/manual fallback VERIFIED (PairingIntentParser 18 tests + PairUrlDialog + tested `pairFromText` seam); camera scanner BLOCKED pending operator approval to add CameraX + QR decoder (7.4 auto-reconnect shipped on this device — see HangHonesty.reconnectPolicy + ChatViewModel.maybeAttemptReconnectRecovery, 310 tests 0-fail) |
| 2026-07-18 | **0.6.0-rc6**: stream recovery offer UI (host excellence 13.10) |
| 2026-07-18 | **0.6.0-rc5**: SSE error catalog + classify free-text + streamDropRecovery pure helpers (13.9) |
| 2026-07-18 | Host excellence **13.9–13.10**: recovery helpers + dual-surface recovery UX |
| 2026-07-18 | **0.6.0-rc4**: session model preference (7.3) + full catalog lock (`invalid_api_key`); host excellence 13.8 |
| 2026-07-18 | **0.6.0-rc3**: stream transport honesty (`HangHonesty.transportFailureMessage`) |
| 2026-07-18 | **0.6.0-rc2**: hang honesty, ClientErrorCatalog, forget-device tip; Play/keystore marked operator-blocked; cross-link JKP master plan 2026-07-18 |
| 2026-07-16 | Initial PLAN_AND_ROADMAP: 0.5.0 baseline, 0.6.0 Play + Bearer pairing, 0.7 quality; cross-link JKP v1.13 |
| 2026-07-14 | Device evidence against JKP (recorded in ANDROID_PORT_PLAN §0) |
