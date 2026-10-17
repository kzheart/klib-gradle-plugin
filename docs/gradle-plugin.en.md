# Gradle plugin

The plugin applies Kotlin/JVM 2.4.21 automatically. Put Kotlin in `src/main/kotlin` and Java in
`src/main/java`; mixed-source projects default to Java 8 bytecode and the Java 8 API baseline.
Do not apply a second, differently versioned Kotlin compiler plugin. Add `mavenCentral()` to your
project repositories (or an equivalent approved mirror).

```kotlin
plugins { id("me.kzheart.klib") version "0.7.0" }
repositories { mavenCentral() }
klib {
    main("com.example.MyPlugin")
    targetPackage("com.example.myplugin")
    modules { config() }
}
```

## Embedded runtime boundary

The final `-all.jar` contains the selected Klib modules, Kotlin stdlib 2.4.21, and the transitive
runtime closure of dependencies explicitly declared in `klibEmbedded`. Kotlin reflect and
coroutines are included when a selected Klib module needs them, or when explicitly embedded.
Ordinary `implementation`, `runtimeOnly`, and `compileOnly` dependencies are not additional
packaging roots. They can affect Gradle's shared version selection but do not silently enter the
bundle. Compile and embedded resolution use the same runtime-selected versions; JVM runtime,
JAR, and Java 8 attributes select the appropriate variants rather than multiplatform metadata.

Klib relocates `kotlin` and `kotlinx.coroutines` below `targetPackage.libs` by default, together with
its own modules. Project classes keep their names while their runtime references are rewritten.
Structured Kotlin class metadata, package facade/module indexes, service descriptors, and builtins
resources are rewritten with the runtime. Conflicting class definitions, unsupported metadata
versions, and detected unsupported extensions fail the build. This is not an exhaustive schema
validator: opaque fields nested inside builtins declarations or optional module annotations may
be preserved without interpretation. Compiler-plugin payloads and unknown extensions are unsupported.

Relocated reflection currently requires the official kotlin-reflect 2.4.21 class bytes. The
package-root adapters check SHA-256 and expected bytecode call sites and reject unrecognized
versions/shapes with an actionable error; upgrade the supported adapter before changing reflect.
Verified probes include constructor/function default arguments, collection and generic types, and
ordinary coroutines in isolated class loaders and on Java 8. This does not cover every reflect API.

This is a tested packaging boundary for the documented stdlib/reflect/coroutine combinations,
not a guarantee for every reflective third-party library. Relocation cannot automatically repair
arbitrary names stored in opaque binary resources, native code, or external configuration.
Do not expose plugin-private relocated Kotlin types through shared host/plugin APIs. Guard's
parent-provided APIs remain protected; their supported Kotlin types must be agreed with the host.
A Guard build fails early when a statically visible call, field access, method handle, or actual
override/implementation of a parent-owned Klib member contains a privately relocated Kotlin/coroutine
type in its JVM descriptor. Guard inspects class headers on the product/compile classpath to match
inherited method names and descriptors, including local intermediate classes and interfaces.
Java-only host signatures and Kotlin code using only its internal stdlib remain supported. This
check does not prove safety for erased `Object` values, dynamically generated implementations
(such as proxies or SAM implementations), reflection, or third-party APIs.
The current Guard collector still rejects multi-release entries and archives above 4096 entries:
Kotlin reflect 2.4.21 has a Java 9 resource-loader overlay, and stdlib + reflect + coroutines already
exceed that entry budget. Those Guard combinations therefore fail validation; this release does not
silently relax the host's security boundary. Coroutine debug-agent instrumentation and arbitrary
compiler-plugin binary payloads are outside the verified runtime surface.

Multi-release entries are retained and relocated, and the final manifest carries
`Multi-Release: true`. The default Java 8 target applies to base classes; version-specific classes may target
their declared newer JVM. Inputs containing versioned entries without an enabling manifest are
rejected. Dependency manifests, signature files, indexes, and module descriptors are excluded.
Generic mode ignores library `plugin.yml` files; Paper mode rejects them. Ordinary binary resources
are preserved; service files are merged and matching Kotlin module indexes are merged.

## External runtime libraries

`externalLibrary` declares a fixed Maven coordinate in Bukkit/Paper `plugin.yml` libraries,
excludes that artifact from the embedded closure, and preserves its package. Single-root packages
are supported, including Kotlin:

```kotlin
klib {
    externalLibrary("org.jetbrains.kotlin:kotlin-stdlib:2.4.21", "kotlin")
}
```

This opts out of Kotlin namespace isolation and requires a compatible server library loader.
Prepare its cache before deployment. Additional runtime artifacts such as reflect/coroutines still
need an explicit source; declaring stdlib does not promise the host provides them. External
coordinates are strict versions and must be compatible with every selected module. Guard products
reject this Bukkit-only mode. No separate Kotlin sidecar artifact is produced.

See the [English guide](README.md) for the complete module DSL. PostgreSQL is selected with
`data { postgresql() }`; its driver and JDBC service descriptor relocate together.

## Paper host and shared API gates

Opt into the Paper host policy explicitly; omitting it preserves generic packaging:

```kotlin
val apiJar = tasks.register<Jar>("apiJar") {
    from(sourceSets.main.get().output)
    include("com/example/api/**", "META-INF/*.kotlin_module")
    archiveClassifier.set("api")
}
klib {
    packaging { paper() }
    api {
        archive(apiJar.flatMap { it.archiveFile })
        packages("com.example.api")
    }
    modules { data { pool(); postgresql() } }
}
```

Default host packages are `net.kyori`, `org.bukkit`, `io.papermc`, `com.google.gson`, and `org.yaml.snakeyaml`.
Append with `hostPackages("org.example.host")`, or replace the complete list using `hostPackages.set(listOf(...))`.
Raw inputs and the final archive must not embed host packages or their children. Relocation sources and targets cannot overlap a host package, including its parents.
The mode removes matching default relocation rules and excludes known host artifacts only on selected KLib module dependency edges (Bukkit/Paper APIs, Gson, SnakeYAML, and the Adventure/Examination closure).
The complete module runtime closure remains compile-visible through `klibPaperModuleClasspath` without becoming an extra embedded root or requiring per-consumer `externalLibrary` declarations.
Explicit user `klibEmbedded` roots and their dependencies are still checked and rejected when they embed hosts. No input classes are silently stripped by package. Every multi-release layer is checked. Nested JAR/ZIP archives are rejected.
All Bukkit/Paper outputs require exactly one root `plugin.yml`, strict UTF-8, no `${...}` / `@name@` placeholders, and a version equal to `project.version`.
Paper also rejects library `plugin.yml` entries; generic mode keeps ignoring library descriptors. Paper and Guard modes cannot be combined.

`api.archive(Object provider)` accepts one raw API JAR file provider, with explicit `api.packages(String...)`.
Jar producers move into `build/intermediates/klib/api/<task>/`; successful validation promotes the candidate to `build/libs/<name>-<version>-api.jar`.
Direct input files must live outside `build/libs`. Maven publications automatically attach the verified `api` classifier; do not attach the raw API archive again.
Failed validation removes previous local final outputs and never promotes candidates. It does not remove versions already published remotely.

`verifyKlibJar`, `verifyKlibApiJar`, and existing Guard verification gate `check`, final archives, and Maven publication.
The Java component exposes the verified main artifact. Source/Javadoc archives are not API binary inputs.
API archives are not relocated. References may only target declared API packages, JDK, and agreed host types (Bukkit/Paper/Adventure by default); Kotlin runtimes, coroutines, KLib, and other implementation types are rejected.
The API host allowlist is independent of packaging exclusions. Append with `api { hostPackages("org.example.host") }`; Paper-provided Gson/SnakeYAML are not automatically allowed API types.
ASM inspects inheritance, descriptors, generic signatures, throws, annotation values, records, and full code including handles, invokedynamic, recursive constant-dynamic, frames, and locals.
The `kotlin.Metadata` and necessary nullability exceptions apply only to actual annotation descriptors. Fields, parameters, returns, generics, code, and annotation class/enum values use the ordinary reference policy.
Structured metadata uses the matching Kotlin metadata library’s JVM mapping and official stdlib typealias declarations, accepting only JDK/primitive mappings. `Cloneable`, `Comparator`, `Appendable`, and `RandomAccess` are supported; actual Kotlin runtime types such as `Pair`, `Result`, functions, `Intrinsics`, `DefaultConstructorMarker`, and coroutines remain forbidden.
Pure Kotlin interfaces can pass. Classes with default arguments or executable Kotlin code may generate runtime references and should remain implementation details.

API `.kotlin_module` files must reference API types only; use a separate API source set when a shared module index also lists implementations.
Resources are limited to manifests, license/NOTICE files, and module indexes. Unknown metadata versions, opaque compiler extensions, and optional module annotation declarations fail.
Reflection strings, generated classes, actual values erased to Object, and external configuration remain outside static guarantees. This is neither a sandbox nor proof of server compatibility.

## Optional pool and project JVM

`modules { data { pool() } }` selects `klib-data-pool → klib-data-jdbc → klib-data → klib-core`, without selecting a database driver.
Its actual DBCP2 runtime closure is resolved from the artifact POM; `org.apache.commons.dbcp2`, `org.apache.commons.pool2`, and `org.apache.commons.logging` are isolated together.
Without `pool()`, JDBC and database backends do not acquire pool dependencies. Existing explicit `klibEmbedded` rules still apply.

KLib and the Gradle plugin remain Java 8. A consumer may independently set `klib { jvmTarget(25) }` and `java { toolchain { languageVersion.set(JavaLanguageVersion.of(25)) } }`.
This controls Java release, Kotlin JVM target, and Kotlin `-Xjdk-release`; the default remains 8 and embedded library bytecode is unchanged.
