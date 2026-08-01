# Privacy Policy — JKP Mobile

**Last updated:** 2026-08-01

**Effective for:** JKP Mobile Android app, v0.8.14 and later (Hermex repo)

This policy describes the native Android client in the
[`JesterkingLord/JKPHermex`](https://github.com/JesterkingLord/JKPHermex)
repository.

## Summary

The publisher does not operate a JKP Mobile account service, chat backend,
analytics service, advertising network, or crash-reporting service. The app is
a client for a JKP / Hermes gateway chosen by the operator.

The app does handle private information on the phone and sends information to
services needed for features the operator uses:

- submitted chats and attachments go to the configured gateway;
- update checks can contact the configured gateway and GitHub;
- optional dictation uses Android speech recognition and can involve the
  device's default system provider when on-device recognition is unavailable.

Hermex does not add telemetry SDKs or send this information to a
publisher-operated analytics backend.

## 1. Data stored on the device

| Data | Storage | Android backup/device transfer |
|---|---|---|
| Pairing grants, device IDs, and session cookies | AES/GCM ciphertext in private SharedPreferences; the non-exportable key lives in Android Keystore | Disabled |
| Server registry and preferences | Private SharedPreferences | Disabled |
| Cached chat payloads | Room `hermex.db` | Disabled |
| Local Notes and Prompts | Room `hermex.db` | Disabled |
| Composer draft | Process memory | Not persisted |

Hermex explicitly disables Android backup in its application manifest and
excludes every supported app-data domain from both cloud-backup and
device-transfer rules. This includes database sidecars and future private
files, not only the filenames listed above.

The credential store does not use Jetpack Security's deprecated preferences
wrapper. Hermex owns a small AES/GCM wrapper: encryption keys are generated and
retained by Android Keystore, while only the IV and encrypted payload are
written to the private `hermex_secrets` SharedPreferences file.

## 2. Data sent to the configured gateway

When the operator uses the corresponding feature, the app can send the
following to the self-hosted JKP / Hermes gateway they selected:

- pairing or login credentials in the appropriate authenticated request;
- chat messages and attachments the operator submits;
- model, reasoning-effort, and composer options selected for a request;
- session, workspace, Git, project, task, skill, memory, and insight requests
  exposed by the configured gateway;
- normal network metadata such as source IP, destination, timing, and transfer
  size.

Shared text, images, and PDFs remain in the composer until the operator sends
them. A local Prompt can be inserted into the composer; it reaches the gateway
only if the resulting message is submitted.

The gateway operator controls that server and is responsible for its models,
tools, logs, retention, and downstream providers. HTTPS is recommended. If the
operator deliberately configures an HTTP endpoint, transport is not encrypted.

## 3. Voice input and camera

### Microphone

Voice input requests `RECORD_AUDIO` only when the operator taps the microphone
control. On Android 12/API 31 and later, Hermex prefers Android's dedicated
on-device recognizer when the device reports it available. Otherwise it can
use Android's default speech-recognition provider, which may process audio over
a network according to the provider and device settings.

Hermex does not retain microphone recordings. Recognized text is inserted into
the composer and is not sent until the operator submits it.

### Camera

Camera access is optional and used only for QR pairing. Camera frames are
decoded locally with ZXing, discarded immediately, and are not recorded or
uploaded as frames. The decoded pairing text is handled by the same local
pairing parser as pasted input.

## 4. Update services and other network destinations

The update checker first asks the configured gateway for release information
when that route is available. If needed, it falls back to GitHub's API and Git
HTTP endpoints for the JKPHermex repository. These requests carry ordinary
network metadata and a JKPHermex user-agent, but no pairing grant or chat
content is sent to GitHub.

If the operator accepts an update, the APK is downloaded from the published
GitHub release asset into private cache and handed to Android's package
installer. Android always presents its normal installation controls.

Opening the privacy, source, or release links launches the device browser and
is then subject to the browser's and destination site's policies.

The app contains no Firebase Cloud Messaging, advertising, social-login,
remote-config, analytics, or third-party crash-reporting SDK.

## 5. Permissions

| Permission | Purpose |
|---|---|
| `INTERNET` | Connect to the chosen gateway and check/download GitHub updates |
| `POST_NOTIFICATIONS` | Optionally report completion of a background response |
| `RECORD_AUDIO` | Optional dictation through Android speech recognition |
| `CAMERA` | Optional local-only QR pairing scan |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC` | Keep an active response stream alive while backgrounded |
| `REQUEST_INSTALL_PACKAGES` | Hand an accepted update APK to Android's package installer |

The app does not request location, contacts, phone, SMS, call-log, or
advertising-ID permissions.

## 6. Retention and deletion

The publisher has no JKP Mobile chat database to retain or erase. On-device
retention is controlled locally:

- **Sign out** makes a best-effort logout request, clears the active host's
  pairing grant, device ID, and session cookies, removes the active server
  selection, and returns the app to its unconfigured state. It retains the
  server registry, preferences, Room cache, local Notes, and local Prompts.
- **Forget server** removes that server's registry entry and host-scoped
  authorization. It does not erase unrelated local content or the Room
  database.
- Individual Notes, Prompts, and supported server content can be removed with
  their existing in-app actions.
- **Uninstalling the app** is the current complete local-data deletion path.
  Because Android backup and device-transfer restore are disabled, Hermex does
  not intentionally restore that private app data after reinstallation.

Deletion or retention on the configured gateway must be managed with that
gateway and its operator.

## 7. Children's privacy

JKP Mobile is a developer/operator tool and is not directed at children under
13. The publisher does not knowingly collect children's information through a
publisher-operated JKP Mobile service.

## 8. Changes to this policy

Policy changes are committed to this repository and recorded in
[`CHANGELOG.md`](./CHANGELOG.md). The repository version is the canonical
auditable policy used by the app and store listing.

## 9. Contact

| Field | Value |
|---|---|
| Repository | https://github.com/JesterkingLord/JKPHermex |
| Security issues | Follow [`SECURITY.md`](./SECURITY.md) for private disclosure |
| Publisher | Farouk Saleh (GitHub: `JesterkingLord`) |
| Email | See the Play Console listing for the public support address |
