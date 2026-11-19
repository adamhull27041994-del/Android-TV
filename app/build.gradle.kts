import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val tmdbProperties = Properties().apply {
    val credentials = rootProject.file("tmdb.properties")
    if (credentials.exists()) credentials.inputStream().use { load(it) }
}
fun escapeBuildConfig(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "") + "\""

android {
    namespace = "uk.co.cinematv"
    compileSdk = 36

    defaultConfig {
        applicationId = "uk.co.cinematv"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        buildConfigField("String", "TMDB_BEARER", escapeBuildConfig(tmdbProperties.getProperty("TMDB_BEARER", "")))
        buildConfigField("String", "TMDB_API_KEY", escapeBuildConfig(tmdbProperties.getProperty("TMDB_API_KEY", "")))
    }
    buildTypes {
        release { isMinifyEnabled = false }
        debug { isDebuggable = true }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.12.00")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
