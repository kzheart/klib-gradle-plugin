# Gradle 插件

插件默认应用 Kotlin/JVM 2.4.21。Kotlin 放在 `src/main/kotlin`，Java 放在
`src/main/java`，支持混合编译，默认采用 Java 8 字节码与 Java 8 API 边界。不要再应用不同
版本的 Kotlin 编译插件；项目仓库需声明 `mavenCentral()` 或对应可信镜像。

```kotlin
plugins { id("me.kzheart.klib") version "0.8.1" }
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

Java 字符串拼接的 `StringConcatFactory` 模板中，内嵌 JVM 对象描述符也会重定位，并保留动态参数标记；普通文本常量不作子串替换。最终 Nashorn 重定位包通过隔离加载器验证数组创建和动态重编译。

支持范围以文档列出的 stdlib/reflect/协程组合和真实测试为准，不保证任意第三方反射库都可
隔离。任意二进制资源、native code 或外部配置里的类名不能自动修复。不要通过插件共享
API 暴露私有重定位 Kotlin 类型。Guard 的父加载器 API 仍受保护，跨边界 Kotlin 类型需与
宿主约定。不会生成独立 Kotlin sidecar 模块。
当前 Guard Collector 仍禁止 multi-release entries，并限制最多 4096 项。reflect 2.4.21
含 Java 9 resource-loader overlay，stdlib + reflect + 协程已有超过 4096 项，因此这些
Guard 组合会验证失败；本次不会放宽宿主安全边界。协程 debug-agent instrumentation
与任意 compiler-plugin 二进制 payload 不属于已验证运行面。

保留并重定位 multi-release entries，最终 manifest 添加 `Multi-Release: true`。默认 Java 8
目标适用于基础类，版本目录下的类可使用对应新 JVM 字节码；有版本目录却未声明启用的
输入会被拒绝。合并 JAR 时有意删除依赖 manifest、签名、索引与 module-info；通用模式忽略依赖自己的
`plugin.yml`，Paper 模式拒绝它。普通二进制资源原样保留，服务文件合并，同名 Kotlin module 索引结构化合并。

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

## Paper 宿主与共享 API 门禁

Paper 项目显式声明宿主规则；不声明时保留原有通用模式：

```kotlin
val apiJar = tasks.register<Jar>("apiJar") {
    from(sourceSets.main.get().output)
    include("com/example/api/**", "META-INF/*.kotlin_module")
    archiveClassifier.set("api")
}
klib {
    packaging {
        paper()
        // hostPackages("org.example.host") 追加；hostPackages.set(listOf(...)) 替换完整名单。
    }
    api {
        archive(apiJar.flatMap { it.archiveFile })
        packages("com.example.api")
    }
    modules { data { pool(); postgresql() } }
}
```

默认宿主包为 `net.kyori`、`org.bukkit`、`io.papermc`、`com.google.gson`、`org.yaml.snakeyaml`。
宿主包及子包不能内嵌，覆盖宿主的父包也不能重定位；同时检查原始输入与最终包，不能先重定位绕过。
这项模式移除对应默认重定位，并在所选 KLib 模块的依赖边排除已知宿主制品（Bukkit/Paper API、Gson、SnakeYAML 与 Adventure/Examination 闭包）。
完整模块 runtime 闭包通过 `klibPaperModuleClasspath` 保留编译可见性，不作为额外内嵌根；不要求消费者逐项 `externalLibrary`。
用户显式 `klibEmbedded` 根及其依赖不会因此被排除，错误宿主输入仍失败；不会按包静默丢弃输入类。multi-release 各层均检查；嵌套 JAR/ZIP 明确拒绝。
所有 Bukkit/Paper 成品要求根 `plugin.yml` 唯一、严格 UTF-8、无 `${...}` / `@name@` 占位符，版本必须等于 `project.version`。
Paper 模式还拒绝依赖携带的 `plugin.yml`；通用模式继续忽略依赖描述文件。Guard 不能与 `paper()` 同时使用。

`api.archive(Object provider)` 接受一个原始 API JAR 的文件 provider；`api.packages(String...)` 是其明确包白名单。
Jar producer 自动移到 `build/intermediates/klib/api/<task>/`，通过验证后晋升为 `build/libs/<name>-<version>-api.jar`。
直接传入文件时，文件必须位于 `build/libs` 外。声明 API 后，Maven publications 自动附加已验证的 `api` classifier；不要重复附加原始 API 包。
主包、API 包与 Maven 发布均经过门禁；主代码的 Java/Kotlin 编译、资源处理和声明的 API producer 均先执行清理，只删除本插件当前拥有的正式主包/API 包。
这些构建步骤失败也不会留下上次正式产物；成功的增量构建会重新验证并恢复正式包，不清理其他用户 JAR。失败不会晋升候选包。远端已有版本不会被本地失败删除。

`verifyKlibJar`、`verifyKlibApiJar` 和现有 Guard 验证接入 `check`、正式包与 Maven 发布。
Java component 的 outgoing artifact 指向验证后的主包。源码/Javadoc 包不属于 API 二进制检查对象。
API 不做重定位；只允许声明包、JDK 与约定宿主类型（默认 Bukkit/Paper/Adventure），禁止 Kotlin 运行时、协程、KLib及其他实现类型。
API 宿主白名单独立于打包排除规则，可用 `api { hostPackages("org.example.host") }` 追加；Gson/SnakeYAML 不会因 Paper 提供而自动成为 API 类型。
检查父类/接口、字段/方法、泛型、throws、注解及其值、record，以及完整方法体（含 handle、indy、递归 condy、局部变量和帧）。
只有真正的注解描述符位置放行 `kotlin.Metadata` 与必要空值标记；作为字段、参数、返回、泛型、代码或注解 class/enum 值时均走普通类型检查。
Metadata 结构化检查复用配套 Kotlin metadata 库的 JVM 映射与 stdlib 正式 typealias 声明，仅接受映射到 JDK/基本类型的声明，`Cloneable`、`Comparator`、`Appendable`、`RandomAccess` 等 JVM 映射可用，`Pair`、`Result`、函数类型、`Intrinsics`、`DefaultConstructorMarker` 与协程等实际运行时类型仍拒绝。
Kotlin 纯接口可以通过；含默认参数或运行时代码的类可能生成上述依赖，应把实现放在插件内部。

API 的 `.kotlin_module` 也要只引用本 API；混合实现的 module 索引会失败，宜使用独立 API source set。
API 资源限 manifest、许可证/NOTICE 和 module 索引；未知 Metadata 版本、不可检查的编译器扩展和可选模块注解声明会失败。
反射字符串、运行时生成类、擦除为 Object 的实际对象类型和外部配置不在静态保证内；验证不是沙箱，也不证明服务端兼容。

## Velocity 原生任务

原生多平台工程可只加载插件任务类型（`apply false`），让现有任务生成 Velocity 描述文件和候选包：

```kotlin
plugins { id("me.kzheart.klib") version "0.8.1" apply false }

val verifyVelocity = tasks.register<me.kzheart.klib.gradle.VerifyKlibJarTask>("verifyVelocity") {
    archiveFile.set(candidateJar.flatMap { it.archiveFile })
    velocity.set(true)
    paper.set(false)
    guardProduct.set(false)
    hostPackages.set(emptyList())
    buildVersion.set(project.version.toString())
}
```

`candidateJar` 为项目已有的候选 JAR 任务；也可接显式配置的 `KlibShadeJarTask.archiveFile` provider。
使用 `KlibShadeJarTask` 时，其 `guardProduct`、`paper` 均设 `false`，依赖与重定位沿用项目明确声明的输入；不要用 Guard 模式绕过 Bukkit 描述文件检查。
打包前检查原始描述文件 UTF-8，不接受依赖 JAR/目录携带 `velocity-plugin.json`，也不合并重复描述文件。
`velocity` 默认 `false`，只在这个验证任务上开启；不能与 Paper 或 Guard 模式混用，不生成描述文件，也不自动改变普通 `klib {}` 打包流水线。
Velocity 包必须恰有一个根 `velocity-plugin.json`，严格 UTF-8 和 JSON，`id/main/version` 是非空字符串，版本等于构建版本；重复键、未展开占位符、NUL、嵌套路径、multi-release 或大小写描述文件别名及任意 `plugin.yml` 均拒绝。
既有嵌套归档与条目边界继续生效；验证成功后再连接项目的正式包晋升与发布任务。本模式只验证制品边界，不证明 Velocity 运行兼容。

## 可选连接池与项目 JVM

`modules { data { pool() } }` 选择 `klib-data-pool → klib-data-jdbc → klib-data → klib-core`，不自动选择数据库驱动。
它解析制品声明的 DBCP2 runtime 闭包，将 `org.apache.commons.dbcp2`、`org.apache.commons.pool2`、`org.apache.commons.logging` 一并隔离。
不选 `pool()` 时，JDBC 与各 KV 驱动不会因此引入连接池；外部库仍按已有 `klibEmbedded` 规则显式选择。

KLib 和 Gradle 插件自身保持 Java 8；用户项目可独立用 `klib { jvmTarget(25) }`，并配置 `java { toolchain { languageVersion.set(JavaLanguageVersion.of(25)) } }`。
这同时设置 Java `--release`、Kotlin JVM target 与 `-Xjdk-release`；默认仍是 8，不改变嵌入的 KLib/第三方制品字节码。
