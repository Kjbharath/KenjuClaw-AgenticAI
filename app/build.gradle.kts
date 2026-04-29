plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace     = "com.kenju.claw"
    compileSdk    = 35

    defaultConfig {
        applicationId         = "com.kenju.claw"
        minSdk                = 35          // Samsung S25 Ultra ships on Android 15
        targetSdk             = 35
        versionCode           = 1
        versionName           = "1.0.0"

        // Snapdragon 8 Elite (SM8750) — build metadata available at runtime
        buildConfigField("String", "TARGET_SOC",     "\"sm8750\"")
        buildConfigField("String", "HEXAGON_VERSION", "\"v79\"")
        buildConfigField("String", "ADRENO_VERSION",  "\"830\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled    = true
            isShrinkResources  = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable       = true
            applicationIdSuffix = ".debug"
            versionNameSuffix   = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi"
        )
    }

    buildFeatures {
        compose     = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.savedstate)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.timber)

    // TODO: Uncomment when integrating GoogleGpuEngine real MediaPipe calls.
    //       Also add abiFilters { include("arm64-v8a") } to defaultConfig when enabling.
    //       Requires: libs.mediapipe.tasks.genai (declared in libs.versions.toml)
    // implementation(libs.mediapipe.tasks.genai)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
