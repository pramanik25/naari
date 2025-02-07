import com.android.build.api.dsl.Packaging

plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.naarishakti"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.naarishakti"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures {
        viewBinding = true
    }

    packagingOptions { exclude("META-INF/NOTICE.md")
        exclude("META-INF/LICENSE.md") }
    
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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

    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("com.google.ai.edge.litert:litert-metadata:1.0.1")
    implementation("com.google.android.gms:play-services-tflite-acceleration-service:16.4.0-beta01")

        "implementation"("androidx.room:room-compiler:2.6.1"){
            exclude(group = "com.intellij", module = "annotations")
        }
    implementation("com.google.android.gms:play-services-maps:19.0.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("androidx.gridlayout:gridlayout:1.0.0")


    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")

    implementation ("com.google.code.gson:gson:2.10.1")
    runtimeOnly("com.google.android.material:material:1.13.0-alpha09")

    implementation("com.airbnb.android:lottie:6.1.0")



    implementation("com.alphacephei:vosk-android:0.3.47")
    // INtegrating the Vosk API to better voicerecognisation

    implementation ("org.osmdroid:osmdroid-android:6.1.20")


    implementation ("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")


    implementation ("androidx.work:work-runtime:2.9.0")
    implementation ("com.google.guava:guava:31.1-android")

    implementation("com.squareup.okhttp3:okhttp:4.10.0")
    implementation ("com.sun.mail:android-mail:1.6.6")
    implementation("com.sun.mail:android-activation:1.6.7")
    implementation ("com.google.guava:listenablefuture:9999.0-empty-to-avoid-conflict-with-guava")

    implementation ("com.google.android.flexbox:flexbox:3.0.0")
}