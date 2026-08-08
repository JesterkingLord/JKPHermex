// jkp-workspace: file tree + git operations UI (WorkspaceScreens +
// WorkspaceViewModel). No state held outside the ViewModel; the data comes
// from the network client in :jkp-core.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.hermexapp.android.jkp.workspace"
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

    // First tests in this module. junit is already in the version catalog and
    // used by every other module here — no new third-party dependency.
    testImplementation(libs.junit)

    // For the view-model tests. Proving the image branch *never* calls
    // /api/file needs a served endpoint whose requests can be inspected —
    // an assertion about a call that did not happen cannot be made against a
    // stub that was never asked. Both are already used by jkp-core, jkp-auth,
    // jkp-chat and jkp-sessions; nothing new enters the build.
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
