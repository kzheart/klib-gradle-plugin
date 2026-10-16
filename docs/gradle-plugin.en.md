# Gradle plugin

The plugin applies Kotlin/JVM 2.4.21 automatically. Put Kotlin in `src/main/kotlin` and Java in
`src/main/java`; mixed-source projects compile with Java 8 bytecode and the Java 8 API baseline.
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
`Multi-Release: true`. Only base classes must target Java 8; version-specific classes may target
their declared newer JVM. Inputs containing versioned entries without an enabling manifest are
rejected. Dependency manifests, signature files, indexes, module descriptors, and dependency
`plugin.yml` files are intentionally excluded from the aggregate JAR. Ordinary binary resources
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
