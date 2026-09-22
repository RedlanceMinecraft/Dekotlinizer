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
     * A constructor Kotlin generated to apply default arguments: the declared parameters, a bitmask
     * of the ones the caller left out, and a trailing `DefaultConstructorMarker` that exists only to
     * keep the two JVM signatures apart and is always passed as null.
     */
    fun isDefaultConstructor(method: MethodNode): Boolean =
        method.name == "<init>" && method.desc.endsWith(MARKER_SUFFIX)

    /**
     * Drops that trailing marker from the constructor's own descriptor. The constructor itself stays:
     * a class whose parameters all have defaults gets a generated no-argument constructor whose whole
     * body is a call to this one, so removing it would leave that call pointing at nothing. The marker
     * parameter is never read, which leaves an unused local slot behind and nothing else.
     */
    fun stripDefaultMarkerParameter(method: MethodNode) {
        if (!isDefaultConstructor(method)) return

        method.desc = withoutMarker(method.desc)
        // Synthetic constructors carry no parameter names or annotations worth keeping, and both
        // arrays are sized to the old parameter count, so they would describe the wrong ones.
        method.parameters = null
        method.visibleParameterAnnotations = null
        method.invisibleParameterAnnotations = null
        method.visibleAnnotableParameterCount = 0
        method.invisibleAnnotableParameterCount = 0
        // The marker's debug-table entry would keep naming a Kotlin type nothing else mentions.
        method.localVariables?.removeAll { it.desc.contains(DEFAULT_MARKER) }
    }

    /**
     * Retargets the calls to such a constructor — the generated no-argument constructor makes one,
     * and so does every Kotlin call site that omits an argument. The marker is pushed last, so the
     * `ACONST_NULL` right before the call is its argument; a call site that pushed anything else is
     * left alone, and [KotlinReferenceValidator] reports it rather than this quietly corrupting the
     * operand stack.
     */
    fun rewriteDefaultConstructorCalls(method: MethodNode) {
        val instructions = method.instructions
        for (insn in instructions.toArray()) {
            if (insn !is MethodInsnNode) continue
            if (insn.opcode != Opcodes.INVOKESPECIAL || insn.name != "<init>") continue
            if (!insn.desc.endsWith(MARKER_SUFFIX)) continue

            val markerPush = previousInstruction(insn) ?: continue
            if (markerPush.opcode != Opcodes.ACONST_NULL) continue

            instructions.remove(markerPush)
            insn.desc = withoutMarker(insn.desc)
        }
    }

    /** The same descriptor without its last parameter, which the caller has checked is the marker. */
    private fun withoutMarker(desc: String): String {
        val parameters = Type.getArgumentTypes(desc).dropLast(1).toTypedArray()
        return Type.getMethodDescriptor(Type.getReturnType(desc), *parameters)
    }

    /** The instruction before [insn], skipping labels, line numbers and frames. */
    private fun previousInstruction(insn: AbstractInsnNode): AbstractInsnNode? {
        var previous = insn.previous
        while (previous != null && previous.opcode < 0) previous = previous.previous
        return previous
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
    private const val DEFAULT_MARKER = "kotlin/jvm/internal/DefaultConstructorMarker"
    private const val MARKER_SUFFIX = "L$DEFAULT_MARKER;)V"
}
