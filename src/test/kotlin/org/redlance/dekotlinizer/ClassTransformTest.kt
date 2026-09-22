package org.redlance.dekotlinizer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The transform against what kotlinc really emits, loaded on a class loader that has no
 * kotlin-stdlib: a surviving reference to the runtime fails here the way it would fail in a
 * consumer's JVM, rather than resolving quietly off the test classpath.
 */
class ClassTransformTest {
    @Test
    fun `a class whose parameters all have defaults keeps working`() {
        val type = plainJvm().loadClass("fixtures.AllDefaults")

        // The parameterless constructor is the one whose body calls the synthetic default constructor.
        val instance = type.getDeclaredConstructor().newInstance()
        assertEquals("anonymous", type.getMethod("getName").invoke(instance))
        assertEquals(3, type.getMethod("getCount").invoke(instance))
        assertEquals(emptyList<String>(), type.getMethod("getTags").invoke(instance))
    }

    @Test
    fun `the marker parameter is gone from every constructor`() {
        val loader = plainJvm()
        for (name in listOf("fixtures.AllDefaults", "fixtures.SomeDefaults")) {
            val constructors = loader.loadClass(name).declaredConstructors

            for (constructor in constructors) {
                assertFalse(
                    constructor.parameterTypes.any { it.name.contains("DefaultConstructorMarker") },
                    "$name still declares $constructor",
                )
            }
            // The synthetic one survives beside the full one, minus its marker: parameters + bitmask.
            assertNotNull(
                constructors.firstOrNull { it.parameterTypes.lastOrNull() == Int::class.javaPrimitiveType },
                "$name lost the constructor that applies its defaults",
            )
        }
    }

    @Test
    fun `defaults are still applied when the caller omits an argument`() {
        val type = plainJvm().loadClass("fixtures.SomeDefaults")
        val withDefaults = type.getDeclaredConstructor(
            String::class.java,
            Boolean::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        )

        // Bit 1 set means "the caller left `flag` out", which is what the default is there for.
        val instance = withDefaults.newInstance("id", false, 0b10)
        assertEquals("SomeDefaults{id, true}", instance.toString())
    }

    @Test
    fun `a data class keeps its value semantics and loses its boilerplate`() {
        val type = plainJvm().loadClass("fixtures.Plain")
        val constructor = type.getDeclaredConstructor(String::class.java, Int::class.javaPrimitiveType)

        val one = constructor.newInstance("a", 1)
        val same = constructor.newInstance("a", 1)
        val other = constructor.newInstance("b", 1)

        assertEquals(one, same)
        assertEquals(one.hashCode(), same.hashCode())
        assertFalse(one == other)
        assertEquals("a", type.getMethod("getLeft").invoke(one))

        assertTrue(type.declaredMethods.none { it.name == "copy" || it.name.startsWith("component") })
    }

    @Test
    fun `an enum resolves its constants by name`() {
        val type = plainJvm().loadClass("fixtures.Colour")
        val constants = type.enumConstants

        assertEquals(listOf("RED", "GREEN"), constants.map { (it as Enum<*>).name })
        assertEquals(constants[0], type.getMethod("valueOf", String::class.java).invoke(null, "RED"))
        assertEquals(0xFF0000, type.getMethod("getRgb").invoke(constants[0]) as Int)
        // getEntries() returns kotlin.enums.EnumEntries, so it cannot survive.
        assertTrue(type.declaredMethods.none { it.name == "getEntries" })
    }

    @Test
    fun `an object still assigns INSTANCE`() {
        val type = plainJvm().loadClass("fixtures.Singleton")
        val instance = type.getDeclaredField("INSTANCE").get(null)

        assertNotNull(instance)
        assertEquals("single", type.getMethod("getLabel").invoke(instance))
    }

    @Test
    fun `kotlin metadata does not reach the output`() {
        val loader = plainJvm()
        for (name in listOf("fixtures.AllDefaults", "fixtures.Plain", "fixtures.Colour", "fixtures.Singleton")) {
            val annotations = loader.loadClass(name).annotations.map { it.annotationClass.qualifiedName }
            assertTrue(annotations.none { it != null && it.startsWith("kotlin") }, "$name kept $annotations")
        }
    }

    @Test
    fun `a property typed on the kotlin runtime is reported rather than shipped`() {
        val bad = fixtureBytes().filterKeys { it.startsWith(UNSUPPORTED) }
        assertTrue(bad.isNotEmpty(), "the unsupported fixture was not compiled")

        val violations = bad.values.flatMap { transform(it).violations }
        assertTrue(violations.isNotEmpty(), "the surviving Lazy went unreported")
        assertTrue(violations.any { it.contains("kotlin/Lazy") }, violations.toString())
    }

    @Test
    fun `the transform is built with the remapping the task ships`() {
        assertEquals(
            mapOf(
                "kotlin/uuid/Uuid" to "java/util/UUID",
                "kotlin/time/Instant" to "java/time/Instant",
            ),
            DekotlinizeJarTask.DEFAULT_TYPE_REMAPPINGS,
        )
    }

    /** The supported fixtures, transformed, on a loader that sees the JDK and nothing else. */
    private fun plainJvm(): ClassLoader {
        val transformed = fixtureBytes()
            .filterKeys { !it.startsWith(UNSUPPORTED) }
            .mapValues { (name, bytes) ->
                val result = transform(bytes)
                assertEquals(emptyList<String>(), result.violations, "$name kept a Kotlin reference")
                result.bytes
            }

        return object : ClassLoader(ClassLoader.getPlatformClassLoader()) {
            override fun findClass(name: String): Class<*> {
                val bytes = transformed[name] ?: throw ClassNotFoundException(name)
                return defineClass(name, bytes, 0, bytes.size)
            }
        }
    }

    private fun transform(bytes: ByteArray): ClassTransform.Result =
        ClassTransform(DekotlinizeJarTask.DEFAULT_TYPE_REMAPPINGS).apply(bytes)

    /** Every compiled fixture class, by binary name. */
    private fun fixtureBytes(): Map<String, ByteArray> {
        val roots = checkNotNull(System.getProperty("dekotlinizer.fixtures")) {
            "the build did not point the test at the compiled fixtures"
        }.split(File.pathSeparator).map(::File)

        return buildMap {
            for (root in roots) {
                root.walkTopDown().filter { it.isFile && it.extension == "class" }.forEach { file ->
                    val name = file.relativeTo(root).path
                        .removeSuffix(".class")
                        .replace(File.separatorChar, '.')
                    put(name, file.readBytes())
                }
            }
        }.also { check(it.isNotEmpty()) { "no fixture classes under $roots" } }
    }

    private companion object {
        /** Fixtures that are meant to fail validation, so they never join the loadable set. */
        const val UNSUPPORTED = "fixtures.unsupported."
    }
}
