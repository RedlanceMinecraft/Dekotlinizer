package fixtures.unsupported

/**
 * A property whose type is the Kotlin runtime. The transform cannot save it — there is no JDK stand
 * in for `Lazy` — so the only right answer is to report it and fail the build.
 */
class LeavesKotlin {
    val lazyValue: Lazy<String> = lazy { "x" }
}
