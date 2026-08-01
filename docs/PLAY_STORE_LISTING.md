# Google Play Store Listing — JKPHermex

Copy-ready text for the Play Console. Recheck field lengths in the console
before submission.

## App name

```text
JKP Mobile
```

## Short description (80 characters maximum)

```text
The native mobile client for your self-hosted JKP or Hermes agent.
```

## Full description (4,000 characters maximum)

```text
JKP Mobile is a fast native Android client for a self-hosted JKP or Hermes
agent. Your laptop or server runs the agent; the phone provides a focused
control surface for conversations, sessions, and workspace tools.

Built for daily use:
 • Stream responses in a native chat interface
 • Navigate grouped sessions, Projects, Tasks, Skills, Memory, and Insights
 • Review cached conversations when the gateway is temporarily unavailable
 • Write local Notes and save reusable Prompts
 • Render Markdown, code blocks, lightweight math, tools, and reasoning clearly
 • Share text, images, and PDFs from other Android apps into the composer
 • Dictate with Android speech recognition
 • Browse workspace files and inspect Git status and diffs
 • Follow long chats with precise fast scrolling and jump-to-latest
 • Keep an active response stream alive while the app is backgrounded

Pair with a QR code or paste a gateway URL. JKP Mobile supports operator-run
gateways reachable over Wi-Fi, Tailscale, or another network route configured
by the operator. HTTPS is recommended; transport security depends on the URL
the operator chooses.

Privacy by construction:
 • No publisher-operated chat backend or JKP Mobile account service
 • No analytics, advertising, social-login, or crash-reporting SDK
 • Android cloud backup and device-transfer restore are disabled
 • Pairing secrets are encrypted with an Android Keystore key
 • QR camera frames are decoded locally and discarded
 • Voice input prefers Android's on-device recognizer when available; the
   system provider fallback may process audio over a network
 • Shared content is not sent until the operator submits it

Update checks can use the configured gateway and GitHub. Accepted update APKs
are handed to Android's standard package installer; JKP Mobile never silently
installs an update.

Permissions explained:
 • INTERNET — connect to your chosen gateway and check/download GitHub updates
 • POST_NOTIFICATIONS — report completion of a background response when enabled
 • RECORD_AUDIO — optional dictation through Android speech recognition
 • CAMERA — optional, local-only QR pairing scan
 • FOREGROUND_SERVICE / DATA_SYNC — keep an active response stream alive in background
 • REQUEST_INSTALL_PACKAGES — hand an accepted update APK to Android's installer

The full source and privacy policy are available at
https://github.com/JesterkingLord/JKPHermex. Read it, audit it, fork it, or
build it yourself under the MIT license.
```

## What's new in this release (500 characters maximum)

```text
v0.8.14 — Mobile quality and reliability
 • One consistent navigation drawer across the app
 • Local Notes and reusable Prompts
 • Denser, clearer composer controls and native Markdown rendering
 • More reliable session loading, offline cache, and streaming recovery
 • Precise draggable fast scrolling plus jump-to-latest
 • Improved keyboard layout, touch targets, themes, and narrow-phone support
 • Hardened backup policy and more accurate voice/privacy behavior
```

## Store assets

| Asset | State |
|---|---|
| Launcher mipmaps | Present for mdpi through xxxhdpi |
| Standalone Play icon (512x512) | Still required |
| Feature graphic (1024x500) | Still required |
| Final phone screenshots | Still required |

The mipmaps are installable launcher resources, not a substitute for the
standalone 512x512 Play Console icon.

## Categorization

| Field | Value |
|---|---|
| Category | Productivity |
| Target audience | Adults; this is a developer/operator tool |
| Contains ads | No |
| In-app purchases | No |
| Privacy policy URL | https://raw.githubusercontent.com/JesterkingLord/JKPHermex/master/PRIVACY.md |
| Source | https://github.com/JesterkingLord/JKPHermex |

## Contact

| Field | Value |
|---|---|
| Developer name | Farouk Saleh (JesterkingLord) |
| Email | Set in Play Console; not committed to Git |
| Website | https://github.com/JesterkingLord/JKPHermex |
