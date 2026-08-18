# Klib Gradle Plugin

Plugin ID: `me.kzheart.klib`  
Plugin version: `0.2.0`  
Bundled Klib library version: `0.2.0`

The plugin builds self-contained Java 8 Bukkit and Paper plugins. It generates `plugin.yml`,
selects Klib modules through a type-safe DSL, adds the matching Maven Central dependencies, and
shades and relocates runtime dependencies into one deployable JAR.

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
    id("me.kzheart.klib") version "0.2.0"
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

## Packaging

`implementation` and `runtimeOnly` dependencies are included in the final JAR. `compileOnly`
dependencies are not included. Run:

```bash
./gradlew clean shadowJar
```

Deploy `build/libs/<project>-<version>-all.jar`, not the ordinary JAR.

## Maintainer validation and publication

Local validation does not require Plugin Portal credentials:

```bash
./gradlew clean check validateGradlePluginPortal --no-configuration-cache
```

Server-side validation requires Portal API credentials but does not publish a version:

```bash
export GRADLE_PUBLISH_KEY='<portal-api-key>'
export GRADLE_PUBLISH_SECRET='<portal-api-secret>'
./gradlew publishPlugins --validate-only --no-configuration-cache
```

Pushes to `main` and pull requests automatically run `check`, the TestKit suite, and local Plugin
Portal validation. They never run `publishPlugins`, so an ordinary `main` push cannot republish a
version.

For a real `0.2.0` publication, create and push the exact `gradle-plugin-v0.2.0` tag. The dedicated
workflow verifies the tag against `pluginVersion`, rejects SNAPSHOT versions, checks that both
Portal secrets exist, and only then runs `publishPlugins`:

```bash
git tag gradle-plugin-v0.2.0
git push origin gradle-plugin-v0.2.0
```

The same workflow supports a recovery-only manual dispatch. Select the existing
`gradle-plugin-v0.2.0` tag and type the exact confirmation `publish`; the workflow checks out that
tag rather than publishing the selected branch. Configure `GRADLE_PUBLISH_KEY` and
`GRADLE_PUBLISH_SECRET` as secrets in the `plugin-portal` GitHub environment. Adding an environment
approval rule provides an additional human gate. The Gradle release gate independently verifies
the version, tag at `HEAD`, clean checkout, confirmation, and credential presence before the Portal
upload task can execute.

Never commit Portal credentials. The public source and documentation live at
[github.com/kzheart/klib-gradle-plugin](https://github.com/kzheart/klib-gradle-plugin).
