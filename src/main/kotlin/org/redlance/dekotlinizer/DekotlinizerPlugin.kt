package org.redlance.dekotlinizer

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.BasePlugin
import org.gradle.api.plugins.JavaPlugin
import org.gradle.kotlin.dsl.register

/**
 * Registers the [DekotlinizeJarTask] named `dekotlinize`, fed by the `jar` task of the project it is
 * applied to. A build that transforms something else — several jars, or one produced by another task —
 * adds them to `inputJars` or registers another task of the same type.
 */
class DekotlinizerPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.plugins.withId("java") {
            target.tasks.register<DekotlinizeJarTask>(TASK_NAME) {
                group = BasePlugin.BUILD_GROUP
                description = "Rewrites the project jar so it runs on a plain JVM without the Kotlin runtime."
                inputJars.from(target.tasks.named(JavaPlugin.JAR_TASK_NAME))
                outputJar.convention(
                    target.layout.buildDirectory.file("dekotlinizer/${target.name}-plain.jar")
                )
            }
        }
    }

    companion object {
        const val TASK_NAME = "dekotlinize"
    }
}
