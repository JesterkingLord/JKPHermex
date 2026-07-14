# JKPHermex

> **Native Android client for a self-hosted JKP / Hermes agent server.**
> Port of the upstream [`super3/hermex`](https://github.com/super3/hermex) iOS app to
> Kotlin + Jetpack Compose. The phone is the **control plane**; the laptop runs the
> agent, the phone sends chat messages and watches streaming responses over HTTPS + SSE.

This repository is a **fork-of-fork**:
- **Upstream:** [`super3/hermex`](https://github.com/super3/hermex) — production iOS SwiftUI app for [`nesquena/hermes-webui`](https://github.com/nesquena/hermes-webui)
- **Upstream Android branch:** [`claude/android-port-plan-lt7p00`](https://github.com/super3/hermex/tree/claude/android-port-plan-lt7p00) — the Android Kotlin/Compose port
- **This fork:** `JesterkingLord/JKPHermex` — the upstream Android branch, re-hosted as a standalone repo so we can ship a `JesterkingLord`-owned APK that talks to a self-hosted `hermes-webui` server (or any compatible JKP/Hermes backend) on the operator's laptop.

The agent does **not** run on the phone. The phone is a client. See
[`docs/ANDROID_PORT_PLAN.md`](docs/ANDROID_PORT_PLAN.md) and the upstream
[`PROJECT_SPEC.md`](https://github.com/super3/hermex/blob/master/PROJECT_SPEC.md)
for the full architecture.

---

## Quick start

### 1. Install the toolchain (one-time, ~15 min)

See [`BUILD.md`](BUILD.md) for the full instructions, or just run the one-shot installer:

```powershell
# In an elevated PowerShell (or as your normal user if winget is on PATH):
powershell -ExecutionPolicy Bypass -File scripts\install-android-toolchain.ps1
```

This installs:
- **Microsoft OpenJDK 17** (via `winget`)
- **Android SDK** command-line tools, then `platforms;android-35`, `build-tools;35.0.0`, `platform-tools`
- Sets `JAVA_HOME` and `ANDROID_HOME` for the current user

### 2. Build the debug APK

```bash
cd android
./gradlew assembleDebug
```

Output: `android/app/build/outputs/apk/debug/app-debug.apk` (~10-20 MB).

### 3. Install on your phone

With USB debugging enabled on the phone (Settings → Developer options → USB debugging),
plug it in and:

```bash
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

### 4. Run a server the app can talk to

The app expects an HTTPS endpoint with:
- `GET  /health`
- `POST /api/auth/login` (body: `{"password": "..."}` → cookie)
- `POST /api/chat/start` → `{"stream_id": "..."}`
- `GET  /api/chat/stream?stream_id=...` (SSE event stream)

The canonical match is [`nesquena/hermes-webui`](https://github.com/nesquena/hermes-webui).
Run it on the laptop, expose it via [Cloudflare Tunnel](https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/)
or [Tailscale](https://tailscale.com/download), and enter the URL + `HERMES_WEBUI_PASSWORD`
in the app's onboarding screen.

For an in-LAN-only setup (no public exposure), use Tailscale on both ends and an
HTTP URL — the app already permits cleartext to private LAN/VPN ranges
(`HermesAndroid cleartext config`).

---

## Repository layout

```
JKPHermex/
├── android/                    # Self-contained Gradle project (the buildable app)
│   ├── app/                    # :app module (Kotlin sources, manifest, resources)
│   ├── gradle/
│   │   └── libs.versions.toml  # Locked dependency list — do not edit without approval
│   ├── build.gradle.kts
│   ├── settings.gradle.kts
│   ├── gradlew / gradlew.bat
│   ├── gradle.properties
│   └── AGENTS.md               # Android-specific working agreement
├── docs/
│   ├── ANDROID_PORT_PLAN.md    # 20 KB upstream port plan (10 phases, scoped)
│   └── ...                     # cross-platform docs
├── ios/                        # Upstream iOS app (kept for reference; not built here)
├── shared/
│   └── fixtures/               # JSON contract fixtures shared with iOS test suite
├── scripts/
│   └── install-android-toolchain.ps1   # one-shot JDK 17 + Android SDK installer
├── README.md                   # this file
├── BUILD.md                    # detailed build instructions
├── AGENTS.md                   # platform-neutral working agreement (from upstream)
├── PROJECT_INTENT.md           # product intent (from upstream)
└── LICENSE                     # MIT (from upstream)
```

---

## Hard rules (from upstream `AGENTS.md`)

1. **Never invent API endpoints or JSON shapes.** The wire format is the source of truth.
   Verify in this precedence order: (a) `curl` your own running server — final arbiter;
   (b) the official API docs; (c) the pinned upstream `api/routes.py` if vendored.
2. **No new third-party dependencies** beyond the locked list in
   `android/gradle/libs.versions.toml` without approval.
3. **Tolerant decoding:** every `@Serializable` model uses nullable, defaulted fields
   decoded through `Json { ignoreUnknownKeys = true }`. Never crash on unknown JSON.
4. **No destructive commands** without explicit operator approval.
5. **Don't commit broken builds.** Run `./gradlew build` and let it pass before pushing.

---

## Status

| Phase | Upstream branch | Status |
|---|---|---|
| Phase 0: scaffold Android Gradle project | `claude/android-port-plan-lt7p00` | ✅ done |
| Phases 1-4: networking, auth, onboarding, sessions, chat with SSE | `claude/android-port-plan-lt7p00` | ✅ done |
| Phases 5-10: composer, workspace/git, panels, settings, platform, polish | `claude/android-port-plan-lt7p00` | 🟡 partial — see commits |
| Phase 11: limited usage analytics | `claude/android-port-plan-lt7p00` | ⏳ planned |
| Phase 12: polish (icons, haptics, voice, notifications) | `claude/android-port-plan-lt7p00` | ⏳ planned |
| Phase 13: Play Store / sideload release | this fork | 🟡 physical-phone sideload works; Play Console/listing remains |
| JKP native pairing | this fork (`0.3.0`) | 🟡 URL pairing + secret storage shipped; Bearer auto-auth and camera scan next |

### Real-device status (2026-07-14)

The Android app is installed on the operator's phone and connected to JKP. It lists
real JKP sessions and completed a live MiniMax M3 model turn. This was a physical-device
check, not an emulator or mobile-browser check. The local Android debug/release
unit-test task also passes. Sideload testing is therefore no longer blocked by the
toolchain or server; the remaining release blocker is Play Console/store setup and a
final application ID decision.

This fork now includes JKP-specific Android `0.3.0` pairing work through commit
`98e5f74`, on top of the upstream Android branch.

---

## License

MIT — see [`LICENSE`](LICENSE). Same license as the upstream project.
