import java.io.File
import java.util.Properties
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    kotlin("plugin.serialization") version "2.1.0"
    id("com.android.library")
}

// 🔒 Считывание ключа доступа из ENV (CI) или local.properties (локально)
val localPropsFile = rootProject.file("local.properties")
val localProps = Properties().apply {
    if (localPropsFile.exists()) {
        localPropsFile.inputStream().use { load(it) }
    }
}

val generateSecurityConfig = tasks.register("generateSecurityConfig") {
    val outputDir = layout.buildDirectory.dir("generated/source/security/commonMain/kotlin")
    outputs.dir(outputDir)
    if (localPropsFile.exists()) {
        inputs.file(localPropsFile)
    }
    inputs.property("envKey", providers.environmentVariable("LIB_ACCESS_KEY").orElse(""))
    inputs.property("propKey", localProps.getProperty("LIB_ACCESS_KEY") ?: "")

    doLast {
        val envKey = System.getenv("LIB_ACCESS_KEY")?.trim()
        val propKey = localProps.getProperty("LIB_ACCESS_KEY")?.trim()
        val accessKey = when {
            !envKey.isNullOrEmpty() -> envKey
            !propKey.isNullOrEmpty() -> propKey
            else -> "ym_sec_8f9c2d7e1a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0d"
        }

        val xorMask: Byte = 0x5C
        val keyBytes = accessKey.toByteArray(Charsets.UTF_8)
        val maskedBytes = keyBytes.map { (it.toInt() xor xorMask.toInt()).toByte() }
        val byteLiterals = maskedBytes.joinToString(", ") { "${it}.toByte()" }

        val targetFile = File(outputDir.get().asFile, "io/github/audiz/core/SecurityConfig.kt")
        targetFile.parentFile.mkdirs()
        targetFile.writeText(
            """
            package io.github.audiz.core

            internal object SecurityConfig {
                private const val XOR_MASK: Byte = ${xorMask}
                private val MASKED_KEY = byteArrayOf($byteLiterals)

                fun getAccessKey(): String {
                    val decrypted = ByteArray(MASKED_KEY.size) { i ->
                        (MASKED_KEY[i].toInt() xor XOR_MASK.toInt()).toByte()
                    }
                    return decrypted.decodeToString()
                }
            }
            """.trimIndent()
        )
    }
}

kotlin {
    applyDefaultHierarchyTemplate()
    jvm()
    androidTarget()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain {
            kotlin.srcDir(generateSecurityConfig)
            dependencies {
                implementation("io.ktor:ktor-client-core:3.0.0")
                implementation("io.ktor:ktor-client-content-negotiation:3.0.0")
                implementation("io.ktor:ktor-client-websockets:3.0.0")
                implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.0")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
            }
        }

        jvmMain.dependencies {
            implementation("io.ktor:ktor-client-cio:3.0.0")
            implementation("net.java.dev.jna:jna:5.16.0")
        }

        val androidMain by getting {
            dependencies {
                implementation("io.ktor:ktor-client-okhttp:3.0.0")
                implementation("net.java.dev.jna:jna:5.16.0")
            }
        }

        val iosMain = maybeCreate("iosMain")
        iosMain.dependencies {
            implementation("io.ktor:ktor-client-darwin:3.0.0")
        }
    }
}

tasks.matching { it.name.startsWith("compile") }.configureEach {
    dependsOn(generateSecurityConfig)
}

android {
    namespace = "io.github.audiz.core"
    compileSdk = 35
    defaultConfig {
        minSdk = 24
    }
}

tasks.register<JavaExec>("runConsole") {
    mainClass.set("io.github.audiz.MainKt")
    classpath = kotlin.jvm().compilations.getByName("main").runtimeDependencyFiles + files(kotlin.jvm().compilations.getByName("main").output.classesDirs)
}
