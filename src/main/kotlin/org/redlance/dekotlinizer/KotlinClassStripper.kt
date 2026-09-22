package org.redlance.dekotlinizer

import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodNode

/**
 * Reshapes a Kotlin class into a POJO with value semantics: fields, getters, a plain constructor and
 * `equals`/`hashCode`/`toString` survive, while `@kotlin.Metadata`, `kotlinx` annotations, `Companion`
 * and the data-class boilerplate (`copy`, `componentN`, `write$Self`) do not.
 */
internal object KotlinClassStripper {
    fun isKotlinClass(node: ClassNode): Boolean =
        (node.visibleAnnotations.orEmpty() + node.invisibleAnnotations.orEmpty())
            .any { it.desc == "Lkotlin/Metadata;" }

    fun strip(node: ClassNode) {
        node.visibleAnnotations = node.visibleAnnotations?.dropKotlin()
        node.invisibleAnnotations = node.invisibleAnnotations?.dropKotlin()

        // Companion plus the kotlinx helper fields ($cachedDescriptor: SerialDescriptor,
        // $childSerializers: Lazy[]); the data itself is JDK-typed and stays. `contains` rather than
        // `startsWith` also catches arrays such as [Lkotlin/Lazy;.
        node.fields?.removeAll {
            it.name == "Companion" || it.desc.contains("kotlin/") || it.desc.contains("kotlinx/")
        }
        node.fields?.forEach { field ->
            field.visibleAnnotations = field.visibleAnnotations?.dropKotlin()
            field.invisibleAnnotations = field.invisibleAnnotations?.dropKotlin()
        }

        // A Kotlin object is recognised by its self-typed static INSTANCE field, and it needs <clinit>
        // (INSTANCE = new X()) or every X.INSTANCE.getY() hits null. A data class only registers the
        // deleted Companion there, so its <clinit> goes.
        val isObject = node.fields.orEmpty().any {
            it.name == "INSTANCE" && it.desc == "L${node.name};" && it.access and Opcodes.ACC_STATIC != 0
        }
        // A Kotlin enum builds its constants in <clinit> and a reflective JSON reader resolves them
        // through valueOf(name); both have to survive, hence the decision by class flag.
        val isEnum = node.access and Opcodes.ACC_ENUM != 0

        node.methods = node.methods.orEmpty().filter { survives(it, isObject, isEnum) }.onEach { method ->
            method.visibleAnnotations = method.visibleAnnotations?.dropKotlin()
            method.invisibleAnnotations = method.invisibleAnnotations?.dropKotlin()
            method.visibleParameterAnnotations = method.visibleParameterAnnotations?.map { it?.dropKotlin() }?.toTypedArray()
            method.invisibleParameterAnnotations = method.invisibleParameterAnnotations?.map { it?.dropKotlin() }?.toTypedArray()
            KotlinCallRewriter.rewriteIntrinsics(method)
            KotlinCallRewriter.rewriteDefaultConstructorCalls(method)
            KotlinCallRewriter.stripDefaultMarkerParameter(method)
            if (isEnum) KotlinCallRewriter.dropEnumEntries(method)
        }.toMutableList()

        node.innerClasses?.removeAll { it.name.endsWith("\$Companion") || it.name.endsWith("\$\$serializer") }
    }

    /**
     * Drops `MethodParameters` from a non-public class: an obfuscator renames it anyway, so the names
     * are noise that a global keep rule would otherwise publish.
     */
    fun hideNonPublicParameterNames(node: ClassNode) {
        if (node.access and Opcodes.ACC_PUBLIC != 0) return
        node.methods?.forEach { it.parameters = null }
    }

    private fun survives(method: MethodNode, isObject: Boolean, isEnum: Boolean): Boolean = when {
        // getEntries() returns kotlin.enums.EnumEntries, so it goes before the getter rule below sees it.
        isEnum && method.name == "getEntries" -> false
        // The enum contract: values/valueOf and the synthetic $values that <clinit> stores in $VALUES.
        isEnum && (method.name == "values" || method.name == "valueOf" || method.name == "\$values") -> true
        method.name == "<clinit>" -> isObject || isEnum
        // The synthetic constructor of the kotlinx deserializer drags the runtime back in. The one
        // Kotlin generates for default arguments does not: it keeps, and loses its marker parameter
        // in the rewriter — a class whose parameters all have defaults calls it from the generated
        // no-argument constructor, which dropping it would leave pointing at nothing.
        method.name == "<init>" -> !method.desc.contains("SerializationConstructorMarker")
        (method.name.startsWith("get") || method.name.startsWith("is")) &&
            Type.getArgumentTypes(method.desc).isEmpty() -> true
        // Value semantics; Intrinsics.areEqual inside equals becomes Objects.equals in the rewriter.
        method.name == "equals" || method.name == "hashCode" || method.name == "toString" -> true
        else -> false
    }

    private fun List<AnnotationNode>.dropKotlin(): MutableList<AnnotationNode>? =
        filterNot { it.desc.startsWith("Lkotlin/") || it.desc.startsWith("Lkotlinx/") }
            .toMutableList().takeIf { it.isNotEmpty() }
}
