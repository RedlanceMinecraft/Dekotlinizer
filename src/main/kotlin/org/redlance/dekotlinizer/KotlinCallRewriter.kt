package org.redlance.dekotlinizer

import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode

/** Instruction-level surgery: the calls into the Kotlin runtime that a surviving method still makes. */
internal object KotlinCallRewriter {
    /**
     * Removes the Intrinsics null checks a constructor performs, and retargets the equality helper.
     * Doing it here rather than compiling with `-Xno-assertions` keeps the checks in every other build
     * of the same sources. A pattern this misses is caught by [KotlinReferenceValidator].
     */
    fun rewriteIntrinsics(method: MethodNode) {
        val instructions = method.instructions
        val remove = mutableListOf<AbstractInsnNode>()
        for (insn in instructions.toArray()) {
            if (insn !is MethodInsnNode || insn.owner != INTRINSICS) continue
            when {
                // A void check (checkNotNullParameter and friends): the call and its arguments go.
                insn.desc.endsWith(")V") -> {
                    remove += insn
                    remove += argumentPushers(insn)
                }
                // Field comparison inside equals; java.util.Objects.equals has the same descriptor.
                insn.name == "areEqual" && insn.desc == "(Ljava/lang/Object;Ljava/lang/Object;)Z" -> {
                    insn.owner = "java/util/Objects"
                    insn.name = "equals"
                }
            }
        }
        remove.forEach(instructions::remove)
    }

    /**
     * Removes the tail of a Kotlin enum `<clinit>`: `$ENTRIES = EnumEntriesKt.enumEntries($VALUES)`.
     * The field went with the other Kotlin-typed ones, so its initialization — the call, its arguments
     * and the `PUTSTATIC` that follows — goes too. The constants and `$VALUES` above it stay untouched.
     */
    fun dropEnumEntries(method: MethodNode) {
        val instructions = method.instructions
        val remove = mutableListOf<AbstractInsnNode>()
        for (insn in instructions.toArray()) {
            if (insn !is MethodInsnNode || !insn.owner.startsWith(ENUMS)) continue
            remove += insn
            remove += argumentPushers(insn)
            var next = insn.next
            while (next != null && next.opcode < 0) next = next.next
            if (next is FieldInsnNode && next.opcode == Opcodes.PUTSTATIC) remove += next
        }
        remove.forEach(instructions::remove)
    }

    /**
     * The instructions that pushed the arguments of [call] — one per argument, walking back from the
     * call itself and skipping labels, line numbers and frames, which are not instructions.
     */
    private fun argumentPushers(call: MethodInsnNode): List<AbstractInsnNode> {
        val pushers = mutableListOf<AbstractInsnNode>()
        var arguments = Type.getArgumentTypes(call.desc).size
        var previous = call.previous
        while (previous != null && arguments > 0) {
            if (previous.opcode >= 0) {
                pushers += previous
                arguments--
            }
            previous = previous.previous
        }
        return pushers
    }

    private const val INTRINSICS = "kotlin/jvm/internal/Intrinsics"
    private const val ENUMS = "kotlin/enums/"
}
