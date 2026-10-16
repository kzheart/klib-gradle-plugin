# Klib Gradle Plugin

[中文文档](docs/README.zh-CN.md) | [English documentation](docs/README.md)

Gradle build integration with default Kotlin/JVM 2.4.21 and Java 8 output for Bukkit/Paper plugins and KlibGuard cloud products.

```kotlin
plugins {
    id("me.kzheart.klib") version "0.7.0"
}
repositories { mavenCentral() }

klib {
    main("com.example.plugin.ExamplePlugin")
    targetPackage("com.example.plugin")
    modules {
        command()
        hook()
    }
}
```

For ordinary Bukkit plugins, the plugin generates `plugin.yml`, resolves selected Klib `0.18.0`
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
versions/shapes fail. Guard rejects statically visible parent-API calls with private Kotlin types,
while Java host signatures and internal stdlib use remain supported. See [runtime packaging and its limits](docs/gradle-plugin.en.md).

See the [English guide](docs/README.md) for repository setup, the complete DSL, and packaging rules.

Licensed under the [Apache License 2.0](LICENSE).

Select PostgreSQL with `modules { data { postgresql() } }`, or MySQL with `data { mysql() }`. Each backend includes its JDBC driver; PostgreSQL classes and JDBC service metadata are relocated together.

## 外部运行时依赖

`klib { externalLibrary("org.postgresql:postgresql:<pgjdbc-version>", "org.postgresql") }` 声明固定 Maven 坐标到 Bukkit/Paper `plugin.yml` 的 `libraries`，从内嵌依赖中排除该制品，并停止该包的重定位。要求宿主支持库加载器，部署前准备缓存；Klib 模块仍分别内嵌和隔离。Velocity 和 Guard 商品通过各自宿主提供依赖。
