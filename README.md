# Klib Gradle Plugin

[中文文档](docs/README.zh-CN.md) | [English documentation](docs/README.md)

Gradle build integration for Java 8 Bukkit and Paper plugins using Klib.

```kotlin
plugins {
    id("me.kzheart.klib") version "0.2.0"
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

The plugin generates `plugin.yml`, adds the selected Klib `0.2.0` modules from Maven Central,
resolves their module closure, and builds a relocated `-all.jar` for deployment.

See the [English guide](docs/README.md) for repository setup, the complete DSL, packaging rules,
and release instructions.

Every push to `main` and every pull request runs the build, TestKit suite, and local Plugin Portal
validation. Publication is isolated in a separate workflow and only runs for the exact
`gradle-plugin-v<pluginVersion>` tag or an explicitly confirmed manual dispatch of that tag.

Licensed under the [Apache License 2.0](LICENSE).
