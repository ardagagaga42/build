// build.gradle.kts
plugins {
    id("fabric-loom") version "1.9.2"
    id("maven-publish")
}

repositories {
    mavenCentral()
    // Repository for Wynntils
    maven {
        name = "CurseMaven"
        url = uri("https://www.cursemaven.com/")
    }
    // Repository for Baritone
    maven {
        name = "Jitpack"
        url = uri("https://jitpack.io")
    }
}

dependencies {
    // 1. Vanilla Minecraft and Mappings
    minecraft("com.mojang:minecraft:${project.property("minecraft_version")}")
    mappings("net.fabricmc:yarn:${project.property("yarn_mappings")}:v2")
    modImplementation("net.fabricmc:fabric-loader:${project.property("loader_version")}")

    // 2. Fabric API
    modImplementation("net.fabricmc.fabric-api:fabric-api:${project.property("fabric_version")}")

    // 3. ✅ WYNNTILS for MC 1.21.11
    modImplementation("curse.maven:wynntils-303451:${project.property("wynntils_file_id")}")

    // 4. ⚠️ BARITONE - May require a local build
    modImplementation("com.github.MeteorDevelopment:baritone:1.21.11")
}

tasks.processResources {
    inputs.property "version", project.version
    filteringCharset "UTF-8"

    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(21)
}

java {
    withSourcesJar()
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
        }
    }
}
