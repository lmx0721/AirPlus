import org.apache.commons.lang3.SystemUtils
import net.fabricmc.loom.task.RemapJarTask

plugins {
    idea
    java
    id("gg.essential.loom") version "0.10.0.+"
    id("dev.architectury.architectury-pack200") version "0.1.3"
    id("com.github.johnrengelman.shadow") version "8.1.1"
    kotlin("jvm")
    id("com.gorylenko.gradle-git-properties") version "2.4.2"
}

// Constants
val baseGroup: String by project
val mcVersion: String by project
val version: String by project
val modid: String by project
val kotlin_version: String by project
val kotlin_coroutines_version: String by project
val transformerFile = file("src/main/resources/airplus_at.cfg")

// Toolchains: compile with JDK 8 (foojay auto-provisions), Gradle itself runs on JDK 17
java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(8))
}

// Minecraft / Forge configuration (Architectury Loom)
loom {
    log4jConfigs.from(file("log4j2.xml"))

    launchConfigs {
        "client" {
            property("mixin.debug", "true")
            arg("--tweakClass", "org.spongepowered.asm.launch.MixinTweaker")
        }
    }

    runConfigs {
        "client" {
            if (SystemUtils.IS_OS_MAC_OSX) {
                vmArgs.remove("-XstartOnFirstThread")
            }
            property("dev-mode", "true")
            vmArgs("-Xmx4096m", "-Xms1024m")
        }
        remove(getByName("server"))
    }

    forge {
        pack200Provider.set(dev.architectury.pack200.java.Pack200Adapter())
        mixinConfig("airplus.forge.mixins.json")
        if (transformerFile.exists()) {
            println("Installing access transformer")
            accessTransformer(transformerFile)
        }
    }

    // Mixin (refmap produced by Loom, must sit inside the loom {} block)
    mixin {
        defaultRefmapName.set("airplus.mixins.refmap.json")
    }
}

// Keep resources next to classes so the dev run finds them without a moveResources hack
sourceSets.main {
    output.setResourcesDir(sourceSets.main.flatMap { it.java.classesDirectory })
}

// Repositories
repositories {
    mavenCentral()
    maven("https://repo.spongepowered.org/maven/")
    maven("https://jitpack.io/")
}

// Shadowed runtime dependencies
val shadowImpl: Configuration by configurations.creating {
    configurations.implementation.get().extendsFrom(this)
}

dependencies {
    minecraft("com.mojang:minecraft:1.8.9")
    mappings("de.oceanlabs.mcp:mcp_stable:22-1.8.9")
    forge("net.minecraftforge:forge:1.8.9-11.15.1.2318-1.8.9")

    // Mixin runtime (shaded) + annotation processor (newer, generates the refmap)
    shadowImpl("org.spongepowered:mixin:0.7.11-SNAPSHOT") {
        isTransitive = false
    }
    annotationProcessor("org.spongepowered:mixin:0.8.5-SNAPSHOT")

    implementation("com.jagrosh:DiscordIPC:0.4")

    implementation("com.github.CCBlueX:Elixir:1.2.6") {
        exclude(module = "kotlin-stdlib")
        exclude(module = "authlib")
    }

    implementation("org.knowm.xchart:xchart:3.8.8")

    implementation("com.squareup.okhttp3:okhttp:5.0.0-alpha.14") {
        exclude(module = "kotlin-stdlib")
    }

    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:${kotlin_version}")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${kotlin_coroutines_version}")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${kotlin_coroutines_version}")

    implementation("com.formdev:flatlaf:3.5.4")

    implementation("javazoom:jlayer:1.0.1")
    implementation("com.googlecode.soundlibs:mp3spi:1.9.5.4")

    implementation("com.jhlabs:filters:2.0.235-1")

    // Nashorn (JS engine for the script system) - only available on the JDK 8 toolchain
    implementation(files("libs/nashorn.jar"))

    //Via
    implementation(files("libs/ViaBackwards-4.9.3-SNAPSHOT.jar"))
    implementation(files("libs/ViaRewind-3.0.7-SNAPSHOT.jar"))
    implementation(files("libs/ViaSnakeYaml-1.30.jar"))
    implementation(files("libs/ViaVersion-4.9.4-SNAPSHOT.jar"))
}

// Tasks

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.processResources {
    filesMatching(listOf("mcmod.info", "client.properties")) {
        expand(
            mapOf(
                "version" to project.version,
                "mcversion" to mcVersion,
                "modid" to modid,
                "basePackage" to baseGroup
            )
        )
    }

    // Access transformer -> META-INF/<modid>_at.cfg (read by Forge at load time)
    rename("airplus_at.cfg", "META-INF/airplus_at.cfg")
}

tasks.withType<Jar> {
    archiveBaseName.set("airplus")
    manifest {
        attributes(
            "FMLCorePlugin" to "net.airplus.injection.forge.MixinLoader",
            "FMLCorePluginContainsFMLMod" to "true",
            "ForceLoadAsMod" to "true",
            "TweakClass" to "org.spongepowered.asm.launch.MixinTweaker",
            "MixinConfigs" to "airplus.forge.mixins.json",
            "ModSide" to "CLIENT",
            "FMLAT" to "airplus_at.cfg"
        )
    }
}

tasks.jar {
    archiveClassifier.set("without-deps")
    destinationDirectory.set(layout.buildDirectory.dir("intermediates"))
}

tasks.shadowJar {
    destinationDirectory.set(layout.buildDirectory.dir("intermediates"))
    archiveClassifier.set("non-obfuscated-with-deps")
    configurations = listOf(shadowImpl)
    doLast {
        configurations.forEach {
            println("Copying dependencies into mod: ${it.files}")
        }
    }
    exclude("module-info.class")
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
    exclude("META-INF/maven/**")
    exclude("META-INF/versions/**")
    exclude("org/apache/log4j/**")
    exclude("org/apache/commons/**")
    exclude("org/junit/**")
}

// Final artifact: remap the shadowed jar (replaces ForgeGradle's reobf)
val remapJar by tasks.named<RemapJarTask>("remapJar") {
    archiveClassifier.set("")
    input.set(tasks.shadowJar.get().archiveFile)
    from(tasks.shadowJar)
}

tasks.assemble {
    dependsOn(remapJar)
}

gitProperties {
    failOnNoGitDirectory = false
}
