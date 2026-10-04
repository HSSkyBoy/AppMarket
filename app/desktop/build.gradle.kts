import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    jvmToolchain(ProjectConfig.JVM_VERSION)

    jvm("desktop")

    sourceSets {
        val desktopMain = getByName("desktopMain")
        desktopMain.dependencies {
            implementation(projects.app.shared)
            implementation(projects.domain)
            implementation(projects.data)
            implementation(libs.koin.core)
            implementation(libs.ktor.client.core)
            implementation(compose.desktop.currentOs)
            implementation(libs.jetbrains.components.resources)
            implementation(libs.kotlinx.coroutines.swing)
        }
    }
}

compose.desktop {
    application {
        mainClass = "top.app.market.MainKt"

        buildTypes.release.proguard {
            optimize = false
            version.set("7.9.1")
            configurationFiles.from("proguard-rules-jvm.pro")
        }

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = ProjectConfig.APP_NAME
            packageVersion = ProjectConfig.VERSION_NAME
        }
    }
}
