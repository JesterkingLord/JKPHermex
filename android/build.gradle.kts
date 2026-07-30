// Top-level build file. Per-module configuration lives in app/build.gradle.kts;
// the locked dependency list lives in gradle/libs.versions.toml (see AGENTS.md).
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}

// Path overrides for structural-sentinel tests in :lib:jkp-* modules.
// ChatScreenImeInsetsLayoutTest reads MainActivity.kt and the app
// AndroidManifest to verify edge-to-edge + windowSoftInputMode setup.
// These tests used to walk up from the test class's location when :app was
// the only module; after the multi-module split, the test class lives in
// :lib:jkp-chat and the walk-up can't find the :app files. Setting these
// project properties bakes the correct paths into the test JVM.
val hermexProjectRoot: String =
    rootDir.absolutePath.replace("\\", "/")
val hermexMainActivity = "$hermexProjectRoot/app/src/main/java/com/hermexapp/android/MainActivity.kt"
val hermexChatScreen = "$hermexProjectRoot/lib/jkp-chat/src/main/java/com/hermexapp/android/features/chat/ChatScreen.kt"
val hermexManifest = "$hermexProjectRoot/app/src/main/AndroidManifest.xml"

// Apply to every test task in the project so ./gradlew test works without
// manual env-var setup.
subprojects {
    tasks.withType<Test>().configureEach {
        environment("HERMEX_MAINACTIVITY_SOURCE_PATH", hermexMainActivity)
        environment("HERMEX_CHATSCREEN_SOURCE_PATH", hermexChatScreen)
        environment("HERMEX_MANIFEST_SOURCE_PATH", hermexManifest)
    }
}
