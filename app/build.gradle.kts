import java.util.Properties

plugins {
    id("com.android.application")
}

// Push notifications (FCM) need google-services.json from the Firebase console. The build must
// still work before that file is added, so the plugin is only applied when it exists; without it
// the app runs fine and push simply stays off (alerts then arrive only while the app is open).
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
} else {
    logger.warn("app/google-services.json missing: building without push notifications (FCM)")
}

// Secrets live in local.properties (gitignored), never in source.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun localProp(key: String, def: String = ""): String = localProps.getProperty(key, def)

android {
    namespace = "com.example.naarishakti"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.naarishakti"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "TELEGRAM_BOT_TOKEN", "\"${localProp("TELEGRAM_BOT_TOKEN")}\"")
        buildConfigField("String", "TELEGRAM_BOT_USERNAME", "\"${localProp("TELEGRAM_BOT_USERNAME", "NaarishakiBot")}\"")
        // Naari Shakti API server (backend/). Empty = cloud features off. The Android emulator
        // reaches a server on the dev machine at http://10.0.2.2:8080.
        buildConfigField("String", "API_BASE_URL", "\"${localProp("API_BASE_URL")}\"")
        // Public base URL used in tracking links sent to contacts. Defaults to API_BASE_URL.
        buildConfigField("String", "TRACKING_BASE_URL",
            "\"${localProp("TRACKING_BASE_URL", localProp("API_BASE_URL"))}\"")
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packagingOptions {
        exclude("META-INF/NOTICE.md")
        exclude("META-INF/LICENSE.md")
    }
    // Keep the on-device ML models uncompressed so they can be memory-mapped.
    androidResources {
        noCompress += listOf("tflite")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}
allprojects {
    configurations.all {
        resolutionStrategy.force("org.jetbrains:annotations:23.0.0")
    }
}

dependencies {
    // Align Kotlin stdlib pulled in transitively by OkHttp / CameraX / ML Kit.
    implementation(platform("org.jetbrains.kotlin:kotlin-bom:1.9.24"))

    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.gridlayout:gridlayout:1.0.0")
    implementation("com.google.android.flexbox:flexbox:3.0.0")
    implementation("com.airbnb.android:lottie:6.1.0")
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")
    implementation("androidx.work:work-runtime:2.9.0")
    implementation("androidx.lifecycle:lifecycle-service:2.6.2")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Location, maps
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("com.google.android.gms:play-services-maps:19.0.0")
    implementation("com.google.android.gms:play-services-auth:20.7.0")
    implementation("org.osmdroid:osmdroid-android:6.1.20")

    // Offline mesh SOS (Bluetooth / Wi-Fi Direct between nearby phones)
    implementation("com.google.android.gms:play-services-nearby:19.3.0")

    // Voice trigger (offline) and scream detection (YAMNet via MediaPipe)
    implementation("com.alphacephei:vosk-android:0.3.47")
    implementation("com.google.mediapipe:tasks-audio:0.10.14")

    // Number-plate scanning (cab mode)
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")

    // Fast memory-mapped key-value storage (replaces SharedPreferences XML, no size penalty)
    implementation("com.tencent:mmkv:2.4.2")

    // Push notifications (FCM): guardian / nearby-helper alerts arrive with the app closed.
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-messaging")

    // Networking, mail, misc
    implementation("com.squareup.okhttp3:okhttp:4.10.0")
    implementation("com.sun.mail:android-mail:1.6.6")
    implementation("com.sun.mail:android-activation:1.6.7")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.google.guava:guava:31.1-android")
    implementation("com.google.guava:listenablefuture:9999.0-empty-to-avoid-conflict-with-guava")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
