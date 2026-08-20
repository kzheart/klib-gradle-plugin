# 更新日志

## 0.3.0 - 2026-08-20

### 新功能

- 新增 `guardProduct {}`，无需 Bukkit 元数据即可构建 KlibGuard 云端商品。
- 保持 Guard/Core 由父加载器提供，并选择性重定位非 Core Klib 模块与私有依赖。
- 新增 `guardProductJar` 和与 Collector 边界一致的上传前制品校验。

### 文档

- 补充 Guard 商品 DSL、依赖边界、打包任务与制品布局的中英文说明。
