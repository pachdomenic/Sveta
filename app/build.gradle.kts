plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// CI передає ключ релізу через SVETA_KEYSTORE_*; без нього реліз підписується debug-ключем.
val releaseKeystore = providers.environmentVariable("SVETA_KEYSTORE_FILE").orNull?.takeIf { it.isNotBlank() }

android {
    namespace = "com.tuki.sveta"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.tuki.sveta"
        minSdk = 29
        targetSdk = 36
        // Реліз у CI бере версію з тегу: -Psveta.versionName=0.2.0 -Psveta.versionCode=2000.
        versionCode = providers.gradleProperty("sveta.versionCode").orNull?.toInt() ?: 1
        versionName = providers.gradleProperty("sveta.versionName").orNull ?: "0.1.0"
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                val storePass = providers.environmentVariable("SVETA_KEYSTORE_PASSWORD").get()
                storeFile = file(releaseKeystore)
                storePassword = storePass
                keyAlias = providers.environmentVariable("SVETA_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("SVETA_KEY_PASSWORD").orNull?.takeIf { it.isNotBlank() } ?: storePass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (releaseKeystore != null) "release" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("META-INF/{AL2.0,LGPL2.1}", "META-INF/INDEX.LIST", "bundle.properties")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all { test ->
            // -Psveta.live=true enables the tests that talk to the real DTEK mirror and t.me.
            test.systemProperty("sveta.live", providers.gradleProperty("sveta.live").getOrElse("false"))
            test.testLogging.showStandardStreams = true
        }
    }
}

kotlin {
    compilerOptions {
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "androidx.compose.foundation.layout.ExperimentalLayoutApi",
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.jsoup)
    implementation(libs.paho.mqtt)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
