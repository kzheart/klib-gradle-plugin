# Klib Gradle Plugin

Plugin ID: `me.kzheart.klib`
Plugin version: `0.3.0`
Bundled Klib library version: `0.2.0`
Bundled Guard API version: `0.1.0`

The plugin supports two mutually exclusive artifacts: ordinary Java 8 Bukkit/Paper plugins and
cloud products loaded by the KlibGuard portal. Both modes share the type-safe module DSL, Maven
Central dependency resolution, and deterministic relocation.

## Quick start

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
        // Required only when item() or ui() is selected.
        maven("https://repo.codemc.io/repository/maven-public/")
    }
}
```

```kotlin
// build.gradle.kts
plugins {
    id("me.kzheart.klib") version "0.3.0"
}

group = "com.example"
version = "1.0.0"

klib {
    name("ExamplePlugin")
    main("com.example.plugin.ExamplePlugin")
    version(project.version.toString())
    noApiVersion()
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

Do not manually add Klib `implementation(...)` dependencies when using `modules {}`. The plugin
adds `me.kzheart.klib:klib-<module>:0.2.0` and its dependency closure from Maven Central.

## KlibGuard cloud products

A cloud product has no Bukkit main class and must not contain `plugin.yml`:

```kotlin
plugins {
    id("me.kzheart.klib") version "0.3.0"
}

group = "com.example"
version = "1.0.0"

klib {
    targetPackage("com.example.cloud")
    modules {
        core()
    }
    guardProduct {
        entrypoint("com.example.cloud.CloudExample")
        // Defaults to 0.1.0; override only for an explicitly tested combination.
        // guardApiVersion("0.1.0")
    }
}

dependencies {
    compileOnly("org.spigotmc:spigot-api:1.12.2-R0.1-20180712.012057-156") {
        isTransitive = false
    }
}
```

`guardProduct {}`:

- adds `klib-guard-api` as `compileOnly`; its POM exposes the Klib Core compile API;
- generates `META-INF/klib-guard/entrypoint` instead of `plugin.yml`;
- treats `core()` as parent-provided by Guard, so Guard/Core/Bukkit classes are neither packaged nor
  relocated;
- packages and selectively relocates chosen non-Core Klib modules and their private dependencies
  below `targetPackage.libs`;
- rejects paths, bytecode, and nested artifacts that the Collector release boundary does not accept.

Run:

```bash
./gradlew clean guardProductJar
```

Upload `build/libs/<project>-<version>-guard.jar` to the KlibGuard Collector. It is not a standalone
Bukkit plugin. The existing `remote {}` block still configures the separate Klib Remote telemetry
service; it is not a Guard license setting.

## Module selection

Available methods:

- `core()`, `config()`, `lang()`, `command()`;
- `item()`, `data()`, `ui()`, `script()`, `hook()`, `remote()`;
- `compat()`, `compatV1_12()`, `compatV1_20()`, `compatV1_21()`, `compatV26()`;
- `none()` when no Klib module should be included.

For example, `command()` adds `lang`, `config`, and `core`; `ui()` adds `item` and `core`.
IDE completion is available because module names are methods rather than strings.

## Main DSL

| Configuration | Purpose |
| --- | --- |
| `name(...)` | Bukkit plugin name; defaults to the Gradle project name |
| `main(...)` | Required fully qualified Bukkit main class |
| `version(...)` | Bukkit plugin version; defaults to `project.version` |
| `apiVersion(...)` / `noApiVersion()` | Configure or omit Bukkit `api-version` |
| `targetPackage(...)` | Root package used for relocation |
| `modules { ... }` | Select Klib capabilities and their dependency closure |
| `depend(...)` / `softdepend(...)` | Generate Bukkit dependency metadata |
| `relocate(source, suffix)` | Relocate an additional third-party package |
| `ketherInterop(true)` | Generate the TabooLib Kether-compatible entry point |
| `libraryVersion(...)` | Override the bundled Klib version for explicit compatibility testing |
| `guardProduct { entrypoint(...) }` | Build a KlibGuard cloud product and declare its entry point |
| `guardApiVersion(...)` | Override the bundled Guard API version inside `guardProduct {}` |

## Packaging

`implementation` and `runtimeOnly` dependencies are included in the final JAR. `compileOnly`
dependencies are not included. Run:

```bash
./gradlew clean shadowJar
```

Deploy `build/libs/<project>-<version>-all.jar`, not the ordinary JAR.
