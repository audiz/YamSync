plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    id("com.android.library") version "8.6.0" apply false
    id("com.android.application") version "8.6.0" apply false
    id("org.jetbrains.kotlin.android") version "2.2.20" apply false // укажите тут ту же версию Котлина, что у вас в проекте (2.2.20 или 2.4.10)
}