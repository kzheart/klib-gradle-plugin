# Gradle 插件

插件默认应用 Kotlin/JVM 2.4.21。Kotlin 放在 `src/main/kotlin`，Java 放在
`src/main/java`，支持混合编译，并统一 Java 8 字节码与 Java 8 API 边界。不要再应用不同
版本的 Kotlin 编译插件；项目仓库需声明 `mavenCentral()` 或对应可信镜像。

```kotlin
plugins { id("me.kzheart.klib") version "0.7.0" }
repositories { mavenCentral() }
klib {
    main("com.example.MyPlugin")
    targetPackage("com.example.myplugin")
    modules { config() }
}
```

## 内嵌运行时边界

最终 `-all.jar` 包含所选 Klib 模块、Kotlin stdlib 2.4.21，以及 `klibEmbedded` 显式根依赖
的传递 runtime 闭包。所选模块需要 reflect/协程时自动随闭包进入，也可显式声明内嵌。
普通 `implementation`、`runtimeOnly` 和 `compileOnly` 依赖不会成为额外打包根；它们可以
影响 Gradle 的统一版本选择，但不会被悄悄全量打入。编译与内嵌解析遵循 runtime 图选择的
相同版本，配置 JVM runtime、JAR 与 Java 8 attributes，避免选到 multiplatform metadata。

默认将 `kotlin`、`kotlinx.coroutines` 和 Klib 重定位到 `targetPackage.libs`。项目类名保持
不变，运行时引用同步重写。Kotlin class metadata、package facade/module 索引、服务描述
与 builtins 资源使用结构化转换。冲突的类定义、无法安全处理或不支持的 metadata 会导致
构建失败，避免产生只转换了一半的 JAR；不承诺支持任意 compiler-plugin payload 或模块扩展。

支持范围以文档列出的 stdlib/reflect/协程组合和真实测试为准，不保证任意第三方反射库都可
隔离。任意二进制资源、native code 或外部配置里的类名不能自动修复。不要通过插件共享
API 暴露私有重定位 Kotlin 类型。Guard 的父加载器 API 仍受保护，跨边界 Kotlin 类型需与
宿主约定。不会生成独立 Kotlin sidecar 模块。
当前 Guard Collector 仍禁止 multi-release entries，并限制最多 4096 项。reflect 2.4.21
含 Java 9 resource-loader overlay，stdlib + reflect + 协程已有超过 4096 项，因此这些
Guard 组合会验证失败；本次不会放宽宿主安全边界。协程 debug-agent instrumentation
与任意 compiler-plugin 二进制 payload 不属于已验证运行面。

保留并重定位 multi-release entries，最终 manifest 添加 `Multi-Release: true`。Java 8
限制适用于基础类，版本目录下的类可使用对应新 JVM 字节码；有版本目录却未声明启用的
输入会被拒绝。合并 JAR 时有意删除依赖 manifest、签名、索引、module-info 及依赖自己的
`plugin.yml`。普通二进制资源原样保留，服务文件合并，同名 Kotlin module 索引结构化合并。

## 外部运行时依赖

`externalLibrary` 把固定 Maven 坐标声明到 Bukkit/Paper `plugin.yml` 的 `libraries`，从
内嵌闭包排除该制品，并保护指定包免于重定位；现在支持 `kotlin` 这样的单段根包：

```kotlin
klib {
    externalLibrary("org.jetbrains.kotlin:kotlin-stdlib:2.4.21", "kotlin")
}
```

该配置退出 Kotlin 命名空间隔离，要求服务端具备兼容 library loader，部署前应准备缓存。
声明 stdlib 不表示宿主也提供 reflect/协程，额外运行库仍须明确提供。外部版本为 strict
版本，必须与所选模块兼容。Guard 商品拒绝这个仅适用于 Bukkit 的模式。

完整 DSL 见[中文教程](README.zh-CN.md)。PostgreSQL 通过 `data { postgresql() }` 选择，
驱动和 JDBC 服务描述文件自动重定位。

已知不支持的版本和检测到的扩展会失败；这不是所有 protobuf 层级的完整 schema 校验器。builtins 声明或 module 可选注解内的深层不透明字段可能被原样保留，不能据此认为任意 compiler-plugin payload 已获支持。

## 已验证的反射与 Guard 边界

私有重定位反射仅支持官方 kotlin-reflect 2.4.21 字节码；内建包根适配同时校验 SHA-256 和调用点形状，未知版本或形状直接失败，需先更新适配再升级 reflect。验证涵盖构造器/函数默认参数、集合/泛型类型及普通协程，不承诺任意反射 API。

Guard 对调用、字段访问、方法句柄和真实的继承覆写/接口实现做静态检测：若目标为父加载器提供的 Klib 成员，且 JVM 描述符包含将私有重定位的 Kotlin/协程类型，则在成品发布前失败。仅 Guard 读取产品及 compile classpath 的类头/方法表，按 name+descriptor 匹配父方法，支持本地中间类和接口；新增内部 Kotlin 方法不会仅因继承 Java 宿主类而被拒绝。Java 宿主签名和仅在插件内部使用 stdlib 的 Kotlin 代码仍支持；擦除为 Object 的传值、动态生成实现（例如代理或 SAM）、反射及其他第三方 API 不在此静态保证内。既有 Collector 的 4096 条目和禁止多版本资源规则保持不变。
