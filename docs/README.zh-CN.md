# Klib Gradle 插件

插件 ID：`me.kzheart.klib`
插件版本：`0.4.0`
默认 Klib 库版本：`0.3.0`
默认 Guard API 版本：`0.2.0`

插件支持两种互斥产物：普通 Java 8 Bukkit/Paper 插件，以及由 KlibGuard 门户加载的云端商品。
两种模式共用类型安全模块 DSL、Maven Central 依赖解析和确定性重定位。

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
    id("me.kzheart.klib") version "0.4.0"
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
`me.kzheart.klib:klib-<module>:0.3.0` 及其模块闭包。

## KlibGuard 云端商品

云端商品不声明 Bukkit 主类，也不包含 `plugin.yml`：

```kotlin
plugins {
    id("me.kzheart.klib") version "0.4.0"
}

group = "com.example"
version = "1.0.0"

klib {
    targetPackage("com.example.cloud")
    // 可选：通过 KlibGuard 门户与同服 TabooLib 容器双向共享 Kether action。
    ketherInterop(true)
    modules {
        core()
    }
    guardProduct {
        entrypoint("com.example.cloud.CloudExample")
        // 默认 0.2.0；只有验证特殊组合时才覆盖。
        // guardApiVersion("0.2.0")
    }
}

dependencies {
    compileOnly("org.spigotmc:spigot-api:1.12.2-R0.1-20180712.012057-156") {
        isTransitive = false
    }
}
```

`guardProduct {}` 会自动完成以下工作：

- 把 `klib-guard-api` 加入 `compileOnly`；它传递提供编译所需的 Klib Core API；
- 生成 `META-INF/klib-guard/entrypoint`，不生成 `plugin.yml`；
- 把 `core()` 视为 Guard 父加载器提供，不打包或重定位 Guard/Core/Bukkit 类；
- 选择非 Core Klib 模块时，只把这些模块及其私有依赖打入并重定位到 `targetPackage.libs`；
- 在 `check` 与 `guardProductJar` 中拒绝 Collector 不接受的路径、字节码和嵌套制品。

Guard 商品启用 `ketherInterop(true)` 时，插件会自动加入 `script()` 并生成
`META-INF/klib-guard/kether-interop.properties`。商品仍不会生成 Bukkit 主类或 `plugin.yml`；实际
OpenContainer 身份由支持 `klib-guard-kether-interop-v1` 的 KlibGuard 门户统一提供，商品通过
`GuardKetherInterop.install(root, statements, host)` 把自己的注册表绑定到当前授权代次。

执行：

```bash
./gradlew clean guardProductJar
```

上传 `build/libs/<项目>-<版本>-guard.jar` 到 KlibGuard Collector。它不能直接放入服务器的
`plugins` 目录。`remote {}` 仍表示独立的 Klib Remote 遥测配置，不是 Guard 授权配置。

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
| `ketherInterop(true)` | 普通插件生成 TabooLib 入口；Guard 商品声明门户级 Kether 互操作能力 |
| `libraryVersion(...)` | 仅在明确验证特殊版本组合时覆盖默认 Klib 版本 |
| `guardProduct { entrypoint(...) }` | 切换为 KlibGuard 云端商品并声明入口类 |
| `guardApiVersion(...)` | 在 `guardProduct {}` 内覆盖默认 Guard API 版本 |

## 打包

`implementation` 和 `runtimeOnly` 依赖会进入最终 JAR，`compileOnly` 不会。执行：

```bash
./gradlew clean shadowJar
```

部署 `build/libs/<项目>-<版本>-all.jar`，不要部署普通 JAR。
