import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    alias(libs.plugins.composeCompiler)
}

android {
    namespace = "io.github.audiz.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.audiz.yandexdownloader"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
        resources {
            excludes += "META-INF/AL2.0"
            excludes += "META-INF/LGPL2.1"
        }
    }
}

// 🔥 ИДЕАЛЬНОЕ РЕШЕНИЕ: Настраиваем таргет компилятора Kotlin на уровне всего подмодуля.
// Этот синтаксис на 100% распознается Kotlin 2.x, не зависит от багов AGP
// и полностью решает проблему "Inconsistent JVM Target Compatibility" между Java и Котлином!
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

dependencies {
    implementation(project(":shared")) {
        exclude(group = "net.java.dev.jna", module = "jna")
    }
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("net.java.dev.jna:jna:5.16.0@aar")
}
