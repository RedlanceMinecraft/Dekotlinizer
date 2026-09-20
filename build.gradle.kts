import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `kotlin-dsl`
    `maven-publish`
    id("com.gradleup.shadow") version "9.6.1"
}

group = "org.redlance"
version = "1.0.0"

// ASM is bundled relocated: a buildscript classpath is shared with every other plugin applied to the
// same project, and the first ASM on it would otherwise decide which API this transform really gets.
// The dependency stays compileOnly so it does not reach the published metadata either.
val shaded = configurations.create("shaded")

configurations.compileOnly {
    extendsFrom(shaded)
}

dependencies {
    shaded("org.ow2.asm:asm:9.10.1")
    shaded("org.ow2.asm:asm-tree:9.10.1")
    shaded("org.ow2.asm:asm-commons:9.10.1")
}

tasks.shadowJar {
    configurations = listOf(shaded)
    relocate("org.objectweb.asm", "org.redlance.dekotlinizer.asm")
    archiveClassifier = ""
    // Nothing but ASM is bundled, so a duplicate path would be a real clash rather than noise to drop.
    duplicatesStrategy = DuplicatesStrategy.WARN
}

// The relocated jar is the artifact of this module; the plain one would only collide with it.
tasks.jar {
    archiveClassifier = "thin"
}

listOf("apiElements", "runtimeElements").forEach { name ->
    configurations.named(name) {
        outgoing.artifacts.clear()
        outgoing.artifact(tasks.shadowJar)
    }
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}

// Java 17 bytecode so builds running on the oldest JDK Gradle still supports can apply the plugin.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

java {
    withSourcesJar()
}

gradlePlugin {
    website.set("https://github.com/RedlanceMinecraft/Dekotlinizer")
    vcsUrl.set("https://github.com/RedlanceMinecraft/Dekotlinizer.git")

    plugins.create("dekotlinizer") {
        id = "org.redlance.dekotlinizer"
        implementationClass = "org.redlance.dekotlinizer.DekotlinizerPlugin"
        displayName = "Dekotlinizer"
        description = "Strips the Kotlin runtime out of compiled jars so they run on a plain JVM."
    }
}

// Credentials come from a private gradle.properties or the environment, never from this repository.
publishing {
    repositories {
        maven {
            name = "RedlanceMinecraft"
            url = uri("https://repo.redlance.org/public")
            credentials {
                username = providers.gradleProperty("release.repo.username")
                    .orElse(providers.environmentVariable("RELEASE_REPO_USERNAME")).orNull
                password = providers.gradleProperty("release.repo.password")
                    .orElse(providers.environmentVariable("RELEASE_REPO_PASSWORD")).orNull
            }
        }
    }
}
