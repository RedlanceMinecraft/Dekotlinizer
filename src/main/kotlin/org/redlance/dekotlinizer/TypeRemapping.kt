package org.redlance.dekotlinizer

/** A Kotlin type replaced by a JDK counterpart, both as JVM internal names. */
internal class TypeRemapping(val kotlinType: String, val javaType: String) {
    /**
     * Method names the replacement actually declares. A call that survives the swap without being one
     * of them is a `NoSuchMethodError` waiting to happen, so [KotlinReferenceValidator] rejects it.
     * Stays empty — which turns that check off instead of rejecting every call — when the replacement
     * is not on this classpath, as it is then not a JDK type this build can reason about.
     */
    val javaMethods: Set<String> = runCatching {
        Class.forName(javaType.replace('/', '.')).methods.mapTo(HashSet()) { it.name }
    }.getOrDefault(emptySet())
}
