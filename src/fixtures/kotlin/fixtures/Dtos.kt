package fixtures

/**
 * Every parameter has a default, so kotlinc emits three constructors: the full one, the synthetic
 * one taking a bitmask and a `DefaultConstructorMarker`, and a parameterless one whose entire body
 * is a call to the synthetic one.
 */
class AllDefaults(
    val name: String? = "anonymous",
    val count: Int = 3,
    val tags: List<String> = ArrayList(),
) {
    override fun equals(other: Any?): Boolean =
        other is AllDefaults && other.name == this.name && other.count == this.count

    override fun hashCode(): Int = 31 * (this.name?.hashCode() ?: 0) + this.count

    override fun toString(): String = "AllDefaults{${this.name}, ${this.count}, ${this.tags}}"
}

/** Only some have defaults, so the synthetic constructor exists without a parameterless one beside it. */
class SomeDefaults(val id: String, val flag: Boolean = true) {
    override fun toString(): String = "SomeDefaults{${this.id}, ${this.flag}}"
}

/** Nothing to rewrite: value semantics, `copy` and `componentN` to drop. */
data class Plain(val left: String, val right: Int)

/** Its constants are built in `<clinit>` and resolved by name, so both have to survive. */
enum class Colour(val rgb: Int) {
    RED(0xFF0000),
    GREEN(0x00FF00),
}

/** Keeps `<clinit>`, or `INSTANCE` is never assigned. */
object Singleton {
    val label: String = "single"
}
