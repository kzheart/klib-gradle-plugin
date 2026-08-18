# Klib Gradle 插件

插件 ID：`me.kzheart.klib`  
插件版本：`0.2.0`  
默认 Klib 库版本：`0.2.0`

插件用于构建 Java 8 Bukkit/Paper 插件：生成 `plugin.yml`，通过类型安全 DSL 选择模块，从
Maven Central 加入对应依赖及其闭包，并把运行时依赖重定位进一个可部署的 JAR。

## 快速开始

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
        // 只在选择 item() 或 ui() 时需要。
        maven("https://repo.codemc.io/repository/maven-public/")
    }
}
```

```kotlin
// build.gradle.kts
plugins {
    id("me.kzheart.klib") version "0.2.0"
}

group = "com.example"
version = "1.0.0"

klib {
    name("ExamplePlugin")
    main("com.example.plugin.ExamplePlugin")
    version(project.version.toString())
    noApiVersion() // 支持 1.12.2 时不生成 api-version
    targetPackage("com.example.plugin")
    modules {
        command()
        hook()
    }
    softdepend("Vault", "PlaceholderAPI")
}

dependencies {
    compileOnly("org.spigotmc:spigot-api:1.12.2-R0.1-20180712.012057-156") {
        isTransitive = false
    }
}
```

使用 `modules {}` 后不要手写 Klib `implementation(...)`。插件会从 Maven Central 自动加入
`me.kzheart.klib:klib-<module>:0.2.0` 及其模块闭包。

## 模块选择

- `core()`、`config()`、`lang()`、`command()`；
- `item()`、`data()`、`ui()`、`script()`、`hook()`、`remote()`；
- `compat()`、`compatV1_12()`、`compatV1_20()`、`compatV1_21()`、`compatV26()`；
- 完全不选择 Klib 模块时使用 `none()`。

例如，`command()` 自动补入 `lang`、`config`、`core`，`ui()` 自动补入 `item`、`core`。模块是
类型安全方法，因此 Kotlin DSL 可以补全，拼写错误也会在构建脚本编译时失败。

## 主要 DSL

| 配置 | 用途 |
| --- | --- |
| `name(...)` | Bukkit 插件名，默认使用 Gradle 项目名 |
| `main(...)` | 必填的 Bukkit 主类全限定名 |
| `version(...)` | Bukkit 插件版本，默认使用 `project.version` |
| `apiVersion(...)` / `noApiVersion()` | 设置或省略 Bukkit `api-version` |
| `targetPackage(...)` | Klib 和内嵌库的重定位根包 |
| `modules { ... }` | 选择 Klib 模块并自动解析依赖闭包 |
| `depend(...)` / `softdepend(...)` | 生成 Bukkit 依赖元数据 |
| `relocate(source, suffix)` | 重定位额外的第三方包 |
| `ketherInterop(true)` | 生成 TabooLib Kether 兼容入口 |
| `libraryVersion(...)` | 仅在明确验证特殊版本组合时覆盖默认 Klib 版本 |

## 打包

`implementation` 和 `runtimeOnly` 依赖会进入最终 JAR，`compileOnly` 不会。执行：

```bash
./gradlew clean shadowJar
```

部署 `build/libs/<项目>-<版本>-all.jar`，不要部署普通 JAR。
