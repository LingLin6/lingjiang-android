import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
}

val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use(::load)
    }
}

fun injected(name: String): String = localProps.getProperty(name) ?: System.getenv(name).orEmpty()

fun quoted(value: String): String {
    val escaped = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
    return "\"$escaped\""
}

android {
    namespace = "com.linglin.lingjiang"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.linglin.lingjiang"
        minSdk = 34
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        buildConfigField("String", "ASR_BASE_URL", quoted(injected("LINGJIANG_ASR_BASE_URL")))
        buildConfigField("String", "ASR_API_KEY", quoted(injected("LINGJIANG_ASR_API_KEY")))
        buildConfigField("String", "ASR_MODEL", quoted(injected("LINGJIANG_ASR_MODEL")))
        buildConfigField("String", "LLM_BASE_URL", quoted(injected("LINGJIANG_LLM_BASE_URL")))
        buildConfigField("String", "LLM_API_KEY", quoted(injected("LINGJIANG_LLM_API_KEY")))
        buildConfigField("String", "LLM_MODEL", quoted(injected("LINGJIANG_LLM_MODEL")))
        buildConfigField("String", "CLOUD_LLM_BASE_URL", quoted(injected("LINGJIANG_CLOUD_LLM_BASE_URL")))
        buildConfigField("String", "CLOUD_LLM_API_KEY", quoted(injected("LINGJIANG_CLOUD_LLM_API_KEY")))
        buildConfigField("String", "CLOUD_LLM_MODEL", quoted(injected("LINGJIANG_CLOUD_LLM_MODEL")))
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.4"
    }

    androidResources {
        noCompress += listOf("onnx", "txt")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

kapt {
    arguments {
        arg("room.schemaLocation", "$projectDir/schemas")
        arg("room.incremental", "true")
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2023.10.01"))
    implementation("androidx.activity:activity-compose:1.8.1")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended:1.5.4")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.6.2")
    implementation("androidx.navigation:navigation-compose:2.7.5")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    implementation("androidx.datastore:datastore-preferences:1.0.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation(files("libs/lib-onnx-6.16.7.aar"))
    implementation(files("libs/lib-sherpa-onnx-6.25.21.aar"))

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
