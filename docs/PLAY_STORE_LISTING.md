# Google Play Store Listing — JKPHermex

> **Copy-paste-ready text for the Play Console submission form.**
> All fields are sized to the Play Console's limits (short description: 80
> chars, full description: 4000 chars). Test for length before submitting —
> characters, not bytes.

---

## App name
```
JKP Mobile
```

## Short description (≤80 chars)
```
The JKP / Hermes agent, on your phone. Talk to your self-hosted LLM gateway.
```
*(68 chars)*

## Full description (≤4000 chars)

```
JKP Mobile is the native Android client for a self-hosted JKP / Hermes
agent. The agent does not run on the phone — your laptop does. The phone
is a fast, native control plane: send chat messages, pick a reasoning
effort, watch the streaming response, share text or images from any app
straight into a new chat.

Designed for one thing: getting out of the way. No accounts, no telemetry,
no ads, no in-app purchases. Pair your phone to your gateway with a
single QR scan or a paste of the pairing URL, and every chat thereafter
is end-to-end encrypted (HTTPS) between this device and your server.

Why a native app instead of a website?
 • Background notifications when a long-running response finishes
 • Share-target: text, images, and PDFs from any app land in the composer
 • One-tap reasoning-effort selector that mirrors the desktop CLI's
   `/reasoning` command
 • Persistent, hardware-backed pairing grant stored in EncryptedShared
   Preferences with the Android Keystore
 • Homescreen widget for a glanceable "is the agent busy?" pulse

What it talks to:
 • A self-hosted hermes-webui / JKP gateway on your own machine
 • Reachable over Wi-Fi, Tailscale, or any network the phone can route to
 • The phone never talks to anyone except the gateway YOU paired it with

What's not in the box (by design):
 • No built-in LLM. The phone is a client; the model lives on your
   laptop / server.
 • No cloud sync, no analytics, no crash reporting. If you want to file
   a bug, file it on GitHub.
 • No subscriptions, no paywall, no premium tier. This is a tool.

The full source is at https://github.com/JesterkingLord/JKPHermex under
the MIT license. Read it, fork it, build it yourself, audit it.

Permissions explained:
 • INTERNET — talk to your gateway
 • POST_NOTIFICATIONS — tell you when a long response finishes
 • RECORD_AUDIO — future voice input (not yet wired)
 • FOREGROUND_SERVICE — keep the active response stream open if the app
   is backgrounded

Privacy: see the privacy policy at the URL listed below. The short
version: we collect nothing. Your chats never leave your gateway.
```
*(~1,650 chars — well under the 4,000 limit; room for a future "What's
new in this release" block.)*

---

## What's new in this release (Release notes, ≤500 chars)

```
v0.5.0 — Reasoning controls + Play Store prep
 • Reasoning-effort selector in the chat composer — pick none, minimal,
   low, medium, high, or xhigh for the next message.
 • Reasoning on/off pill to show or hide the assistant's thinking block.
 • Server-clamp banner: if the gateway downgrades your requested
   effort, you'll see a notice.
 • 230/230 unit tests green, 1.7 MB signed release APK.
 • Background-update check — the app now checks GitHub for new
   releases and prompts you to install.
```

---

## App icon

| Asset | Required? | Where it lives |
|---|---|---|
| App icon (512×512) | Yes | `android/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png` — **NOT YET GENERATED** |
| Feature graphic (1024×500) | Yes | **NOT YET GENERATED** — needed for the Play listing |
| Phone screenshots (min 2, max 8) | Yes | **NOT YET CAPTURED** |

The mipmap launchers don't exist in the repo today. We rely on the
default Android Studio icon, which is not a "JKP Mobile" branded asset.
**This is the operator-side blocker for the actual Play Console upload.**

---

## Categorization

| Field | Value |
|---|---|
| Category | Productivity |
| Content rating | Everyone (no user-generated content, no ads, no in-app purchases) |
| Target audience | 18+ (developer tool, the gateway operator is assumed to be a developer) |
| Contains ads | No |
| In-app purchases | No |
| Data safety | See https://github.com/JesterkingLord/JKPHermex/blob/master/SECURITY.md |
| Privacy policy URL | https://raw.githubusercontent.com/JesterkingLord/JKPHermex/master/PRIVACY.md |

---

## Contact

| Field | Value |
|---|---|
| Developer name | Farouk Saleh (JesterkingLord) |
| Email | (set in Play Console — not committed to git) |
| Website | https://github.com/JesterkingLord/JKPHermex |
