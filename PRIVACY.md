# Privacy Policy — JKP Mobile

**Last updated:** 2026-07-15
**Effective for:** JKP Mobile Android app, v0.4.0 and later (Hermex repo)

This is the privacy policy for **JKP Mobile**, the native Android client
in the [`JesterkingLord/JKPHermex`](https://github.com/JesterkingLord/JKPHermex)
repository. The short version is at the top; the long version follows.

---

## TL;DR

**JKP Mobile collects nothing.** The app does not have a backend service
operated by us. It does not include analytics SDKs, crash reporters,
advertising networks, or any third-party telemetry. The app only talks to
**the JKP / Hermes gateway you configure it to talk to** — typically a
self-hosted `hermes-webui` instance on your own machine or a server you
control.

If you find evidence to the contrary, please open a private security
advisory per [`SECURITY.md`](./SECURITY.md).

---

## 1. What the app stores on your device

| Data | Where it lives | Can be backed up? |
|---|---|---|
| **Pairing grant** (bearer token + device ID) | Android Keystore-wrapped EncryptedSharedPreferences (`hermex_secrets`) | **No** — explicitly excluded from cloud backup and device-transfer |
| **Server registry** (URL + nickname for each paired gateway) | SharedPreferences (`hermex_servers`) | **No** |
| **Preferences** (current model, reasoning effort, last-used session, theme) | SharedPreferences (`hermex_prefs`) | **No** |
| **Cached chat payloads** (text the assistant has produced, for offline review) | Room database (`hermex.db`) | **No** |
| **In-memory draft text** (the half-typed message in the composer) | Process memory | n/a — dies with the app process |

All of the above lives **on your device only.** None of it is sent to us
because we have nowhere to send it.

## 2. What the app sends over the network

The app makes HTTPS requests **only** to a host you selected during the
onboarding flow. That host is your self-hosted JKP / Hermes gateway.

The requests carry:

- **The pairing grant** as a `Bearer` token in the `Authorization`
  header. This is the same token your gateway issued via
  `python -m jkp pair` (or the QR code you scanned).
- **Your chat messages**, exactly as you typed them.
- **The model ID, reasoning effort, and any other parameters you picked
  in the composer**, exactly as you picked them.
- **Standard HTTP telemetry** (TLS handshake, IP of the destination you
  configured, request/response sizes). This is observable to your
  network operator and to whoever runs the gateway host — not to us.

The app does **not** include:

- Crash reporting SDKs (no Firebase Crashlytics, no Sentry, no Bugsnag).
- Analytics SDKs (no Google Analytics, no Amplitude, no Mixpanel).
- Advertising SDKs (no Google AdMob, no Facebook Audience).
- Push-notification services (no FCM, no OneSignal).
- Remote-config services (no Firebase Remote Config, no LaunchDarkly).
- Social-login SDKs (no Google Sign-In, no Facebook Login).
- Any other network endpoint not listed above.

## 3. What the app does NOT do

- We do not have a server. The publisher of this app does not see your
  chats, your pairing grant, your server URL, or any other app-level
  telemetry. If we wanted to, we couldn't.
- We do not log, monitor, or analyze how you use the app.
- We do not display advertising.
- We do not make in-app purchases.
- We do not collect location, contacts, microphone audio, photos, or any
  other sensor data on the phone. (A `RECORD_AUDIO` permission is
  declared for **future** voice input; it is **not** exercised by the
  current release.)

## 4. Third-party services

The app links to **GitHub** for the in-app update check (when a new
release is published, the app shows a banner with a link to the
release page on `github.com/JesterkingLord/JKPHermex`). This is a
read-only HTTP GET to `api.github.com`. The request includes the
current `versionName` as a query parameter so the API can return the
latest version. **No user-identifying information is sent** — no auth
token, no device ID, no IP that's not already in the TLS handshake.

The app does not embed any other third-party SDK, library, or service.

## 5. Children's privacy

JKP Mobile is a developer tool. It is not directed at children under
the age of 13, and we do not knowingly collect any information from
children. (See TL;DR: we do not collect information from anyone.)

## 6. Data retention

We have no data to retain. All data the app handles lives on your
device. Uninstalling the app deletes all of it.

## 7. Your rights

Because we have no server, the rights that typically apply (access,
deletion, portability) reduce to a single action: **uninstall the
app**. The app also provides an in-app **Sign out** action that
clears the pairing grant, server registry, and cached payloads from
your device without uninstalling.

## 8. Changes to this policy

If this policy changes, the change will be:

1. Committed to this repository with a new commit.
2. Listed in [`CHANGELOG.md`](./CHANGELOG.md) under the version that
   introduces the change.
3. Reflected in the in-app "About" screen with a notice on first
   launch after the update.

## 9. Contact

| Field | Value |
|---|---|
| Repository | https://github.com/JesterkingLord/JKPHermex |
| Security issues | Per [`SECURITY.md`](./SECURITY.md) — private disclosure |
| Publisher | Farouk Saleh (GitHub: `JesterkingLord`) |
| Email | (see Play Console listing for the public-support address) |

---

**This document is the canonical privacy policy for JKP Mobile.** It
is published in the source repository so the version in the Play
Console listing is always the same as the version in the code that
the user can audit.
