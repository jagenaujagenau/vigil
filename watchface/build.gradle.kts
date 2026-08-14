plugins {
    id("com.android.application")
}

/**
 * Watch Face Format package. Wear OS 6 only accepts watch faces in this declarative format, and a
 * WFF package must contain no executable code — all the logic lives in the :app module and reaches
 * the face through a complication.
 */
android {
    namespace = "com.awakeface.wff"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.awakeface.wff"
        minSdk = 33
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the debug key so the face can be sideloaded for testing.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}
