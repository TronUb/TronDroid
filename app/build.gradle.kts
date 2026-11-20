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
val signingStorePath = System.getenv("ANDROID_KEYSTORE_PATH").orEmpty()
val signingStorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD").orEmpty()
val signingKeyAlias = System.getenv("ANDROID_KEY_ALIAS").orEmpty()
val signingKeyPassword = System.getenv("ANDROID_KEY_PASSWORD").orEmpty()
val hasSharedSigningKey = signingStorePath.isNotBlank() && signingStorePassword.isNotBlank() &&
    signingKeyAlias.isNotBlank() && signingKeyPassword.isNotBlank()
android {
    namespace = "com.vikram.trondroid"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.vikram.trondroid"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        buildConfigField("String", "TELEGRAM_API_ID", "\"\${secret("telegramApiId")}\\"")
        buildConfigField("String", "TELEGRAM_API_HASH", "\"\${secret("telegramApiHash")}\\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86") }
    }
    flavorDimensions += "distribution"
    productFlavors {
        create("dev") {
            dimension = "distribution"
            applicationId = "com.vikram.trondroid.debug"
            versionNameSuffix = "-debug"
        }
        create("prod") {
            dimension = "distribution"
            applicationId = "com.vikram.trondroid"
        }
    }
    signingConfigs {
        if (hasSharedSigningKey) {
            create("shared") {
                storeFile = file(signingStorePath)
                storeType = "PKCS12"
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }
    buildTypes {
        debug {
            isDebuggable = true
            if (hasSharedSigningKey) signingConfig = signingConfigs.getByName("shared")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasSharedSigningKey) signingConfig = signingConfigs.getByName("shared")
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
