pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven("https://oss.sonatype.org/content/repositories/snapshots")
        maven("https://maven.architectury.dev/")
        maven("https://maven.fabricmc.net")
        maven("https://maven.minecraftforge.net/")
        maven("https://repo.spongepowered.org/maven/")
        maven("https://repo.essential.gg/repository/maven-releases/")
        maven("https://jitpack.io/")
    }
    resolutionStrategy {
        eachPlugin {
            when (requested.id.id) {
                "gg.essential.loom" -> useModule("gg.essential:architectury-loom:${requested.version}")
            }
        }
    }
    plugins {
        id("org.jetbrains.kotlin.jvm") version("2.0.21")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version("0.6.0")
}

rootProject.name = "AirPlus"
