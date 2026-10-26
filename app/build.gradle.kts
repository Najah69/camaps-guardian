import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.najah.camapsguardian"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.najah.camapsguardian"
        minSdk = 26
        // âš  IMPORTANT : targetSdk 32 est volontaire.
        // Ã€ partir de targetSdk 33, Android interdit aux applications d'activer
        // le Bluetooth par programme (BluetoothAdapter.enable() renvoie false).
        // En restant sous 33, le watchdog peut rallumer le Bluetooth tout seul.
        targetSdk = 32
        versionCode = 6
        versionName = "1.5"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        val propsFile = rootProject.file("keystore.properties")
        if (propsFile.exists()) {
            val props = Properties().apply { propsFile.inputStream().use { load(it) } }
            val store = rootProject.file(props.getProperty("storeFile") ?: "")
            if (store.exists()) {
                create("release") {
                    storeFile = store
                    storePassword = props.getProperty("storePassword")
                    keyAlias = props.getProperty("keyAlias")
                    keyPassword = props.getProperty("keyPassword")
                }
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // DÃ©clare les deux langues (franÃ§ais, anglais) au systÃ¨me : Android 13+ propose alors
    // le choix de la langue de l'application dans RÃ©glages â–¸ Applications â–¸ CamAPS Guardian.
    androidResources {
        generateLocaleConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
