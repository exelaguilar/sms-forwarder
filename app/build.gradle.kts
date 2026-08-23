import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Release signing is opt-in: drop a keystore.properties next to this project (it is
// gitignored) and release builds get signed. Without it — on CI, or a fresh clone —
// the release build still succeeds, just unsigned.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
val hasSigningConfig = keystoreProperties.getProperty("storeFile") != null

android {
    namespace = "com.personal.smsforwarder"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.personal.smsforwarder"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasSigningConfig) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8 is left off deliberately: JavaMail resolves providers reflectively, and
            // a stripped SMTP path would fail only at send time, on a real account.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true // version name/code for the About screen
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    // JavaMail (com.sun.mail) ships duplicate metadata files that break packaging.
    packaging {
        resources {
            excludes += setOf(
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/LICENSE.md",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/NOTICE.md",
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST",
            )
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Biometric / device-credential prompt for the optional app lock. Its prompt is a
    // fragment, which is why MainActivity is a FragmentActivity.
    implementation("androidx.biometric:biometric:1.1.0")

    // Not optional. biometric:1.1.0 resolves androidx.fragment 1.2.x, whose
    // FragmentActivity still rejects any permission request code above 16 bits - and the
    // activity-result APIs generate exactly those, so every runtime permission request
    // crashes with "Can only use lower 16 bits for requestCode". Fragment 1.3+ dropped
    // the check. Verified by the app dying on the onboarding Grant button.
    implementation("androidx.fragment:fragment:1.8.5")

    // Encrypted storage for webhook URLs/headers and SMTP credentials.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Reliable, retrying delivery off the SMS receiver's thread.
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // HTTP forwarder.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // SMTP forwarder (JavaMail port for Android).
    implementation("com.sun.mail:android-mail:1.6.7")
    implementation("com.sun.mail:android-activation:1.6.7")

    // Config/history persistence (JSON blobs inside EncryptedSharedPreferences).
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
