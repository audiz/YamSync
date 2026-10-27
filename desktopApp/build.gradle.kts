import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutinesSwing)
    implementation(libs.compose.uiToolingPreview)
}

// Версия приложения: тег релиза из CI (APP_VERSION, вида v1.2.3) либо appVersion из gradle.properties
val appVersion: String = providers.environmentVariable("APP_VERSION").orNull
    ?.removePrefix("v")?.takeIf { Regex("""\d+(\.\d+){1,2}""").matches(it) }
    ?: providers.gradleProperty("appVersion").get()

compose.desktop {
    application {
        mainClass = "io.github.audiz.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Deb, TargetFormat.Dmg)

            packageName = "yamsync"
            packageVersion = appVersion.let { if (it.count { c -> c == '.' } == 1) "$it.0" else it }

            // Настройки сборки для Windows
            windows {
                iconFile.set(project.file("icon.ico")) // Для Windows нужна иконка в формате .ico
                shortcut = true // Создать ярлык на рабочем столе
                menu = true // Добавить в меню "Пуск"
                upgradeUuid = "3f3b9c8a-4d2e-4b1a-8c3f-9d8e7f6a5b4c" // Уникальный UUID вашего приложения
            }

            linux {
                iconFile.set(project.file("icon.png"))
                shortcut = true
                menuGroup = "AudioVideo"
                debMaintainer = "github.com/audiz"
            }

            macOS {
                iconFile.set(project.file("icon.png"))
                bundleID = "io.github.audiz.yamsync"
            }

            appResourcesRootDir.set(project.file("src/jvmMain/resources"))
        }
    }
}
