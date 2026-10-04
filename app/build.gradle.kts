plugins {
    alias(libs.plugins.agp.app)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "moe.yanhe.xmsound"
    compileSdk = 37

    defaultConfig {
        applicationId = "moe.yanhe.xmsound"
        minSdk = 35
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("long", "BUILD_TIMESTAMP", System.currentTimeMillis().toString())
    }

    signingConfigs {
        create("release") {
            storeFile = file("${rootDir}/keystore/xmsound.jks")
            storePassword = (project.findProperty("KEYSTORE_PASSWORD") as String?) ?: "xmsound"
            keyAlias = (project.findProperty("KEY_ALIAS") as String?) ?: "xmsound"
            keyPassword = (project.findProperty("KEY_PASSWORD") as String?) ?: "xmsound"
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            isDebuggable = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    dependenciesInfo.includeInApk = false

    buildFeatures {
        buildConfig = true
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/**.version"
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            excludes += "kotlin/**"
            excludes += "org/**"
            excludes += "**.properties"
            excludes += "kotlin-tooling-metadata.json"
        }
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(JavaVersion.VERSION_22.majorVersion)
    }
}

kotlin {
    jvmToolchain(JavaVersion.VERSION_22.majorVersion.toInt())
}

dependencies {
    implementation(libs.coreKtx)
    compileOnly(libs.libxposedApi)
    // Matches the known-working libxposed modules on this framework (OppoPods, HyperEars).
    implementation(libs.libxposedService)
    implementation(libs.kotlinx.serialization.json)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.androidx.activity.compose)

    testImplementation(libs.junit)
}
