# Changelog

## 0.5.1 - 2026-08-21

### Fixes

- Keep user-declared host APIs such as Spigot visible on `compileOnly` and `compileClasspath`, while
  continuing to exclude Spigot, Gson, and SQLite JDBC from embedded artifacts.
- Add real compilation fixtures proving host APIs remain available to source code without leaking
  into ordinary or Guard product JARs.

## 0.5.0 - 2026-08-21

### Breaking Changes

- Stop embedding the complete `runtimeClasspath`. Dependencies declared with `implementation`,
  `runtimeOnly`, or `compileOnly` are no longer copied into the final JAR automatically.
- Move the base and shaded candidate JARs to `build/intermediates/klib`. Only a successfully
  validated final artifact is published to `build/libs`.

### Features

- Add the explicit `klibEmbedded` dependency configuration for private third-party libraries that
  must be relocated and packaged.
- Add type-safe data capability selection with
  `data { json(); jdbc(); sqlite(); mysql() }`; plain `data()` now selects only the lightweight
  base module.
- Treat Spigot API, Gson, and SQLite JDBC as host-provided dependencies, while keeping the MySQL
  driver opt-in through `mysql()`.
- Generate a deterministic per-dependency size and entry report at
  `build/reports/klib/bundle-report.txt`.
- Build Guard products through an intermediate candidate, aggregate multiple release-boundary
  violations in one diagnostic, and atomically publish only verified products.
- Update the bundled dependency set to Klib `0.4.0` while retaining Guard API `0.2.0`.

### Migration

- Move every private runtime dependency that must be packaged from `implementation` or
  `runtimeOnly` to `klibEmbedded`, for example:

  ```kotlin
  dependencies {
      klibEmbedded("com.example:private-library:1.0.0")
  }
  ```

- Select only the data backends the plugin actually uses:

  ```kotlin
  klib {
      modules {
          data {
              json()
              mysql()
          }
      }
  }
  ```

- Deploy only `build/libs/<project>-<version>-all.jar` or the verified
  `build/libs/<project>-<version>-guard.jar`. Files below `build/intermediates/klib` are internal
  build inputs and must not be deployed or uploaded.

## 0.4.0 - 2026-08-20

### Features

- Add `ketherInterop(true)` support to Guard product mode.
- Generate and strictly validate the Guard Kether interoperability capability marker.
- Preserve the existing Kether interoperability behavior for ordinary Bukkit plugins.

### Dependencies

- Update the bundled defaults to Klib `0.3.0` and Guard API `0.2.0`.

## 0.3.0 - 2026-08-20

### Features

- Add `guardProduct {}` for building KlibGuard cloud products without Bukkit metadata.
- Keep Guard/Core parent-provided while selectively relocating non-Core Klib modules and private dependencies.
- Add `guardProductJar` and Collector-compatible product validation before upload.

### Documentation

- Document the Guard product DSL, dependency boundary, packaging task, and artifact layout.
