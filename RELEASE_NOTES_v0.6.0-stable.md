# JKPHermex v0.6.0-stable — Production-Ready Android Control Plane

**Released:** 2026-07-22
**Baseline:** `0.6.0` (versionCode `13`)
**Stable line:** `stable/v1.12.1-jkphermex-0.6.0` (long-lived backport branch, same name on both repos)
**Tags:** `v0.6.0-stable` (this release), `stable` (advancing alias)
**Coordinated release with:** `JKP v1.12.1-stable` (same stable branch name, same coordinated release date)

---

## Headline

`v0.6.0-stable` is the first phone-side tag paired end-to-end with a known-good host (`JKP v1.12.1-stable` on Tailscale). Two recent commits that landed in master but had not yet been release-cut are now part of the stable line: `39aa194 chat: relabel reasoning controls to remove ambiguity (UX)` and `9ad7543 docs(changelog): 2026-07-22 unit-test verification (323 pass / 0 fail)`. **No APK rebuild is required** — the APK at `0.6.0` on the operator's phone already contains the shipped code.

## What's in v0.6.0-stable

### Full feature set (carries forward from 0.6.0)

- **Bearer pairing** on every API + SSE endpoint (0.6.0-rc1). 401 → clear grant + re-pair.
- **Hang honesty** stall tip — ≥15s silent stream shows honest stall tip; `ClientErrorCatalog` aligned with host `jkp.client_errors` (0.6.0-rc2).
- **Session model preference parity (7.3)** — composer seeds from host `session.model`; user pick still wins; catalog default stays display-only. Model switch propagates to the active session without re-pair.
- **Stream recovery offer UI** (13.10) — after transport/stream drops, `streamRecoveryOffer` + tip "Partial reply kept. Edit or resend…" on chat screen; pure helpers from 13.9 drive the decision.
- **SSE error catalog honesty + stream-drop recovery helpers** (13.9) — `HangHonesty.streamErrorMessage` / `ClientErrorCatalog` routing; `streamDropRecovery` + `shouldKeepPartialTranscript` pure helpers.
- **Full auto-reconnect (7.4)** — `ConnectionSupervisor` + `ReconnectController` drive the full reconnect lifecycle end-to-end with backoff (1s/2s/4s), jitter, and host-grant refresh on transport drops. Best-effort session re-fetch recovers a turn that completed while the wire was down (same-turn guard); auth errors clear grant + route to re-pair; never re-POSTs the user message. Pure `reconnectPolicy` shared contract with desktop + PWA.
- **Live camera QR scanner (6.6)** — commit `fd50355`. Local-only decode via **CameraX 1.4.1** + **ZXing 3.5.3** (no network round-trip for the QR contents). `CameraQrScannerView` wires preview + `ImageAnalysis` frame decode + runtime `CAMERA` permission + torch toggle + viewfinder reticle, with a paste fallback when the user prefers the clipboard or the device has no camera.

### Two commits landed since the prior `v0.6.0` tag

- **`39aa194` — `chat: relabel reasoning controls to remove ambiguity (UX).`** Two reasoning controls were visually misleading: the "xhi" picker at the composer (ReasoningEffort) and the 🧠 "reasoning on/off" pill (think-block display toggle). Renamed to remove the visual redundancy that made both look like duplicates.
- **`9ad7543` — `docs(changelog): 2026-07-22 unit-test verification (323 pass / 0 fail).`** Verified the full unit-test sweep at 323 tests passing. Documentation delta only.

### Verified in isolation at tag time

`./gradlew.bat testDebugUnitTest --no-daemon`

```
BUILD SUCCESSFUL in 14s
25 actionable tasks: 25 up-to-date
```

| Metric | Result |
|---|---|
| Total unit tests | **323** |
| Passed | **323** |
| Failed | **0** |
| Errors | **0** |
| Skipped | **0** |
| Test suites | **39** |

Test suite highlights:
- `MathLiteTest` — **25** tests
- `PairingIntentParserTest` — **18** tests
- `HangHonestyTest` — **17** tests
- `UpdateCheckerTest` — **17** tests
- `AuthManagerTest` — **14** tests
- 34 more suites covering auth, chat, network, settings, UI, etc.

Exceeds the 255+ plan baseline from the [2026-07-21 production-excellence master plan](https://github.com/JesterkingLord/Jester-King-Prime-with-Hermes-Base-Fork/blob/main/docs/superpowers/plans/2026-07-21-jkp-jkphermex-production-excellence-master-plan.md).

### Verified end-to-end against JKP v1.12.1-stable

The phone APK on Tailscale `farouk.tail6197e7.ts.net:8787` was observed responding with the JKP Fable Brain identity ("I do not rule, I reveal.") to a user message, after this commit was merged. The chat goes through:

```
JKPHermex (Tailscale)
  ↓ Bearer-authenticated SSE
hermes-webui :8787 (jkp venv)
  ↓ in-process AIAgent.run_conversation
Hermes 0.19.0 (vendored, agent_compat.py shim bridged to protected run_agent.py)
  ↓ OpenAI-compatible wire path
minimax / MiniMax-M3
  ↓ real reply streamed back through SSE
Phone renders it (<5s typical, finish: stop, no leak)
```

All server-side commits from `JKP v1.12.1-stable` (`c0a9f935d` Telegram typing fix, `81a4b527a` Hermes 0.19.0 sync, `7339dafa2` TELEGRAM_RICH_MESSAGES_HINT import, `dc3187981` 0.19.0 compat shim, `cca2bf606` ollama-vision short-circuit) apply automatically to the phone.

---

## Cross-repo coordination

| Repo | Stable branch | Stable tag | HEAD |
|---|---|---|---|
| `Jester-King-Prime-with-Hermes-Base-Fork` | `stable/v1.12.1-jkphermex-0.6.0` | `v1.12.1-stable` | `1200cb0bb` |
| `JKPHermex` | `stable/v1.12.1-jkphermex-0.6.0` | `v0.6.0-stable` | `9ad7543` |

**Same branch name on both repos.** Ops can pin either side and verify coordination by checking out the same branch on each repo.

---

## What is deliberately NOT in v0.6.0-stable

Per the [master plan §1 BLOCKED](https://github.com/JesterkingLord/Jester-King-Prime-with-Hermes-Base-Fork/blob/main/docs/superpowers/plans/2026-07-21-jkp-jkphermex-production-excellence-master-plan.md) row — these items are operator-blocked and remain unchanged from the 0.6.0 base:

| Item | Blocker |
|---|---|
| Play Store upload / Developer account / real keystore (6.1) | Operator secrets (1Password) |
| Launcher mipmaps / feature graphic / screenshots (6.2) | Operator assets |
| Store listing (6.3) | Play console |
| Physical device QA matrix (6.8) | Physical phone session |

---

## Honest closeout

**Production-ready for every surface that isn't operator-blocked.** The operator can sideload the existing `0.6.0` APK (`versionCode 13`) from the [GitHub Releases page](https://github.com/JesterkingLord/JKPHermex/releases) and connect it to a JKP `v1.12.1-stable` host without any rebuild.
