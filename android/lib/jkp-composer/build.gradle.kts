// jkp-composer: composer feature rail (templates, insert palette).
//
// The chat input area (ComposerConfig + ComposerControls) lives in :jkp-chat;
// this module is the side panel for templates and inserts that the chat
// composer exposes to the user.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.hermexapp.android.jkp.composer"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":lib:jkp-core"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
