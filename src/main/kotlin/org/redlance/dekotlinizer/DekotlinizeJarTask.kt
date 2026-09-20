package org.redlance.dekotlinizer

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.SimpleRemapper
import org.objectweb.asm.tree.ClassNode
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream

/**
 * Merges [inputJars] into [outputJar] with the Kotlin runtime stripped out, so the result runs on a
 * plain JVM that has no `kotlin-stdlib` on its classpath. Kotlin classes keep value semantics as POJOs
 * a reflective JSON library can read; Java classes are copied untouched. The output is validated: a
 * single surviving `kotlin`/`kotlinx` reference fails the build.
 *
 * Run this before an obfuscator — readable names make both the transform and its failures legible.
 */
@CacheableTask
abstract class DekotlinizeJarTask : DefaultTask() {
    @get:InputFiles
    @get:Classpath
    abstract val inputJars: ConfigurableFileCollection

    @get:OutputFile
    abstract val outputJar: RegularFileProperty

    /**
     * Kotlin types replaced by a JDK counterpart, as JVM internal names; defaults to
     * [DEFAULT_TYPE_REMAPPINGS]. A call the replacement does not declare fails the build.
     */
    @get:Input
    abstract val typeRemappings: MapProperty<String, String>

    /**
     * Drops `MethodParameters` from non-public classes. Worth enabling when an obfuscator renames them
     * anyway and a global keep rule would otherwise publish their parameter names to consumers.
     */
    @get:Input
    abstract val stripNonPublicParameterNames: Property<Boolean>

    init {
        typeRemappings.convention(DEFAULT_TYPE_REMAPPINGS)
        stripNonPublicParameterNames.convention(false)
    }

    @TaskAction
    fun run() {
        val remappings = typeRemappings.get().map { (kotlinType, javaType) -> TypeRemapping(kotlinType, javaType) }
        val remapper = SimpleRemapper(Opcodes.ASM9, typeRemappings.get())
        val validator = KotlinReferenceValidator(remappings)
        val hideParameterNames = stripNonPublicParameterNames.get()
        val violations = mutableListOf<String>()
        val written = mutableSetOf<String>()
        val target = outputJar.get().asFile.apply { parentFile.mkdirs() }

        JarOutputStream(target.outputStream().buffered()).use { output ->
            for (file in inputJars.files) {
                if (!file.isFile) continue
                JarFile(file).use { input ->
                    for (entry in input.entries().asSequence()) {
                        if (entry.isDirectory) continue

                        if (!entry.name.endsWith(".class")) {
                            if (isKotlinMetadata(entry.name)) continue
                            if (written.add(entry.name)) copy(input, entry, output)
                            continue
                        }
                        if (isSerializationHelper(entry.name)) continue

                        val node = read(input, entry, remapper)
                        if (KotlinClassStripper.isKotlinClass(node)) KotlinClassStripper.strip(node)
                        if (hideParameterNames) KotlinClassStripper.hideNonPublicParameterNames(node)
                        violations += validator.validate(node)
                        if (written.add(entry.name)) write(node, entry.name, output)
                    }
                }
            }
        }

        if (violations.isNotEmpty()) {
            target.delete()
            throw GradleException(
                "Kotlin runtime references survived the transform (${violations.size}):\n" +
                    violations.take(REPORTED_VIOLATIONS).joinToString("\n") { "  $it" }
            )
        }
        logger.lifecycle("dekotlinize: ${written.count { it.endsWith(".class") }} classes, no Kotlin runtime left")
    }

    private fun read(jar: JarFile, entry: JarEntry, remapper: SimpleRemapper): ClassNode {
        val bytes = jar.getInputStream(entry).use { it.readBytes() }
        return ClassNode().also { ClassReader(bytes).accept(ClassRemapper(it, remapper), 0) }
    }

    private fun write(node: ClassNode, name: String, output: JarOutputStream) {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        node.accept(writer)
        output.putNextEntry(JarEntry(name))
        output.write(writer.toByteArray())
        output.closeEntry()
    }

    private fun copy(jar: JarFile, entry: JarEntry, output: JarOutputStream) {
        output.putNextEntry(JarEntry(entry.name))
        jar.getInputStream(entry).use { it.copyTo(output) }
        output.closeEntry()
    }

    /** Module-level Kotlin metadata: nothing on a plain JVM reads it. */
    private fun isKotlinMetadata(name: String) =
        name.endsWith(".kotlin_module") || name.endsWith(".kotlin_builtins")

    /** Generated kotlinx-serialization scaffolding, replaced by reflective reads of the stripped class. */
    private fun isSerializationHelper(name: String) =
        name.endsWith("\$\$serializer.class") || name.endsWith("\$Companion.class")

    companion object {
        /**
         * Kotlin types a data class only stores, returns and prints, which makes the JDK counterpart an
         * exact stand-in. Code that constructs one bridges through an `(Object)` cast, and this same
         * remapping rewrites that cast.
         */
        val DEFAULT_TYPE_REMAPPINGS = mapOf(
            "kotlin/uuid/Uuid" to "java/util/UUID",
            "kotlin/time/Instant" to "java/time/Instant",
        )

        private const val REPORTED_VIOLATIONS = 60
    }
}
