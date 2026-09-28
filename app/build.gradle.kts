plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Der Signaturschlüssel liegt verschlüsselt in signing/punkt.jks.enc.
// Die Build-Pipeline entschlüsselt ihn mit dem Secret PUNKT_SIGNING.
val signaturPasswort: String? = System.getenv("PUNKT_SIGNING")?.takeIf { it.isNotBlank() }
val signaturDatei = rootProject.file("signing/punkt.jks")

android {
    namespace = "de.punkt.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "de.punkt.app"
        minSdk = 31
        targetSdk = 34
        val lauf = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = lauf
        versionName = "0.$lauf"
    }

    signingConfigs {
        if (signaturPasswort != null && signaturDatei.exists()) {
            create("punkt") {
                storeFile = signaturDatei
                storePassword = signaturPasswort
                keyAlias = "punkt"
                keyPassword = signaturPasswort
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("punkt") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}
