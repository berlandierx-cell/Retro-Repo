plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    // Winlator Java sources refer to com.winlator.R. Runtime app id is com.retroiso.
    namespace = "com.winlator"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.retroiso"
        minSdk = 30
        targetSdk = 28
        versionCode = 15
        versionName = "0.5.9-colors-session"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    // A stable, private keystore is injected by GitHub Actions from the
    // RETROISO_SIGNING_BUNDLE repository secret. Do not commit the keystore.
    // Debug builds are deliberately signed with this same certificate so a
    // new Actions runner produces an Android-installable upgrade.
    val retroIsoKeystore = System.getenv("RETROISO_KEYSTORE_PATH")
    val retroIsoPasswordFile = System.getenv("RETROISO_SIGNING_PASSWORD_FILE")
    if (!retroIsoKeystore.isNullOrBlank() && !retroIsoPasswordFile.isNullOrBlank()) {
        signingConfigs {
            create("retroIsoStable") {
                storeFile = file(retroIsoKeystore)
                val password = java.io.File(retroIsoPasswordFile).readText().trim()
                storePassword = password
                keyAlias = "retroiso"
                keyPassword = password
                storeType = "PKCS12"
            }
        }
        buildTypes {
            getByName("debug") {
                signingConfig = signingConfigs.getByName("retroIsoStable")
            }
        }
    }

    sourceSets {
        getByName("main") {
            java.srcDir("../.runtime/winlator/app/src/main/java")
            res.srcDir("../.runtime/winlator/app/src/main/res")
            assets.srcDir("../.runtime/winlator/app/src/main/assets")
            jniLibs.srcDir("../.runtime/winlator/app/src/main/jniLibs")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("../.runtime/winlator/app/src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    ndkVersion = "24.0.8215888"

    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Dependencies used by the embedded Winlator runtime.
    implementation("androidx.appcompat:appcompat:1.4.0")
    implementation("androidx.preference:preference:1.2.1")
    implementation("com.google.android.material:material:1.4.0")
    implementation("com.github.luben:zstd-jni:1.5.2-3@aar")
    implementation("org.tukaani:xz:1.7")
    implementation("org.apache.commons:commons-compress:1.20")
    implementation("androidx.lifecycle:lifecycle-process:2.5.1")
}

