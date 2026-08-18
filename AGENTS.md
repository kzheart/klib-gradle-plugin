# Klib Gradle Plugin 协作指令

- 始终使用简体中文沟通，代码标识和配置键保留原文。
- 本仓库只维护 `me.kzheart.klib` Gradle 插件，不复制 Klib 模块或 Guard 源码。
- 插件版本与 Klib 库版本独立；默认库版本通过 `klibVersion` 配置并从 Maven Central 解析。
- 修改公共 DSL、依赖打包、重定位或发布方式时，同步更新 `docs/gradle-plugin.md`、`docs/gradle-plugin.en.md` 和 `README.md`。
- 插件必须保持 Java 8 字节码，并通过 TestKit 验证类型安全模块 DSL、依赖闭包、打包和重定位行为。
- 发布元数据中的 `website` 与 `vcsUrl` 必须指向本公共 GitHub 仓库。
- 不提交 Gradle Plugin Portal 密钥、Token、签名密钥、`.gradle/` 或 `build/`。
- 提交信息使用 Conventional Commits：`type(scope): 中文描述`。

常用验证：

```bash
./gradlew check validateGradlePluginPortal --no-configuration-cache
```
