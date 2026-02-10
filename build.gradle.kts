plugins {
    java
    id("com.gradleup.shadow") version "8.3.0"
    id("xyz.jpenilla.run-paper") version "2.3.1"
}

group = "net.democracycraft"
version = "1.0.16"

repositories {
    mavenCentral()
    maven(uri("https://jitpack.io"))
    maven("https://repo.papermc.io/repository/maven-public/") {
        name = "papermc-repo"
    }
}

dependencies {
    val democracyLibVersion = "5891d074b3"
    val democracyLib = "com.github.MCCitiesNetwork:DemocracyLib:$democracyLibVersion"
    compileOnly("io.papermc.paper:paper-api:1.21.8-R0.1-SNAPSHOT")
    compileOnly("net.kyori:adventure-text-minimessage:4.17.0")
    compileOnly("com.github.plan-player-analytics:Plan:5.6.2965")
    implementation(democracyLib)
}

tasks {
    runServer {

        minecraftVersion("1.21.8")
    }
    shadowJar {
        configurations = listOf(project.configurations.runtimeClasspath.get())

        archiveClassifier.set("")
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.build {
    dependsOn("shadowJar")
}

tasks.processResources {
    val props = mapOf("version" to version)
    inputs.properties(props)
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand(props)
    }
}
