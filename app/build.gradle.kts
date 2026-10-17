import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}
extensions.configure<com.android.build.api.dsl.ApplicationExtension> {
    namespace = "com.happy.consumption"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.happy.consumption"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    val signingFile = file(System.getProperty("user.home") + "/.happy-consumption-secrets/keystore.properties")
    if (signingFile.isFile) {
        val signingProperties = Properties().apply { signingFile.inputStream().use { load(it) } }
        signingConfigs.create("release") {
            storeFile = file(signingProperties.getProperty("storeFile"))
            storePassword = signingProperties.getProperty("storePassword")
            keyAlias = signingProperties.getProperty("keyAlias")
            keyPassword = signingProperties.getProperty("keyPassword")
            enableV2Signing = true
            enableV3Signing = true
        }
    }
    buildTypes {
        getByName("debug") { isMinifyEnabled = false; isShrinkResources = false }
        getByName("release") {
            val optimize = providers.gradleProperty("releaseOptimization").orNull != "false"
            isMinifyEnabled = optimize
            isShrinkResources = optimize
            signingConfigs.findByName("release")?.let { signingConfig = it }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint { abortOnError = true }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation(platform("androidx.compose:compose-bom:2026.03.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.03.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
