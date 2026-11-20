import java.util.Properties
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use(::load)
}
fun secret(name: String) = providers.gradleProperty(name).orNull
    ?: System.getenv(if (name == "telegramApiId") "TELEGRAM_API_ID" else "TELEGRAM_API_HASH")
    ?: localProps.getProperty(name) ?: ""
android {
    namespace = "com.vikram.trondroid"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.vikram.trondroid"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        buildConfigField("String", "TELEGRAM_API_ID", "\"${secret("telegramApiId")}\"")
        buildConfigField("String", "TELEGRAM_API_HASH", "\"${secret("telegramApiHash")}\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86") }
    }
    flavorDimensions += "distribution"
    productFlavors {
        create("dev") {
            dimension = "distribution"
            applicationId = "com.vikram.trondroid-debug"
            versionNameSuffix = "-debug"
        }
        create("prod") {
            dimension = "distribution"
            applicationId = "com.vikram.trondroid"
        }
    }
    buildTypes {
        debug { isDebuggable = true }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    packaging { jniLibs { useLegacyPackaging = true } }
}
androidComponents {
    beforeVariants { variant ->
        if (variant.name != "devDebug" && variant.name != "prodRelease") variant.enable = false
    }
}
dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.compose.ui:ui:1.9.4")
    implementation("androidx.compose.ui:ui-tooling-preview:1.9.4")
    implementation("androidx.compose.foundation:foundation:1.9.4")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    debugImplementation("androidx.compose.ui:ui-tooling:1.9.4")
    testImplementation("junit:junit:4.13.2")
}
