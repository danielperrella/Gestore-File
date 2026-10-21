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
        versionCode = 7
        versionName = "0.4.0-rc3"
        buildConfigField("boolean", "DRIVE_CONFIGURED", providers.gradleProperty("driveConfigured").orElse("false").get())
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { buildConfig = true }
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
    implementation("com.google.android.gms:play-services-auth:22.0.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
