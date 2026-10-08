plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    kotlin("plugin.serialization") version "2.1.0"
    id("com.android.library")
}

kotlin {
    applyDefaultHierarchyTemplate()
    jvm()
    androidTarget()

    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":core"))
                implementation(libs.compose.runtime)
                implementation(libs.compose.foundation)
                implementation(libs.compose.material3)
                implementation(libs.compose.ui)
                implementation(libs.compose.components.resources)
                implementation(libs.compose.uiToolingPreview)
                implementation(libs.androidx.lifecycle.viewmodelCompose)
                implementation(libs.androidx.lifecycle.runtimeCompose)
                implementation(compose.materialIconsExtended)
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
                implementation("io.ktor:ktor-client-core:3.0.0")
                implementation("io.ktor:ktor-client-content-negotiation:3.0.0")
                implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.0")
            }
        }

        commonTest {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }

        val jvmAndAndroidMain by creating {
            dependsOn(commonMain.get())
        }
        jvmMain.get().dependsOn(jvmAndAndroidMain)
        androidMain.get().dependsOn(jvmAndAndroidMain)

        jvmMain {
            dependencies {
                implementation(libs.kotlinx.coroutinesSwing)
                // MP3 playback fallback
                implementation("javazoom:jlayer:1.0.1")
                // FLAC playback fallback
                implementation("org.jflac:jflac-codec:1.5.2")

                // JavaFX Media (AAC, M4A, MP3, WAV) for Windows, Linux, macOS
                val javafxVersion = "21.0.6"
                implementation("org.openjfx:javafx-base:$javafxVersion")
                implementation("org.openjfx:javafx-graphics:$javafxVersion")
                implementation("org.openjfx:javafx-media:$javafxVersion")
                for (platform in listOf("win", "linux", "mac")) {
                    implementation("org.openjfx:javafx-base:$javafxVersion:$platform")
                    implementation("org.openjfx:javafx-graphics:$javafxVersion:$platform")
                    implementation("org.openjfx:javafx-media:$javafxVersion:$platform")
                }
            }
        }

        androidMain {
            dependencies {
                implementation("androidx.activity:activity-compose:1.9.0")
            }
        }
    }
}

android {
    namespace = "io.github.audiz.shared"
    compileSdk = 35
    defaultConfig {
        minSdk = 24
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/androidMain/jniLibs")
        }
    }
}
