# Backup, privacy and release hardening — 2026-08-01

> **STATUS: COMPLETE.** All three tasks shipped; the checkboxes below were
> never ticked at the time, which left this reading as outstanding work for
> days. Verified against the tree on 2026-08-09, not from memory:
>
> - Task 1 (no-backup policy) — `app/src/test/.../BackupPolicyTest.kt`,
>   3 tests green; the manifest carries `android:allowBackup="false"` with both
>   `dataExtractionRules` and `fullBackupContent` wired.
> - Task 2 (on-device speech) — `VoiceInputPolicy.kt` with
>   `VoiceInputPolicyTest.kt`, 7 tests green.
> - Task 3 (migration fixture cleanup) — `MigrationInstrumentation.kt`; the
>   v2→v4 chain was additionally proved on the CPH2343 (see CURRENT.md).
>
> Nothing below needs doing. Left intact as the record of how it was built.


> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Make the Android 0.8.14 binary and its public documentation enforce the approved no-backup policy, prefer genuinely on-device dictation, clean migration fixtures, and describe real storage/network behavior.

**Architecture:** Keep backup enforcement in the application manifest/resources, isolate speech selection and start decisions in a pure policy file inside `:lib:jkp-chat`, retain Android framework ownership in `VoiceInput.kt`, and keep migration cleanup inside the existing isolated instrumentation runner. The repository is now modular, so chat production/tests live in `android/lib/jkp-chat`; app policy, instrumentation, and packaging remain in `android/app`.

**Tech Stack:** Kotlin 2.1.10, Android SDK 35 (minSdk 26), Jetpack Compose, AndroidX Core, JUnit 4, Android instrumentation, Gradle wrapper, PowerShell, and ADB.

## Global Constraints

- Do not add dependencies, endpoints, JSON models, database migrations, or persistence schemas.
- Do not clear app data, uninstall the app, remove a pairing, send a message, mutate server state, reset permissions, or trigger backup/restore.
- Do not change Sign out or Forget server behavior; document the existing retention contract accurately.
- Do not open or enable TalkBack.
- Preserve the operator's conversations, Notes, Prompts, preferences, registry, and pairing.
- Use `adb install -r` for device installation and keep screenshots/UI dumps/device data out of Git.
- Preserve `_disabled_calendar/`, `prompts and notes/`, and `screenshots/` exactly as found.
- Do not push, tag, open a PR, merge, sign, publish, or distribute without explicit operator approval.
- Use the current modular branch and do not move extracted sources back into `:app`.
- Run commands from `E:\JKPHermex\android` unless a step explicitly says otherwise.

---

## File map

- `android/app/src/main/AndroidManifest.xml`: manifest-level no-backup policy.
- `android/app/src/main/res/xml/backup_rules.xml`: complete Android 11-and-lower exclusions.
- `android/app/src/main/res/xml/data_extraction_rules.xml`: complete Android 12+ cloud/D2D exclusions.
- `android/app/src/test/java/com/hermexapp/android/BackupPolicyTest.kt`: structural XML policy regression tests.
- `android/lib/jkp-chat/src/main/java/com/hermexapp/android/features/chat/VoiceInputPolicy.kt`: pure speech-mode and start-action decisions.
- `android/lib/jkp-chat/src/test/java/com/hermexapp/android/features/chat/VoiceInputPolicyTest.kt`: API-boundary, fallback, availability, and permission tests.
- `android/lib/jkp-chat/src/main/java/com/hermexapp/android/features/chat/VoiceInput.kt`: Android recognizer factory, permission observation, and lifecycle.
- `android/app/src/androidTest/java/com/hermexapp/android/persistence/MigrationInstrumentation.kt`: exact fixture cleanup and one-result exit path.
- `PRIVACY.md`: canonical privacy behavior for 0.8.14.
- `docs/PLAY_STORE_LISTING.md`: accurate store copy, permissions, release notes, and asset state.
- `CHANGELOG.md`: security/privacy hardening entry under `Unreleased`.
- `CURRENT.md`: local-only resume state after verification; never stage or commit it.

---

### Task 1: Enforce and test the no-backup policy

**Files:**
- Create: `android/app/src/test/java/com/hermexapp/android/BackupPolicyTest.kt`
- Modify: `android/app/src/main/AndroidManifest.xml`
- Modify: `android/app/src/main/res/xml/backup_rules.xml`
- Modify: `android/app/src/main/res/xml/data_extraction_rules.xml`

**Interfaces:**
- Consumes: Android's `application/@android:allowBackup`, `full-backup-content`, and `data-extraction-rules` XML contracts.
- Produces: an application that denies backup plus identical whole-domain exclusions for legacy, cloud, and D2D paths.

- [x] **Step 1: Write the failing structural policy test**

Create `BackupPolicyTest.kt` with the exact domain set and parsed-XML assertions:

```kotlin
package com.hermexapp.android

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element

class BackupPolicyTest {
    private val expectedDomains = setOf(
        "root",
        "file",
        "database",
        "sharedpref",
        "external",
        "device_root",
        "device_file",
        "device_database",
        "device_sharedpref",
    )

    @Test
    fun `manifest explicitly disables backup`() {
        val application = document("AndroidManifest.xml")
            .getElementsByTagName("application")
            .item(0) as Element
        assertEquals(
            "false",
            application.getAttributeNS(ANDROID_NAMESPACE, "allowBackup"),
        )
    }

    @Test
    fun `legacy rules exclude every data domain at its root`() {
        val root = document("res/xml/backup_rules.xml").documentElement
        assertEquals(expectedDomains, exclusionDomains(root))
    }

    @Test
    fun `cloud and device transfer rules have identical complete exclusions`() {
        val rules = document("res/xml/data_extraction_rules.xml")
        val cloud = rules.getElementsByTagName("cloud-backup").item(0) as Element
        val transfer = rules.getElementsByTagName("device-transfer").item(0) as Element
        assertEquals(expectedDomains, exclusionDomains(cloud))
        assertEquals(expectedDomains, exclusionDomains(transfer))
    }

    private fun exclusionDomains(parent: Element): Set<String> =
        (0 until parent.childNodes.length)
            .map { parent.childNodes.item(it) }
            .filterIsInstance<Element>()
            .filter { it.tagName == "exclude" }
            .onEach { assertEquals(".", it.getAttribute("path")) }
            .map { it.getAttribute("domain") }
            .toSet()

    private fun document(relativePath: String): Document =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(File(mainSourceDirectory, relativePath))

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"

        val mainSourceDirectory: File = requireNotNull(
            File(
                requireNotNull(System.getenv("HERMEX_MANIFEST_SOURCE_PATH")) {
                    "HERMEX_MANIFEST_SOURCE_PATH is configured by android/build.gradle.kts"
                },
            ).parentFile,
        )
    }
}
```

- [x] **Step 2: Run the test and verify RED**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.hermexapp.android.BackupPolicyTest
```

Expected: FAIL because `allowBackup` is empty and the exclusion sets contain only the current narrow entries.

- [x] **Step 3: Implement explicit manifest denial**

Add this attribute to `<application>` alongside the existing extraction-rule attributes:

```xml
android:allowBackup="false"
```

- [x] **Step 4: Replace the legacy rules with complete exclusions**

Keep a short comment explaining the no-backup policy and use this body:

```xml
<full-backup-content>
    <exclude domain="root" path="." />
    <exclude domain="file" path="." />
    <exclude domain="database" path="." />
    <exclude domain="sharedpref" path="." />
    <exclude domain="external" path="." />
    <exclude domain="device_root" path="." />
    <exclude domain="device_file" path="." />
    <exclude domain="device_database" path="." />
    <exclude domain="device_sharedpref" path="." />
</full-backup-content>
```

- [x] **Step 5: Replace Android 12+ rules symmetrically**

Use the same nine exclusions inside each section:

```xml
<data-extraction-rules>
    <cloud-backup>
        <exclude domain="root" path="." />
        <exclude domain="file" path="." />
        <exclude domain="database" path="." />
        <exclude domain="sharedpref" path="." />
        <exclude domain="external" path="." />
        <exclude domain="device_root" path="." />
        <exclude domain="device_file" path="." />
        <exclude domain="device_database" path="." />
        <exclude domain="device_sharedpref" path="." />
    </cloud-backup>
    <device-transfer>
        <exclude domain="root" path="." />
        <exclude domain="file" path="." />
        <exclude domain="database" path="." />
        <exclude domain="sharedpref" path="." />
        <exclude domain="external" path="." />
        <exclude domain="device_root" path="." />
        <exclude domain="device_file" path="." />
        <exclude domain="device_database" path="." />
        <exclude domain="device_sharedpref" path="." />
    </device-transfer>
</data-extraction-rules>
```

- [x] **Step 6: Run focused and app-module verification**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.hermexapp.android.BackupPolicyTest
.\gradlew.bat :app:testDebugUnitTest :app:testReleaseUnitTest :app:lintDebug
```

Expected: the three policy tests pass; app debug/release unit tests and lint finish successfully.

- [x] **Step 7: Commit the backup slice**

```powershell
git add app/src/main/AndroidManifest.xml app/src/main/res/xml/backup_rules.xml app/src/main/res/xml/data_extraction_rules.xml app/src/test/java/com/hermexapp/android/BackupPolicyTest.kt
git commit -m "fix(android): disable app data backup"
```

---

### Task 2: Prefer on-device speech and honor the existing permission

**Files:**
- Create: `android/lib/jkp-chat/src/main/java/com/hermexapp/android/features/chat/VoiceInputPolicy.kt`
- Create: `android/lib/jkp-chat/src/test/java/com/hermexapp/android/features/chat/VoiceInputPolicyTest.kt`
- Modify: `android/lib/jkp-chat/src/main/java/com/hermexapp/android/features/chat/VoiceInput.kt`

**Interfaces:**
- Produces: `selectVoiceRecognitionMode(Int, Boolean, Boolean): VoiceRecognitionMode` and `decideVoiceStart(Boolean, Boolean): VoiceStartDecision`.
- Consumes: API level, Android's on-device/default availability probes, recognizer construction, and the current `RECORD_AUDIO` grant.

- [x] **Step 1: Write the failing pure-policy tests**

Create `VoiceInputPolicyTest.kt`:

```kotlin
package com.hermexapp.android.features.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceInputPolicyTest {
    @Test
    fun `api 31 prefers on-device recognition`() {
        assertEquals(
            VoiceRecognitionMode.ON_DEVICE,
            selectVoiceRecognitionMode(31, onDeviceAvailable = true, systemAvailable = true),
        )
    }

    @Test
    fun `api 30 never selects the api 31 on-device path`() {
        assertEquals(
            VoiceRecognitionMode.SYSTEM_DEFAULT,
            selectVoiceRecognitionMode(30, onDeviceAvailable = true, systemAvailable = true),
        )
    }

    @Test
    fun `system provider is the explicit fallback`() {
        assertEquals(
            VoiceRecognitionMode.SYSTEM_DEFAULT,
            selectVoiceRecognitionMode(35, onDeviceAvailable = false, systemAvailable = true),
        )
    }

    @Test
    fun `no provider means unavailable`() {
        assertEquals(
            VoiceRecognitionMode.UNAVAILABLE,
            selectVoiceRecognitionMode(35, onDeviceAvailable = false, systemAvailable = false),
        )
    }

    @Test
    fun `existing grant starts immediately`() {
        assertEquals(VoiceStartDecision.START, decideVoiceStart(true, true))
    }

    @Test
    fun `missing grant requests permission`() {
        assertEquals(VoiceStartDecision.REQUEST_PERMISSION, decideVoiceStart(true, false))
    }

    @Test
    fun `unavailable recognizer does nothing`() {
        assertEquals(VoiceStartDecision.UNAVAILABLE, decideVoiceStart(false, true))
    }
}
```

- [x] **Step 2: Run the policy test and verify RED**

```powershell
.\gradlew.bat :lib:jkp-chat:testDebugUnitTest --tests com.hermexapp.android.features.chat.VoiceInputPolicyTest
```

Expected: compilation FAIL because the policy types and functions do not exist.

- [x] **Step 3: Implement the pure policy**

Create `VoiceInputPolicy.kt`:

```kotlin
package com.hermexapp.android.features.chat

internal enum class VoiceRecognitionMode {
    ON_DEVICE,
    SYSTEM_DEFAULT,
    UNAVAILABLE,
}

internal enum class VoiceStartDecision {
    START,
    REQUEST_PERMISSION,
    UNAVAILABLE,
}

internal fun selectVoiceRecognitionMode(
    sdkInt: Int,
    onDeviceAvailable: Boolean,
    systemAvailable: Boolean,
): VoiceRecognitionMode = when {
    sdkInt >= 31 && onDeviceAvailable -> VoiceRecognitionMode.ON_DEVICE
    systemAvailable -> VoiceRecognitionMode.SYSTEM_DEFAULT
    else -> VoiceRecognitionMode.UNAVAILABLE
}

internal fun decideVoiceStart(
    recognizerAvailable: Boolean,
    hasPermission: Boolean,
): VoiceStartDecision = when {
    !recognizerAvailable -> VoiceStartDecision.UNAVAILABLE
    hasPermission -> VoiceStartDecision.START
    else -> VoiceStartDecision.REQUEST_PERMISSION
}
```

- [x] **Step 4: Wire the policy into the Android controller**

Add imports for `PackageManager`, `Build`, and `ContextCompat`. The Core/Activity dependency graph already supplies AndroidX Core, so no Gradle dependency changes are needed. Replace the hard-coded permission and default recognizer initialization with:

```kotlin
var hasPermission by remember {
    mutableStateOf(
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED,
    )
}

val recognitionMode = remember(context) {
    selectVoiceRecognitionMode(
        sdkInt = Build.VERSION.SDK_INT,
        onDeviceAvailable = Build.VERSION.SDK_INT >= 31 &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context),
        systemAvailable = SpeechRecognizer.isRecognitionAvailable(context),
    )
}

val recognizer = remember(context, recognitionMode) {
    runCatching {
        when (recognitionMode) {
            VoiceRecognitionMode.ON_DEVICE ->
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            VoiceRecognitionMode.SYSTEM_DEFAULT ->
                SpeechRecognizer.createSpeechRecognizer(context)
            VoiceRecognitionMode.UNAVAILABLE -> null
        }
    }.getOrNull()
}
```

Use `decideVoiceStart(recognizerAvailable = recognizer != null, hasPermission)` in both the mic `start` lambda and permission callback. Map `START` to `listening = true`, `REQUEST_PERMISSION` to `permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)`, and `UNAVAILABLE` to no action. Set `isAvailable = recognizer != null`.

The callback and returned controller use these exact branches:

```kotlin
val permissionLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestPermission(),
) { granted ->
    hasPermission = granted
    if (decideVoiceStart(recognizer != null, granted) == VoiceStartDecision.START) {
        listening = true
    }
}

return VoiceInputController(
    isListening = listening,
    isAvailable = recognizer != null,
    start = {
        when (decideVoiceStart(recognizer != null, hasPermission)) {
            VoiceStartDecision.START -> listening = true
            VoiceStartDecision.REQUEST_PERMISSION ->
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            VoiceStartDecision.UNAVAILABLE -> Unit
        }
    },
    stop = {
        listening = false
        recognizer?.stopListening()
    },
)
```

Replace the inaccurate class comment with this contract:

```kotlin
/**
 * Dictation through Android's SpeechRecognizer. API 31+ prefers the dedicated
 * on-device recognizer when available; otherwise Android's default recognition
 * provider is used and may process audio over a network. Hermex retains no
 * microphone recording and inserts results into the composer without sending.
 */
```

- [x] **Step 5: Run focused RED-to-GREEN verification**

```powershell
.\gradlew.bat :lib:jkp-chat:testDebugUnitTest --tests com.hermexapp.android.features.chat.VoiceInputPolicyTest
.\gradlew.bat :lib:jkp-chat:testDebugUnitTest :lib:jkp-chat:testReleaseUnitTest :lib:jkp-chat:lintDebug
```

Expected: all seven policy tests pass; both chat variants compile/test; chat lint succeeds.

- [x] **Step 6: Commit the voice slice**

```powershell
git add lib/jkp-chat/src/main/java/com/hermexapp/android/features/chat/VoiceInput.kt lib/jkp-chat/src/main/java/com/hermexapp/android/features/chat/VoiceInputPolicy.kt lib/jkp-chat/src/test/java/com/hermexapp/android/features/chat/VoiceInputPolicyTest.kt
git commit -m "fix(android): prefer private on-device dictation"
```

---

### Task 3: Make migration instrumentation clean up exactly its fixture

**Files:**
- Modify: `android/app/src/androidTest/java/com/hermexapp/android/persistence/MigrationInstrumentation.kt`

**Interfaces:**
- Consumes: the existing unique database name, `Context.getDatabasePath`, `Context.cacheDir`, and Room v2-to-v3 verification.
- Produces: exactly one instrumentation result after close/delete checks; never touches `hermex.db`.

- [x] **Step 1: Preserve the existing RED evidence**

The prior physical run returned PASS but left `cache/hermex-migration-v2-v3-test.db.lck`. Do not rerun a destructive setup merely to recreate it while no device is connected. Record this known artifact as the failing behavior that this task fixes.

- [x] **Step 2: Refactor to one terminal result and a `finally` cleanup**

Split the existing body into three focused functions. Replace `onStart` with this complete control flow:

```kotlin
override fun onStart() {
    val testContext = targetContext
    val databaseName = "hermex-migration-v2-v3-test.db"
    val databaseFile = testContext.getDatabasePath(databaseName)
    var failure: Throwable? = null

    try {
        check(databaseName != "hermex.db") {
            "Refusing to use the production database name"
        }
        check(
            databaseFile.parentFile?.canonicalFile ==
                testContext.getDatabasePath("sentinel").parentFile?.canonicalFile &&
                databaseFile.name == databaseName,
        ) {
            "Fixture path does not belong to ${testContext.packageName}"
        }

        testContext.deleteDatabase(databaseName)
        databaseFile.parentFile?.mkdirs()
        createVersionTwoFixture(databaseFile)
        verifyVersionThreeMigration(testContext, databaseName)
    } catch (throwable: Throwable) {
        failure = throwable
    } finally {
        cleanupFixture(testContext, databaseName, databaseFile)
            ?.let { cleanupFailure ->
                failure = appendFailure(failure, cleanupFailure)
            }
    }

    val result = failure
    if (result == null) {
        finish(
            Activity.RESULT_OK,
            Bundle().apply {
                putString("stream", "PASS: Room v2-to-v3 preserved note and prompt")
            },
        )
    } else {
        finish(
            Activity.RESULT_CANCELED,
            Bundle().apply { putString("stream", result.stackTraceToString()) },
        )
    }
}
```

Add `import android.content.Context` and `import java.io.File`, then extract the current fixture SQL and assertions without changing their values:

```kotlin
private fun createVersionTwoFixture(databaseFile: File) {
    SQLiteDatabase.openOrCreateDatabase(databaseFile, null).use { sqlite ->
        sqlite.execSQL(
            "CREATE TABLE cached_payloads (`key` TEXT NOT NULL, json TEXT NOT NULL, " +
                "fetchedAtMillis INTEGER NOT NULL, PRIMARY KEY(`key`))",
        )
        sqlite.execSQL(
            "CREATE TABLE local_notes (id TEXT NOT NULL, title TEXT NOT NULL, " +
                "body TEXT NOT NULL, color_hex TEXT NOT NULL, pinned INTEGER NOT NULL, " +
                "updated_at_millis INTEGER NOT NULL, created_at_millis INTEGER NOT NULL, " +
                "PRIMARY KEY(id))",
        )
        sqlite.execSQL(
            "CREATE TABLE local_prompts (id TEXT NOT NULL, name TEXT NOT NULL, " +
                "body TEXT NOT NULL, tags TEXT NOT NULL, pinned INTEGER NOT NULL, " +
                "usage_count INTEGER NOT NULL, updated_at_millis INTEGER NOT NULL, " +
                "created_at_millis INTEGER NOT NULL, PRIMARY KEY(id))",
        )
        sqlite.execSQL(
            "INSERT INTO local_notes VALUES " +
                "('note-1','Keep me','Preserved body','#FFE9A2',1,200,100)",
        )
        sqlite.execSQL(
            "INSERT INTO local_prompts VALUES " +
                "('prompt-1','Keep prompt','Prompt body','tag',0,7,201,101)",
        )
        sqlite.version = 2
    }
}

private fun verifyVersionThreeMigration(context: Context, databaseName: String) {
    val room = HermexDatabase.build(context, databaseName)
    try {
        runBlocking {
            val note = room.notesDao().get("note-1")
            check(note?.title == "Keep me")
            check(note.body == "Preserved body")
            check(note.status == NoteStatus.IDEA)

            val prompt = room.promptsDao().get("prompt-1")
            check(prompt?.name == "Keep prompt")
            check(prompt.body == "Prompt body")
            check(prompt.usageCount == 7)
        }
    } finally {
        room.close()
    }
}
```

Add exact-path cleanup and failure aggregation:

```kotlin
private fun cleanupFixture(
    context: Context,
    databaseName: String,
    databaseFile: File,
): Throwable? = runCatching {
    val cacheDirectory = context.cacheDir.canonicalFile
    val lockFile = File(context.cacheDir, "$databaseName.lck").canonicalFile
    check(lockFile.parentFile == cacheDirectory) {
        "Fixture lock escaped the app cache directory"
    }
    check(context.deleteDatabase(databaseName) || !databaseFile.exists()) {
        "Could not delete migration fixture database"
    }
    check(!lockFile.exists() || lockFile.delete()) {
        "Could not delete migration fixture lock"
    }
    val remainingDatabases = databaseFile.parentFile
        ?.listFiles { _, name -> name.startsWith(databaseName) }
        .orEmpty()
    check(remainingDatabases.isEmpty() && !lockFile.exists()) {
        "Migration fixture artifacts remain after cleanup"
    }
}.exceptionOrNull()

private fun appendFailure(existing: Throwable?, next: Throwable): Throwable {
    if (existing == null) return next
    existing.addSuppressed(next)
    return existing
}
```

- [x] **Step 3: Compile the instrumentation APK locally**

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest
```

Expected: both APKs assemble without warnings caused by the cleanup refactor.

- [x] **Step 4: Commit the migration cleanup**

```powershell
git add app/src/androidTest/java/com/hermexapp/android/persistence/MigrationInstrumentation.kt
git commit -m "test(android): remove migration fixture locks"
```

- [x] **Step 5: Run real cleanup acceptance when a device is available**

Install with replacement semantics, then run the unique instrumentation:

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb install -r app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
adb shell am instrument -w com.hermexapp.android.test/com.hermexapp.android.persistence.MigrationInstrumentation
adb shell run-as com.hermexapp.android ls databases
adb shell run-as com.hermexapp.android ls cache
```

Expected: instrumentation reports PASS; `hermex.db`, its WAL, and SHM remain; no filename beginning with `hermex-migration-v2-v3-test.db` appears in either listing.

---

### Task 4: Make privacy and release copy match the shipping app

**Files:**
- Modify: `PRIVACY.md`
- Modify: `docs/PLAY_STORE_LISTING.md`
- Modify: `CHANGELOG.md`

**Interfaces:**
- Consumes: manifest permissions, `KeystoreSecretStore`, `AuthManager.signOut`, `AuthManager.forgetServer`, Room entities, QR scanner, share targets, and `UpdateChecker` routes.
- Produces: one consistent public account of publisher collection, device storage, third-party processing, retention, permissions, and release readiness.

- [x] **Step 1: Rewrite the privacy summary and device-storage table**

Set `Last updated` to `2026-08-01` and `Effective for` to Android `v0.8.14 and later`. The summary must say the publisher operates no analytics, advertising, crash-reporting, account, or chat backend while clearly distinguishing the configured gateway, GitHub update services, and Android's selected speech provider.

Use these storage rows:

```markdown
| Data | Storage | Android backup/device transfer |
|---|---|---|
| Pairing grants, device IDs, and session cookies | AES/GCM ciphertext in private SharedPreferences; the non-exportable key lives in Android Keystore | Disabled |
| Server registry and preferences | Private SharedPreferences | Disabled |
| Cached chat payloads | Room `hermex.db` | Disabled |
| Local Notes and Prompts | Room `hermex.db` | Disabled |
| Composer draft | Process memory | Not persisted |
```

- [x] **Step 2: Document sensors, network destinations, and retention exactly**

Include all of these statements in plain language:

- Camera frames are decoded locally for QR pairing, discarded immediately, and never uploaded as frames.
- Voice input requires microphone permission, stores no recording, prefers Android's on-device recognizer on API 31+ when available, and otherwise may use a system provider that processes audio remotely.
- Recognized or shared text/images/PDFs remain in the composer until the operator sends them to the configured gateway.
- Update checks try the configured gateway and GitHub API/git endpoints; accepted APK downloads come from GitHub release assets.
- Sign out clears active-host authorization, cookies, and active selection but retains the registry, preferences, Room cache, Notes, and Prompts.
- Forget server removes that registry entry and host-scoped authorization but does not erase unrelated local content.
- Uninstall is the current complete local-data deletion path.

Remove the stale absolute phrases `collects nothing`, `only talks`, `future voice input`, and the Jetpack `EncryptedSharedPreferences` claim.

- [x] **Step 3: Refresh Play Store copy for 0.8.14**

The permission block must explain every declared permission:

```text
Permissions explained:
 • INTERNET — connect to your chosen gateway and check/download GitHub updates
 • POST_NOTIFICATIONS — report completion of a background response when enabled
 • RECORD_AUDIO — optional dictation through Android speech recognition
 • CAMERA — optional, local-only QR pairing scan
 • FOREGROUND_SERVICE / DATA_SYNC — keep an active response stream alive in background
 • REQUEST_INSTALL_PACKAGES — hand an accepted update APK to Android's installer
```

Replace stale v0.5 release notes with v0.8.14 user-facing highlights: consolidated navigation, Notes/Prompts, improved composer, reliable sessions/offline cache, native markdown, and precise fast scrolling. Do not publish test counts, APK size, or signing claims in marketing copy.

Correct the asset table to state:

```markdown
| Asset | State |
|---|---|
| Launcher mipmaps | Present for mdpi through xxxhdpi |
| Standalone Play icon (512x512) | Still required |
| Feature graphic (1024x500) | Still required |
| Final phone screenshots | Still required |
```

- [x] **Step 4: Add a changelog security entry**

Under `[Unreleased]`, add:

```markdown
### Security
- Disabled Android cloud backup and device-transfer restore for all app-private data domains.
- Voice dictation now prefers Android's on-device recognizer when available and accurately documents the system-provider fallback.
- Corrected public privacy/storage disclosures for credentials, cached chats, Notes, Prompts, camera QR scanning, updates, and sign-out retention.
```

- [x] **Step 5: Scan for stale claims and verify document structure**

```powershell
rg -n "collects nothing|only talks|future voice input|not yet wired|EncryptedSharedPreferences|v0\.5\.0|230/230|1\.7 MB signed" ..\PRIVACY.md ..\docs\PLAY_STORE_LISTING.md
rg -n "v0\.8\.14|on-device|system provider|CAMERA|REQUEST_INSTALL_PACKAGES|Notes|Prompts|Sign out|Forget server" ..\PRIVACY.md ..\docs\PLAY_STORE_LISTING.md
git diff --check
```

Expected: the stale-claim search returns no matches; the required-disclosure search returns matches in the relevant documents; `git diff --check` succeeds.

- [x] **Step 6: Commit the documentation slice**

```powershell
git add ..\PRIVACY.md ..\docs\PLAY_STORE_LISTING.md ..\CHANGELOG.md
git commit -m "docs: correct Android privacy and release disclosures"
```

---

### Task 5: Run full local and non-destructive device acceptance

**Files:**
- Modify locally only: `CURRENT.md`
- Do not commit device artifacts.

**Interfaces:**
- Consumes: all four implementation slices and the connected OPPO CPH2343 when available.
- Produces: reproducible build/test evidence, installed-package proof, preserved user data, and an accurate resume point.

- [x] **Step 1: Verify tracked scope before the full run**

```powershell
git status --short --branch
git diff --check HEAD~4..HEAD
```

Expected: only the protected pre-existing untracked directories remain; no generated files or device evidence is staged.

- [x] **Step 2: Run the complete modular verification matrix**

```powershell
.\gradlew.bat --no-daemon testDebugUnitTest testReleaseUnitTest
.\gradlew.bat --no-daemon lintDebug
.\gradlew.bat --no-daemon assembleDebug assembleRelease assembleDebugAndroidTest
.\gradlew.bat --no-daemon build
```

Expected: every command exits zero. Inspect XML/HTML summaries for zero failures and lint errors instead of relying only on Gradle's final line.

- [x] **Step 3: Preserve and install when the OPPO reconnects**

```powershell
adb devices -l
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Before installation, record the visible session count and confirm Notes, Prompts, pairing, and preferences are present. After installation, confirm the same data remains. Do not unlock the phone by changing security settings and do not proceed with UI actions while the notification shade/lock screen owns focus.

- [x] **Step 4: Verify the compiled package backup flag**

```powershell
$backupFlag = adb shell dumpsys package com.hermexapp.android | Select-String 'ALLOW_BACKUP'
if ($backupFlag) { throw "Installed package still exposes ALLOW_BACKUP" }
```

Expected: no `ALLOW_BACKUP` match.

- [x] **Step 5: Verify voice behavior without sending content**

Check the existing `RECORD_AUDIO` grant with `dumpsys package`. If already granted, open one existing chat, tap the mic once, dictate harmless local text, confirm it appears only in the composer, stop recognition, and clear only that unsent draft through the UI. Do not press Send and do not reset the permission to test denial. Confirm no redundant permission prompt appears.

- [x] **Step 6: Run the migration acceptance from Task 3**

Run the exact instrumentation and directory-listing commands from Task 3. Confirm PASS, absence of the test database/lock, and presence of the production database files.

- [x] **Step 7: Smoke-test unaffected primary surfaces**

Open Sessions, one existing chat, Notes, Prompts, Projects, Files/Git, Settings, the privacy link, QR pairing entry point, and update dialog entry point. Do not save, send, delete, forget, install, or mutate remote state. Do not use TalkBack.

- [x] **Step 8: Update the local resume state**

Overwrite `CURRENT.md` with the verified branch/HEAD, exact commands/results, device state, preserved-data proof, known remaining work, and the next safe task. Keep it uncommitted.

- [x] **Step 9: Confirm the slice is ready for the next goal workstream**

```powershell
git status --short --branch
git log -5 --oneline
```

Expected: all intended source/docs changes are committed, only protected untracked directories remain, and no push/tag/PR/release action has occurred.

The next workstream after this plan is the separately designed, non-destructive Maestro master-flow suite; it must not be folded into these security commits.
