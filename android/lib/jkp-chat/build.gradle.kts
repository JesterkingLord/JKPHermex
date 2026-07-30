// jkp-chat: the main chat surface (chat list, search, composer controls,
// speech/voice I/O, hang-honesty state, interaction overlays).
//
// Depends on :lib:jkp-core (models, network, theme), :lib:jkp-composer
// (the composer feature rail — templates, insert palette), and
// :lib:jkp-sessions (SessionRepository).
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.hermexapp.android.jkp.chat"
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
    implementation(project(":lib:jkp-composer"))
    implementation(project(":lib:jkp-sessions"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}
