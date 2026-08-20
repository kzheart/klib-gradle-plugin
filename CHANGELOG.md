# Changelog

## 0.4.0 - 2026-08-20

- Guard 商品模式支持 `ketherInterop(true)`。
- 自动生成并验证严格的 Guard Kether 互操作能力标记。
- 默认依赖升级为 Klib `0.3.0` 与 Guard API `0.2.0`。
- 普通 Bukkit 插件模式的 Kether 互操作行为保持不变。

## 0.3.0 - 2026-08-20

### Features

- Add `guardProduct {}` for building KlibGuard cloud products without Bukkit metadata.
- Keep Guard/Core parent-provided while selectively relocating non-Core Klib modules and private dependencies.
- Add `guardProductJar` and Collector-compatible product validation before upload.

### Documentation

- Document the Guard product DSL, dependency boundary, packaging task, and artifact layout.
