import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Optional release signing. Credentials are read from android-app/keystore.properties (git-ignored) or from the
// P2G_KEYSTORE_FILE / P2G_KEYSTORE_PASSWORD / P2G_KEY_ALIAS / P2G_KEY_PASSWORD environment variables.
// When any value is missing (or the keystore file does not exist) no signing config is created and the release
// build stays unsigned; debug builds and unit tests are unaffected. See docs/BUILD.md.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}
fun signingValue(propKey: String, envKey: String): String? =
    (keystoreProps.getProperty(propKey) ?: System.getenv(envKey))?.takeIf { it.isNotBlank() }
val releaseStoreFile = signingValue("storeFile", "P2G_KEYSTORE_FILE")?.let { rootProject.file(it) }
val releaseStorePassword = signingValue("storePassword", "P2G_KEYSTORE_PASSWORD")
val releaseKeyAlias = signingValue("keyAlias", "P2G_KEY_ALIAS")
val releaseKeyPassword = signingValue("keyPassword", "P2G_KEY_PASSWORD")
val hasReleaseSigning = releaseStoreFile?.isFile == true &&
    releaseStorePassword != null && releaseKeyAlias != null && releaseKeyPassword != null

android {
    namespace = "com.phone2gamepad"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.phone2gamepad"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    sourceSets["main"].java.directories.add("src/main/kotlin")
    sourceSets["test"].java.directories.add("src/test/kotlin")
    // Shared, language-neutral protocol vectors (also used by the Windows tests).
    sourceSets["test"].resources.directories.add("../../shared/protocol/test-vectors")
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2026.03.01")
    implementation(bom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260814")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}
