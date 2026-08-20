# 更新日志

## 0.5.0 - 2026-08-21

### 破坏性变更

- 不再内嵌完整的 `runtimeClasspath`。声明在 `implementation`、`runtimeOnly` 或
  `compileOnly` 中的依赖不会再自动进入最终 JAR。
- 基础 JAR 与重定位后的候选 JAR 改为写入 `build/intermediates/klib`；只有验证成功的最终
  制品才会发布到 `build/libs`。

### 新功能

- 新增显式 `klibEmbedded` 依赖配置，用于必须重定位并打包的私有第三方库。
- 新增类型安全的数据能力选择 DSL：`data { json(); jdbc(); sqlite(); mysql() }`；单独调用
  `data()` 只选择轻量基础模块。
- 将 Spigot API、Gson 与 SQLite JDBC 视为宿主提供依赖；MySQL 驱动仅在选择 `mysql()` 时
  进入产物。
- 在 `build/reports/klib/bundle-report.txt` 生成稳定的逐依赖体积与条目统计报告。
- Guard 商品先生成中间候选包，一次汇总多个发布边界违规原因，并仅原子发布验证通过的
  商品制品。
- 默认依赖升级为 Klib `0.4.0`，Guard API 保持 `0.2.0`。

### 迁移指南

- 将所有必须打进产物的私有运行时依赖从 `implementation` 或 `runtimeOnly` 移到
  `klibEmbedded`，例如：

  ```kotlin
  dependencies {
      klibEmbedded("com.example:private-library:1.0.0")
  }
  ```

- 只选择插件实际使用的数据后端：

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

- 仅部署 `build/libs/<项目>-<版本>-all.jar`，或上传验证通过的
  `build/libs/<项目>-<版本>-guard.jar`。`build/intermediates/klib` 下的文件仅供构建内部
  使用，不应部署或上传。

## 0.4.0 - 2026-08-20

### 新功能

- Guard 商品模式支持 `ketherInterop(true)`。
- 自动生成并严格验证 Guard Kether 互操作能力标记。
- 普通 Bukkit 插件模式的 Kether 互操作行为保持不变。

### 依赖

- 默认依赖升级为 Klib `0.3.0` 与 Guard API `0.2.0`。

## 0.3.0 - 2026-08-20

### 新功能

- 新增 `guardProduct {}`，无需 Bukkit 元数据即可构建 KlibGuard 云端商品。
- 保持 Guard/Core 由父加载器提供，并选择性重定位非 Core Klib 模块与私有依赖。
- 新增 `guardProductJar` 和与 Collector 边界一致的上传前制品校验。

### 文档

- 补充 Guard 商品 DSL、依赖边界、打包任务与制品布局的中英文说明。
