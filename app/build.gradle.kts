plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

import org.gradle.api.tasks.compile.JavaCompile
import java.util.Properties

android {
    namespace = "com.calonyx.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.calonyx.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 47
        versionName = "0.21.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Permanent release key (~/workspace/calonyx/keystore/). All
            // builds from v0.17.0 on are signed with it; the ephemeral
            // debug key broke the v0.17.0 update (signature mismatch).
            val ksProps = Properties()
            val ksFile = rootProject.file("keystore/keystore.properties")
            if (ksFile.exists()) {
                ksProps.load(ksFile.inputStream())
                signingConfig = signingConfigs.create("permanent") {
                    storeFile = file(ksProps.getProperty("storeFile"))
                    storePassword = ksProps.getProperty("storePassword")
                    keyAlias = ksProps.getProperty("keyAlias")
                    keyPassword = ksProps.getProperty("keyPassword")
                }
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

// This build host cannot run Gradle worker daemons (localhost socket filter),
// so Java compilation uses a forked javac process instead of a worker.
tasks.withType<JavaCompile>().configureEach {
    options.isFork = true
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    // Room persistence for events, contacts, tags, types, theme.
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
