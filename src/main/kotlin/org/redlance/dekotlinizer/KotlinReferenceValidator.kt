package org.redlance.dekotlinizer

import org.objectweb.asm.Handle
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.FieldNode
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.MultiANewArrayInsnNode
import org.objectweb.asm.tree.TypeInsnNode

/**
 * Reports every `kotlin`/`kotlinx` reference a transformed class still holds, plus the calls a
 * [TypeRemapping] left pointing at a method its JDK replacement does not declare. Both would only
 * surface at runtime — as `NoClassDefFoundError` and `NoSuchMethodError` — so the build fails on them.
 */
internal class KotlinReferenceValidator(private val remappings: List<TypeRemapping>) {
    fun validate(node: ClassNode): List<String> {
        val violations = Violations(node.name)
        violations.check(node.superName, "super")
        violations.check(node.signature, "class-sig")
        node.interfaces?.forEach { violations.check(it, "iface") }
        (node.visibleAnnotations.orEmpty() + node.invisibleAnnotations.orEmpty())
            .forEach { violations.check(it.desc, "class-anno") }
        node.fields?.forEach { checkField(it, violations) }
        node.methods?.forEach { checkMethod(it, violations) }
        return violations.messages
    }

    private fun checkField(field: FieldNode, violations: Violations) {
        violations.check(field.desc, "field ${field.name}")
        violations.check(field.signature, "field-sig ${field.name}")
        (field.visibleAnnotations.orEmpty() + field.invisibleAnnotations.orEmpty())
            .forEach { violations.check(it.desc, "field-anno ${field.name}") }
    }

    private fun checkMethod(method: MethodNode, violations: Violations) {
        violations.check(method.desc, "method ${method.name}")
        violations.check(method.signature, "method-sig ${method.name}")
        (method.visibleAnnotations.orEmpty() + method.invisibleAnnotations.orEmpty())
            .forEach { violations.check(it.desc, "method-anno ${method.name}") }
        (method.visibleParameterAnnotations.orEmpty().filterNotNull() +
            method.invisibleParameterAnnotations.orEmpty().filterNotNull())
            .flatten().forEach { violations.check(it.desc, "param-anno ${method.name}") }
        method.tryCatchBlocks?.forEach { violations.check(it.type, "trycatch in ${method.name}") }
        method.instructions?.forEach { checkInstruction(it, "in ${method.name}", violations) }
    }

    private fun checkInstruction(insn: AbstractInsnNode, where: String, violations: Violations) {
        when (insn) {
            is MethodInsnNode -> {
                violations.check(insn.owner, "call $where")
                violations.check(insn.desc, "call-desc $where")
                checkRemappedCall(insn, where, violations)
            }
            is FieldInsnNode -> {
                violations.check(insn.owner, "fieldref $where")
                violations.check(insn.desc, "fieldref-desc $where")
            }
            is TypeInsnNode -> violations.check(insn.desc, "type $where")
            is MultiANewArrayInsnNode -> violations.check(insn.desc, "array $where")
            is LdcInsnNode -> (insn.cst as? Type)?.let { violations.check(it.internalName, "ldc $where") }
            is InvokeDynamicInsnNode -> {
                violations.check(insn.bsm.owner, "indy-bsm $where")
                violations.check(insn.bsm.desc, "indy-bsm-desc $where")
                insn.bsmArgs?.forEach { argument ->
                    when (argument) {
                        is Type -> violations.check(argument.descriptor, "indy-arg $where")
                        is Handle -> {
                            violations.check(argument.owner, "indy-handle $where")
                            violations.check(argument.desc, "indy-handle-desc $where")
                        }
                    }
                }
            }
        }
    }

    /** A call the remapping retargeted to a JDK type that declares no such method. */
    private fun checkRemappedCall(insn: MethodInsnNode, where: String, violations: Violations) {
        val remapping = remappings.firstOrNull { it.javaType == insn.owner } ?: return
        if (remapping.javaMethods.isEmpty() || insn.name == "<init>" || insn.name in remapping.javaMethods) return
        violations.report(
            "call $where -> ${insn.owner}.${insn.name} (absent from ${insn.owner}, " +
                "a ${remapping.kotlinType} member left over by the remapping)"
        )
    }

    private class Violations(private val owner: String) {
        val messages = mutableListOf<String>()

        fun check(reference: String?, where: String) {
            if (reference != null && (reference.contains("kotlin/") || reference.contains("kotlinx/"))) {
                report("$where -> $reference")
            }
        }

        fun report(message: String) {
            messages += "$owner: $message"
        }
    }
}
