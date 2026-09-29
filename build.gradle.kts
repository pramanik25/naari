// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("com.android.application") version "8.2.2" apply false
    // Firebase push (nearby-helper / guardian alerts with the app closed). Applied in :app only
    // when app/google-services.json exists.
    id("com.google.gms.google-services") version "4.4.2" apply false
}
