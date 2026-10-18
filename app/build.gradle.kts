import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Canal « release » (branche main) ou « test » (branche de test : app « TU
// Test », installable à côté de l'autre), passé par la CI : -Pchannel=test.
// Version : VERSION + numéro du build (-PappVersionName, -PappVersionCode).
val channel = (findProperty("channel") as String?) ?: "release"
val isTest = channel == "test"
val appVersionName = (findProperty("appVersionName") as String?) ?: "0.1.0"
val appVersionCode = ((findProperty("appVersionCode") as String?) ?: "1").toInt()
// Clé de signature : posée par la CI depuis les secrets GitHub (README).
// Sans elle, la clé de débogage de la machine (chaque build changerait de
// signature : il faudrait désinstaller avant chaque mise à jour).
val keystorePath: String? = System.getenv("ANDROID_KEYSTORE_PATH")?.takeIf { File(it).exists() }

android {
    namespace = "com.mxfia19.twitchunblock"
    compileSdk = 34

    defaultConfig {
        applicationId = if (isTest) "com.mxfia19.twitchunblock.test" else "com.mxfia19.twitchunblock"
        minSdk = 24
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName
        resValue("string", "app_name", if (isTest) "TU Test" else "TwitchUnblock")
        // Schéma des liens de l'app (notifications) : TU Test a le sien.
        manifestPlaceholders["appScheme"] = if (isTest) "twitchunblock-test" else "twitchunblock"
        buildConfigField("String", "CHANNEL", "\"$channel\"")
    }

    signingConfigs {
        create("release") {
            if (keystorePath != null) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (keystorePath != null) signingConfigs.getByName("release")
                            else signingConfigs.getByName("debug")
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
        buildConfig = true
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.5")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-gif:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.google.zxing:core:3.5.3")
}
