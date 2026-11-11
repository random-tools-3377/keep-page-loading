import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val appVersionName: String = providers.gradleProperty("appVersionName").get()

// 1.2.3 becomes 10203. Each Release must have a higher number than the one before, or Android
// won't install it as an update.
fun versionCodeOf(name: String): Int {
    val parts = name.split(".").map { it.toInt() }
    require(parts.size == 3 && parts.all { it in 0..99 }) { "appVersionName must look like 1.2.3" }
    return parts[0] * 10000 + parts[1] * 100 + parts[2]
}

android {
    namespace = "io.github.randomtools3377.keeppageloading"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.randomtools3377.keeppageloading"
        minSdk = 26
        targetSdk = 35
        versionName = appVersionName
        versionCode = versionCodeOf(appVersionName)
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.webkit)

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
}
