package org.redlance.dekotlinizer

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.SimpleRemapper
import org.objectweb.asm.tree.ClassNode

/**
 * One class through the whole transform: the type remapping, the Kotlin strip and the validation
 * that nothing of the runtime was left behind. [DekotlinizeJarTask] runs it over every entry of the
 * input jars, and the tests run it over a class the Kotlin compiler has just produced, so what is
 * tested is what ships.
 */
internal class ClassTransform(
    typeRemappings: Map<String, String>,
    private val stripNonPublicParameterNames: Boolean = false,
) {
    private val remapper = SimpleRemapper(Opcodes.ASM9, typeRemappings)
    private val validator = KotlinReferenceValidator(
        typeRemappings.map { (kotlinType, javaType) -> TypeRemapping(kotlinType, javaType) },
    )

    /** The rewritten class, plus every Kotlin reference it still holds — a build fails on those. */
    fun apply(bytes: ByteArray): Result {
        val node = ClassNode().also { ClassReader(bytes).accept(ClassRemapper(it, remapper), 0) }

        if (KotlinClassStripper.isKotlinClass(node)) KotlinClassStripper.strip(node)
        if (stripNonPublicParameterNames) KotlinClassStripper.hideNonPublicParameterNames(node)

        val violations = validator.validate(node)

        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        node.accept(writer)
        return Result(node.name, writer.toByteArray(), violations)
    }

    class Result(val internalName: String, val bytes: ByteArray, val violations: List<String>)
}
