# Gradle 插件

完整教程见 [中文文档](README.zh-CN.md)。PostgreSQL 通过 `data { postgresql() }` 选择，驱动和 JDBC 服务描述文件自动重定位。

0.6.1 修复了 `kotlin` 等单段包名的重定位，保留 JVM 路径与点分名称的正确分隔符，避免目标包被二次替换。Kotlin 插件需要内嵌标准库时，将标准库加入 `klibEmbedded`，并配置 `relocate("kotlin", "kotlin")`；Kotlin 编译插件仍由项目自行选择。
