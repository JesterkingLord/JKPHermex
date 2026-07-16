# JKPHermex — Plan & Roadmap

**Product:** Native **Android** control surface for a self-hosted **JKP / Hermes** agent  
**Repo:** `E:\JKPHermex` · GitHub: `JesterkingLord/JKPHermex`  
**Current Android version:** **`0.5.0`** (`versionCode` 6)  
**Last roadmap refresh:** `2026-07-16`  
**Authoritative for “what’s next” on the phone.** Port history: [`ANDROID_PORT_PLAN.md`](ANDROID_PORT_PLAN.md).  
**Host roadmap (laptop agent):** [`E:\JKP\Jester-King-Prime-with-Hermes-Base-Fork\docs\PLAN_AND_ROADMAP.md`](file:///E:/JKP/Jester-King-Prime-with-Hermes-Base-Fork/docs/PLAN_AND_ROADMAP.md) · v1.13 plan: [`docs/superpowers/plans/2026-07-16-jkp-v1.13-roadmap.md`](file:///E:/JKP/Jester-King-Prime-with-Hermes-Base-Fork/docs/superpowers/plans/2026-07-16-jkp-v1.13-roadmap.md)

---

## 0. Mental model

| Layer | Who | Where |
|---|---|---|
| **Execution plane** | JKP agent + gateway + MCPs | Operator laptop (`Jester-King-Prime-with-Hermes-Base-Fork`) |
| **Control plane (native)** | **JKPHermex** | This repo (`android/`) |
| **Control plane (browser)** | Hermex PWA | Host `/mobile` (same-origin pairing) — not this APK |

The phone **does not** run the agent. It authenticates, lists/opens sessions, streams chat, and recovers offline. Inventing API paths or JSON shapes is forbidden — verify against the live JKP host or pinned contracts.

---

## 1. Current state (2026-07-16)

### Shipped

| Version | Highlights |
|---|---|
| **0.5.0** | G-lite math in chat markdown; auto-update check → GitHub releases Snackbar; 255 unit tests green; debug + signed release APKs verified |
| **0.4.x** | Release signing / Play prep scaffolding; proguard; manifest hardening |
| **0.3.0** | Physical phone ↔ live JKP: sessions, stream, MiniMax response (2026-07-14 evidence) |
| Phases 0–3, 5–9 | Setup, networking/auth, onboarding, session list, composer, workspace/git, server panels, settings, platform hooks — see ANDROID_PORT_PLAN |

### Open from ANDROID_PORT_PLAN (reconcile)

| Phase | Status note |
|---|---|
| **4 – Chat + SSE** | Functionally shipped on device (0.3+ evidence); keep checkbox debt cleaned as polish only |
| **10 – Polish + release prep** | In progress via 0.4–0.6 track (Play listing, icons, keystore) |
| **11 – Native JKP pairing completion** | **Primary product gap** — grant as Bearer on API traffic; QR optional |

### Boundaries (locked)

- Kotlin + Jetpack Compose only for shipping Android UI (no RN/Flutter).  
- No new third-party deps without approval (`libs.versions.toml`).  
- Tolerant decoding for all upstream JSON.  
- iOS tree is **reference / upstream parity** unless explicitly scheduled.  
- Do not commit secrets, release keystore, or phone screenshots with paths/session metadata.

---

## 2. Roadmap by version

### 0.5.x — STABLE baseline (now)

- Keep 255+ unit tests green on every change.  
- Auto-update check + About / privacy links stay honest.  
- Document install path for operator phone.

### 0.6.0 — Play Store operator upload + pairing Bearer (NEXT)

| # | Work | Priority |
|---|---|---|
| 6.1 | Google Play Developer account + real release keystore (1Password) | P0 operator |
| 6.2 | Launcher mipmaps, feature graphic, screenshots | P0 |
| 6.3 | Store listing from `docs/PLAY_STORE_LISTING.md` + `PRIVACY.md` URL | P0 |
| 6.4 | **`ApiClient` prefers JKP pairing grant** → `Authorization: Bearer <grant>` (no log/URL leak). **Host freeze:** JKP `docs/PAIRING_CONTRACT.md` + `python -m jkp pair contract` / `GET /v1/pair/contract` | **P0 product** |
| 6.5 | Password/cookie fallback for non-JKP hermes-webui servers | P0 |
| 6.6 | Camera QR for `PairingIntentParser` (paste/manual fallback remains) | P1 |
| 6.7 | Device UI: linked device name + local “Forget this JKP device” (local grant clear only — **not** host revoke) | P1 |
| 6.8 | Physical QA matrix: revoke, expired pair, offline reconnect, model switch, multi-device isolation | P0 |

**Depends on host JKP:** stable `/v1/pair/*`, grant revoke, session ownership (v1.12 track); freeze contract **v1.13.1**; host revoke UX **v1.13.1c** (`python -m jkp pair list` / `pair revoke`, contract `revoke_ux`, PWA “Forget this phone link”). Mirror: local forget ≠ host revoke; on 401 `invalid_device_grant` clear grant and re-pair.

### 0.7.0 — Operator quality (after 0.6)

| # | Work | Priority |
|---|---|---|
| 7.1 | Hang honesty: surface host approval waits (mirror Hermex PWA “Still working / approve or YOLO on host”) | P0 |
| 7.2 | Categorized errors aligned with JKP gateway categories (auth, quota, rate-limit, offline) | P1 |
| 7.3 | Model preference parity with host session model | P1 |
| 7.4 | Streaming resilience (reconnect, partial render) | P1 |
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
| Unit tests | ≥ 255 green (0.5.0 baseline) |
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
| 2026-07-16 | Initial PLAN_AND_ROADMAP: 0.5.0 baseline, 0.6.0 Play + Bearer pairing, 0.7 quality; cross-link JKP v1.13 |
| 2026-07-14 | Device evidence against JKP (recorded in ANDROID_PORT_PLAN §0) |
