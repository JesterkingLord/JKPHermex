# Android Backup, Speech Privacy, and Release-Truth Hardening Design

**Status:** Design direction approved by the operator on 2026-07-28; written
specification awaiting review
**Date:** 2026-07-28
**Scope:** Android 0.8.14 security/privacy correctness slice
**Evidence device:** OPPO CPH2343, Android 13/API 33

## Goal

Make the installed Android app match its privacy promises. Hermex must not
participate in Android cloud backup or device-to-device restore, voice input
must prefer truly on-device recognition when Android provides it without
misrepresenting the fallback, permission state must reflect the permission
already granted by the operator, and public release copy must describe the
shipping app rather than an older release.

This is a hardening slice, not a data-erasure redesign. It must preserve the
operator's 37 conversations and all local notes, prompts, preferences, and
pairings during implementation and verification.

## Confirmed defects and evidence

1. The installed debug package reports `ALLOW_BACKUP` in `dumpsys package`.
   `AndroidManifest.xml` does not set `android:allowBackup`, whose default is
   enabled.
2. Both backup-rule files exclude `databases/hermex.db` using `domain="file"`.
   Android databases belong to the `database` domain, so the rule does not
   express the intended Room exclusion. The narrow rules also leave future
   private files, databases, and preferences eligible.
3. App-private data currently includes `hermex_secrets.xml`,
   `hermex_servers.xml`, `hermex_prefs.xml`, the Room database plus its WAL/SHM
   files, cached chat payloads, local notes, and local prompts.
4. `VoiceInput.kt` calls `SpeechRecognizer.createSpeechRecognizer`, Android's
   default recognition service, but describes the feature as on-device.
   Android documents that the default recognizer may stream audio to remote
   servers; `createOnDeviceSpeechRecognizer` is a separate API on API 31+.
5. Voice permission state starts as `false` on every composition instead of
   checking the existing runtime grant, so a previously authorized mic press
   can unnecessarily re-enter the permission-launch path.
6. The real Room migration harness deletes its uniquely named test database
   but leaves `cache/hermex-migration-v2-v3-test.db.lck` behind.
7. `PRIVACY.md` and `docs/PLAY_STORE_LISTING.md` describe an older app. They
   incorrectly call the secret store `EncryptedSharedPreferences`, call voice
   input unused, omit camera and local Notes/Prompts, claim sign-out clears
   data it retains, claim the phone only contacts the gateway, and list stale
   version, test, APK, and asset state.

Android's backup domain contract is documented at
<https://developer.android.com/identity/data/autobackup>. Android's speech
recognizer behavior and on-device APIs are documented at
<https://developer.android.com/reference/android/speech/SpeechRecognizer>.

## Constraints

- Do not add dependencies, endpoints, JSON models, database migrations, or
  persistence schemas.
- Do not clear app data, uninstall the app, replace the production Room
  database, remove a pairing, send a chat message, or mutate server state.
- Do not change the meaning of Sign out or Forget server in this slice. Their
  current retention behavior will be documented accurately; a future
  `Erase local data` feature requires a separate design with confirmation UI.
- Keep the current camera, updater, share-target, Notes, Prompts, and
  notification features working.
- Do not use TalkBack for this verification. It is disabled at the operator's
  request and is unrelated to this slice.
- Keep screenshots, UI dumps, and device data out of Git.
- Every behavior change receives a failing test before production code.

## Approved architecture

### 1. Backup denial is explicit and layered

`AndroidManifest.xml` sets `android:allowBackup="false"` on the application.
This is the primary product policy: no Hermex app-private data must be
restored through Android cloud backup or device-to-device transfer.

The legacy `backup_rules.xml` and Android 12+
`data_extraction_rules.xml` remain attached as defense-in-depth and exclude
`path="."` for every app-data domain supported by the rule format:

- credential-protected `root`, `file`, `database`, and `sharedpref`;
- `external` app-specific storage;
- device-protected `device_root`, `device_file`, `device_database`, and
  `device_sharedpref`.

For `data_extraction_rules.xml`, the same complete exclusion set appears in
both `cloud-backup` and `device-transfer`. The rule comments state the actual
policy and do not claim that a narrow filename list covers all data. Whole
domains are excluded so Room sidecars and future private files are protected
without relying on filenames.

The two layers are intentional. `allowBackup=false` provides the clear
manifest-level denial; complete resource rules keep the policy explicit to
backup tooling and defend against platform/vendor differences in restore
handling.

### 2. Speech recognition is selected by a small testable policy

Voice input gains a pure selection unit with three outcomes:

- `ON_DEVICE`: API level is at least 31 and
  `SpeechRecognizer.isOnDeviceRecognitionAvailable(context)` is true;
- `SYSTEM_DEFAULT`: on-device recognition is unavailable but
  `SpeechRecognizer.isRecognitionAvailable(context)` is true;
- `UNAVAILABLE`: neither usable path exists.

The order is fixed: prefer on-device, then fall back to the system default,
then hide/disable voice input through the existing unavailable behavior. The
factory creates `createOnDeviceSpeechRecognizer` only for `ON_DEVICE` and
`createSpeechRecognizer` only for `SYSTEM_DEFAULT`. If the selected factory
throws during creation, the controller becomes unavailable rather than
crashing or silently changing recognition mode mid-request.

This policy does not promise that the fallback is offline. Source comments,
privacy copy, and store permission copy call it Android system speech
recognition and disclose that the selected system provider may process audio
over a network. Hermex itself does not retain microphone recordings.

The controller initializes `hasPermission` from
`ContextCompat.checkSelfPermission(RECORD_AUDIO)` and continues updating it
from the existing permission result callback. A mic press starts recognition
directly when permission is already granted and launches the permission
request only when it is not.

Recognizer lifecycle remains owned by the composable: one remembered
recognizer is destroyed on disposal, listening stops on user action or terminal
callback, and recognized text is appended to the composer without being sent
until the operator submits the message.

### 3. Migration verification cleans up only its own artifacts

`MigrationInstrumentation` continues to refuse the production name
`hermex.db` and uses only `hermex-migration-v2-v3-test.db`. Cleanup moves to a
single `finally` path that closes the Room instance, deletes the named fixture
database, and deletes only the exact cache lock
`hermex-migration-v2-v3-test.db.lck` after validating that the file is directly
under the target app's cache directory.

Cleanup failure makes the instrumentation result fail with a useful message;
it must not report PASS while its fixture remains. No wildcard, recursive, or
production-database deletion is permitted.

### 4. Privacy and release documents become code-matched contracts

`PRIVACY.md` is updated for Android 0.8.14 and states:

- the publisher operates no Hermex analytics, advertising, crash-reporting,
  account, or chat backend;
- configured gateway operators receive submitted chats, attachments, selected
  prompts, and normal network metadata;
- app-private storage contains encrypted credential ciphertext, the server
  registry, preferences, cached chat payloads, local Notes, and local Prompts;
- secrets use AES/GCM with a non-exportable Android Keystore key and private
  SharedPreferences ciphertext, not Jetpack
  `EncryptedSharedPreferences`;
- Android backup and device-transfer restore are disabled for app-private data;
- camera frames are used only for local QR decoding and are not retained or
  uploaded;
- voice input requires microphone permission, retains no recording, prefers
  Android's on-device recognizer when available, and otherwise may use a
  system recognition provider that processes audio over the network;
- update checks can contact the configured gateway and GitHub services, and
  an accepted APK download comes from the published GitHub release asset;
- shared images/PDFs and text remain in the composer until the operator sends
  them to the configured gateway;
- Sign out clears active-host authorization/session material and the active
  server selection but retains the registry, local Notes, local Prompts,
  preferences, and Room cache; Forget server removes that registry entry and
  its host-scoped authorization but does not erase unrelated local content;
- uninstalling the app is the complete local-data deletion path currently
  provided by Android.

The policy distinguishes data handled by the app from data collected by the
publisher. It does not use the misleading absolute phrase “collects nothing”
without explaining gateway, GitHub, and speech-provider processing.

`docs/PLAY_STORE_LISTING.md` is updated to match version 0.8.14 and the same
network disclosures. Its permission section accounts for every declared
permission: gateway/update networking, optional notifications, voice-input
microphone access, response-stream foreground data sync, local QR-scanner
camera access, and handing an accepted update APK to Android's package
installer. It removes brittle claims about an exact test count, APK size,
signing status, or the phone never contacting anything except the gateway.
Release notes describe user-visible capabilities rather than CI statistics.
The asset table records the real state: launcher mipmaps exist, but a
standalone 512x512 Play icon, a 1024x500 feature graphic, and final store
screenshots remain required release assets. This slice does not fabricate or
publish those assets.

## Component boundaries

- `android/app/src/main/AndroidManifest.xml`: manifest-level backup denial.
- `android/app/src/main/res/xml/backup_rules.xml`: pre-Android-12 complete
  domain exclusions.
- `android/app/src/main/res/xml/data_extraction_rules.xml`: Android 12+ cloud
  and device-transfer complete domain exclusions.
- `android/app/src/main/java/com/hermexapp/android/features/chat/VoiceInput.kt`:
  recognition selection/factory, real permission initialization, and accurate
  lifecycle comments.
- A focused JVM test beside the chat feature: pure recognition selection
  coverage without creating Android framework recognizers.
- A focused JVM policy test: parse the manifest and both XML resources and
  assert their structure, domains, paths, and cloud/D2D symmetry.
- `android/app/src/androidTest/java/com/hermexapp/android/persistence/MigrationInstrumentation.kt`:
  exact fixture and lock cleanup.
- `PRIVACY.md` and `docs/PLAY_STORE_LISTING.md`: public statements that match
  the shipping code and release state.

## Error and edge handling

- API 30 and below never call API-31 on-device methods.
- A device with no recognition service exposes voice input as unavailable.
- A recognizer-construction exception does not crash composition and does not
  trigger an undisclosed fallback.
- Denied microphone permission leaves listening false; a later grant is
  reflected by the result callback, while an existing grant is recognized on
  first composition.
- Non-result speech callbacks and recognizer errors continue to return the UI
  to its non-listening state without inserting text.
- Backup protection does not depend on the current database or preference
  filenames.
- Migration cleanup targets only the exact validated fixture paths and reports
  failure if either artifact survives.
- Documentation avoids promises the app cannot enforce, including whether a
  vendor's default speech service is offline.

## Test strategy

### Automated red-green coverage

1. Add a parsed-XML policy test that first fails against the current manifest
   and rules, then proves `allowBackup=false`, the complete exclusion-domain
   set, `path="."` for every exclusion, and identical cloud/D2D sets.
2. Add pure selection tests covering API 30/31 boundaries, on-device priority,
   default fallback, and the unavailable case. These fail before production
   selection logic exists and do not require Robolectric or a new dependency.
3. Add pure voice-start decision tests proving that an available recognizer
   starts immediately when permission is already granted, requests permission
   only when it is absent, and does nothing when recognition is unavailable.
   Production code supplies the real grant state from
   `ContextCompat.checkSelfPermission`; the API wiring is compiled by both
   variants and the existing-grant behavior is verified on the physical
   device. Do not add a framework-test dependency solely to duplicate that
   device proof.
4. Run the real Room v2-to-v3 instrumentation and assert both the fixture
   database and exact cache lock are absent after PASS.
5. Run focused tests after each red-green slice, then both debug and release
   unit suites, lint, debug/release assembly, and the full Gradle build.

Policy tests parse XML structurally. They do not merely search for source text,
and they fail if a later edit narrows or asymmetrically changes the exclusions.

### Physical-device acceptance

Use `adb install -r` with the debug APK so app data is preserved, then:

1. Record the conversation count before and after; it remains 37, and existing
   sessions, Notes, Prompts, pairing, and preferences remain available.
2. Confirm `dumpsys package com.hermexapp.android` no longer reports the
   `ALLOW_BACKUP` application flag.
3. With microphone permission already granted, tapping the mic does not open a
   redundant permission prompt. Denial/grant destructive reset scenarios are
   not forced on the operator's live app data.
4. On the API-33 OPPO, confirm voice input starts, inserts recognized text only
   into the composer, stops cleanly, and sends nothing because the test does
   not press Send. If Android reports on-device recognition unavailable, the
   documented system-default fallback is the expected mode.
5. Run the uniquely named migration instrumentation, confirm PASS, and verify
   neither its database files nor its cache lock remain. Confirm production
   `hermex.db`, WAL, and SHM files still exist.
6. Recheck the installed package, app launch, Settings privacy link, QR entry
   point, update dialog entry point, Notes, Prompts, and one existing chat for
   regressions without mutating server or user data.

## Acceptance criteria

- The manifest explicitly disables backup and the compiled installed package
  lacks `ALLOW_BACKUP`.
- Legacy, cloud, and device-transfer rules exclude every supported private
  data domain at its root, including the correct `database` domain.
- On-device speech is selected first on supported devices; the system default
  is an explicit, accurately documented fallback; unavailable devices remain
  stable.
- An existing microphone grant is honored without a redundant request.
- Migration instrumentation leaves no fixture database or lock and never
  touches the production database.
- Privacy and Play listing copy match actual storage, network, sensor,
  sign-out, asset, and version behavior.
- All automated verification passes, the installed debug app retains all
  existing data, and the primary app surfaces still open normally.

## Non-goals

- No new erase-data feature, sign-out semantics, encryption format, account
  model, API contract, Room schema, backup/export feature, speech engine,
  dependency, version bump, release signing, or Play Console submission.
- No backup or restore is triggered against the connected phone.
- No microphone-permission reset, app-data clear, uninstall, chat send, server
  mutation, or TalkBack session is part of acceptance.
- No push, tag, PR, merge, APK distribution, or store publication occurs
  without separate explicit operator approval.

## Implementation order and rollback

The implementation plan will split this into small commits in dependency
order: backup policy/tests, voice policy/tests, migration cleanup, truthful
documents, then full and device verification. Each slice is independently
revertible. Reverting the backup slice would reintroduce a security defect and
must never be used as a troubleshooting shortcut; failures should instead be
diagnosed while the explicit no-backup invariant stays intact.
