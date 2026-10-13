plugins { id("com.android.application"); kotlin("android") }
android {
    namespace = "it.liberofiles.app"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    signingConfigs {
        getByName("debug") {
            // La chiave originale resta locale; GitHub non contiene chiavi private.
            // In sua assenza si usa la chiave debug generata da Android Gradle Plugin.
            val developmentKey = rootProject.file("signing/development.jks")
            if (developmentKey.exists()) {
                storeFile = developmentKey
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = false
        }
    }
    defaultConfig {
        applicationId = "it.liberofiles.app"
        minSdk = 30
        targetSdk = 35
        versionCode = 2
        versionName = "0.1.1"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { it.systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2") }
    }
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":core"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
