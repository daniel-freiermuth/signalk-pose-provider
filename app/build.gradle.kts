import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("dagger.hilt.android.plugin")
    id("kotlin-parcelize")
    id("kotlinx-serialization")
}

// Exclude profileinstaller for reproducible builds (transitively pulled by lifecycle/activity)
configurations.configureEach {
    exclude(group = "androidx.profileinstaller", module = "profileinstaller")
}

val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("keystore.properties")
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(keystorePropertiesFile.inputStream())
}

android {
    namespace = "com.signalk.companion"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.signalk.companion"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        val isFDroidBuild = System.getenv("SOURCE_DATE_EPOCH") != null
        val isRepoDirty = if (isFDroidBuild) {
            false
        } else {
            providers.exec {
                commandLine("git", "status", "--porcelain")
            }.standardOutput.asText.get().trim().isNotEmpty()
        }

        versionName = versionName + if (isRepoDirty) "-DIRTY" else ""

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        val gitHash = providers.exec {
            commandLine("git", "rev-parse", "--short", "HEAD")
        }.standardOutput.asText.get().trim()

        val buildDate = when {
            System.getenv("SOURCE_DATE_EPOCH") != null -> {
                val epoch = System.getenv("SOURCE_DATE_EPOCH").toLongOrNull()
                epoch?.let {
                    Instant.ofEpochSecond(it)
                        .atZone(ZoneOffset.UTC)
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                }
            }
            isRepoDirty -> {
                LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
            }
            else -> {
                providers.exec {
                    commandLine("git", "log", "-1", "--format=%cI")
                }.standardOutput.asText.get().trim().let { gitDate ->
                    OffsetDateTime.parse(gitDate)
                        .atZoneSameInstant(ZoneOffset.UTC)
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                }
            }
        } ?: "unknown"

        buildConfigField("String", "GIT_HASH", "\"$gitHash\"")
        buildConfigField("String", "BUILD_DATE", "\"$buildDate\"")
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    dependenciesInfo {
        // Disable dependency metadata for reproducible builds
        includeInApk = false
        includeInBundle = false
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
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/**.prof"
            excludes += "META-INF/**baseline-prof.txt"
        }
    }

    testOptions {
        unitTests.all {
            it.useJUnitPlatform()
        }
    }
}

composeCompiler {
    includeSourceInformation = false
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    
    // ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    
    // Hilt
    implementation("com.google.dagger:hilt-android:2.53.1")
    ksp("com.google.dagger:hilt-compiler:2.53.1")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")
    
    // Navigation
    implementation("androidx.navigation:navigation-compose:2.8.5")
    
    // Location: platform LocationManager + GPS_PROVIDER only (architectural-plan.md P8).
    // Deliberately no com.google.android.gms:play-services-location — the Fused Location
    // Provider injects database-derived position jumps, and dropping it keeps the build
    // free of proprietary dependencies for F-Droid.

    // JSON
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    
    // WebSocket
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    
    // Permissions
    implementation("com.google.accompanist:accompanist-permissions:0.37.0")
    
    // Testing
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testImplementation("org.junit.jupiter:junit-jupiter-engine:5.10.3")
    testImplementation("org.mockito:mockito-core:5.8.0")
    testImplementation("org.mockito:mockito-inline:5.2.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.8.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.06.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
