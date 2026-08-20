# Klib Gradle Plugin

[中文文档](docs/README.zh-CN.md) | [English documentation](docs/README.md)

Gradle build integration for Java 8 Bukkit/Paper plugins and KlibGuard cloud products.

```kotlin
plugins {
    id("me.kzheart.klib") version "0.3.0"
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

For ordinary Bukkit plugins, the plugin generates `plugin.yml`, resolves selected Klib `0.2.0`
modules, and builds a relocated `-all.jar`. For KlibGuard products, `guardProduct {}` generates the
cloud entrypoint, keeps Guard/Core parent-provided, selectively relocates private modules, and
builds a Collector-validated `-guard.jar`.

See the [English guide](docs/README.md) for repository setup, the complete DSL, and packaging rules.

Licensed under the [Apache License 2.0](LICENSE).
