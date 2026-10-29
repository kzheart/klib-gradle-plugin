# Klib Gradle Plugin

[中文文档](docs/README.zh-CN.md) | [English documentation](docs/README.md)

Gradle build integration with default Kotlin/JVM 2.4.21 and Java 8 output for Bukkit/Paper plugins and KlibGuard cloud products.

```kotlin
plugins {
    id("me.kzheart.klib") version "0.8.1"
}
repositories {
    maven("https://maven.kzheart.me/releases") { content { includeGroup("me.kzheart.klib") } }
    mavenCentral()
}

klib {
    main("com.example.plugin.ExamplePlugin")
    targetPackage("com.example.plugin")
    modules {
        command()
        hook()
    }
}
```

For ordinary Bukkit plugins, the plugin generates `plugin.yml`, resolves selected Klib `0.19.0`
modules, and builds a relocated `-all.jar`. For KlibGuard products, `guardProduct {}` generates the
cloud entrypoint, keeps Guard/Core parent-provided, selectively relocates private modules, and
builds a Collector-validated `-guard.jar`. Guard products may also use `ketherInterop(true)` to
declare the portal-brokered Kether protocol without adding a Bukkit main class or `plugin.yml`.
The selected Klib modules and Kotlin stdlib are explicit embedded roots. Only their runtime closure
and dependencies declared in `klibEmbedded` enter the final JAR; ordinary `implementation` and
`runtimeOnly` dependencies are never bundled implicitly.

Kotlin and Java sources compile together by default. Kotlin runtime namespaces, structured metadata,
module indexes, and builtins are relocated below `targetPackage.libs`; multi-release resources are
preserved. Reflection adapters are verified against official kotlin-reflect 2.4.21; unsupported
versions/shapes fail. Guard rejects statically visible parent-API calls and overrides with private Kotlin types,
while Java host signatures and internal stdlib use remain supported. See [runtime packaging and its limits](docs/gradle-plugin.en.md).

StringConcatFactory recipes also relocate embedded JVM object descriptors while retaining argument markers; final shaded Nashorn array execution is verified in an isolated class loader.

See the [English guide](docs/README.md) for repository setup, the complete DSL, and packaging rules.

Licensed under the [Apache License 2.0](LICENSE).

Select PostgreSQL with `modules { data { postgresql() } }`, or MySQL with `data { mysql() }`. Each backend includes its JDBC driver; PostgreSQL classes and JDBC service metadata are relocated together.

## 外部运行时依赖

`klib { externalLibrary("org.postgresql:postgresql:<pgjdbc-version>", "org.postgresql") }` 声明固定 Maven 坐标到 Bukkit/Paper `plugin.yml` 的 `libraries`，从内嵌依赖中排除该制品，并停止该包的重定位。要求宿主支持库加载器，部署前准备缓存；Klib 模块仍分别内嵌和隔离。Velocity 和 Guard 商品通过各自宿主提供依赖。

Paper projects can opt into `klib { packaging { paper() } }`: known host dependencies on KLib module edges remain compile-visible and are excluded from the embedded closure; explicit host inputs and host relocation rules are rejected.
Declare a shared API using `api { archive(apiJar.flatMap { it.archiveFile }); packages("com.example.api") }`;
`check`, final archives, and Maven publication verify its full bytecode and Kotlin metadata boundary.
Candidates only become final JARs after validation. Previous owned final JARs are cleared before compilation and API producers, including failed builds. See [Paper and API rules](docs/gradle-plugin.en.md#paper-host-and-shared-api-gates).

`modules { data { pool() } }` adds the optional DBCP2 pool with isolated transitive dependencies; JDBC and drivers remain pool-free unless selected.
Consumer JVM targets are independent: `jvmTarget(25)` plus a Java 25 toolchain leaves KLib and this plugin on Java 8.

Native Velocity builds can register `VerifyKlibJarTask` directly with `velocity.set(true)`; this verifies a unique root `velocity-plugin.json` without introducing Bukkit or Guard entrypoints. See the [native task example](docs/gradle-plugin.en.md#native-velocity-tasks).
