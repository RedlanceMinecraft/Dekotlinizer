# Dekotlinizer

A Gradle plugin that rewrites compiled jars so they run on a plain JVM with no `kotlin-stdlib` on the
classpath. It exists for libraries whose consumer supplies neither the Kotlin runtime nor a Kotlin
JSON library — a Minecraft mod, for instance, which reads DTOs with Gson.

An internal RedlanceMinecraft project: it exists for this organization's own builds, is published
for them alone, and carries no promise of support, stability or a compatible next version for anyone
else. Use it if it helps, but do not expect it to be maintained as a product.

## What it does

Kotlin classes become POJOs with value semantics: fields, getters, a plain constructor and
`equals`/`hashCode`/`toString`. Removed along the way:

- `@kotlin.Metadata`, `kotlinx` annotations, `.kotlin_module` and `.kotlin_builtins` entries;
- `Companion` and generated `$$serializer` classes, plus the Kotlin-typed helper fields beside them;
- data-class boilerplate — `copy`, `componentN`, `write$Self` — and the synthetic constructor of the
  kotlinx deserializer, which carries `SerializationConstructorMarker`;
- `Intrinsics` null checks, with `Intrinsics.areEqual` retargeted to `java.util.Objects.equals`;
- the `kotlin.enums.EnumEntries` initialization in an enum `<clinit>`, while the constants, `values`,
  `valueOf` and `$values` stay, because a reflective reader resolves a constant by name.

A default argument stays a default argument. Kotlin compiles one into a second constructor taking a
bitmask and a `DefaultConstructorMarker`, a parameter that exists only to keep the two JVM signatures
apart and is always passed as null: the parameter goes and the constructor stays, along with the
calls to it. Dropping the constructor instead would break a class whose parameters all have defaults,
because the parameterless constructor Kotlin generates beside it does nothing but call it.

Kotlin types that a data class only stores, returns and prints are swapped for their JDK counterpart
(`kotlin.uuid.Uuid` for `java.util.UUID`, `kotlin.time.Instant` for `java.time.Instant`). A Kotlin
object keeps its `<clinit>`, so `INSTANCE` is still assigned. Java classes are copied untouched.

Afterwards the output is validated. One surviving `kotlin`/`kotlinx` reference fails the build, as
does a call the type swap left pointing at a method the JDK type does not declare.

## Usage

```kotlin
plugins {
    id("org.redlance.dekotlinizer") version "1.0.0"
}
```

Applied to a project with the `java` plugin, it registers `dekotlinize`: it reads that project's `jar`
and writes `build/dekotlinizer/<project>-plain.jar`. Configure it to add jars — bundling a shared
module's DTOs into the same artifact, for example:

```kotlin
import org.redlance.dekotlinizer.DekotlinizeJarTask

tasks.named<DekotlinizeJarTask>("dekotlinize") {
    inputJars.from(configurations.named("sdkBundle"))
    outputJar.set(layout.buildDirectory.file("sdk/my-sdk-plain.jar"))
}
```

| Property | Default | Meaning |
| --- | --- | --- |
| `inputJars` | the project `jar` | jars merged into the output, first entry of a name wins |
| `outputJar` | `build/dekotlinizer/<project>-plain.jar` | the transformed jar |
| `typeRemappings` | `Uuid`, `Instant` | Kotlin type to JDK type, as JVM internal names |
| `stripNonPublicParameterNames` | `false` | drop `MethodParameters` from non-public classes |

Enable `stripNonPublicParameterNames` when an obfuscator follows: it renames non-public classes
anyway, so a global `-keepattributes MethodParameters` would only publish their parameter names.

Run the task before obfuscation. Readable names make both the transform and its failures legible.

The plugin bundles ASM under its own package. A buildscript classpath is shared with every other
plugin applied to the same project, so an unrelocated copy would leave the transform running against
whichever ASM version happened to land first.

## Building

`./gradlew test` runs the transform over `src/fixtures`, which the Kotlin plugin compiles with the
same compiler a consumer uses — so the tests see the real output for defaults, enums, objects and
data classes rather than bytecode assembled by hand. Each transformed class is then loaded on a class
loader whose parent is the platform one: with no kotlin-stdlib in sight, a reference the transform
missed fails there the way it would fail in a consumer's JVM. `fixtures/unsupported` holds what
cannot be transformed, and asserts the build is failed rather than a broken jar shipped.

`./gradlew publishToMavenLocal` installs the plugin for local consumers; `./gradlew publish` sends it
to the RedlanceMinecraft repository, with credentials taken from `release.repo.username`/
`release.repo.password` or from `RELEASE_REPO_USERNAME`/`RELEASE_REPO_PASSWORD`.
