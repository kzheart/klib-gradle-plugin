# 原生 Kotlin 与隔离打包验证记录

验证日期：2026-10-16。对应本次未发布实现；不是对任意 Kotlin 版本或第三方反射库的兼容声明。

## 环境与命令

- Gradle 9.6.1、构建 JDK 21.0.12.1
- 独立运行时 JDK 8u504
- Kotlin JVM/stdlib/reflect/metadata 2.4.21、coroutines-core-jvm 1.10.2
- ASM/asm-commons 9.9.1

```bash
KLIB_TEST_JAVA8_HOME=/path/to/jdk8 gradle clean check validateGradlePluginPortal --no-configuration-cache
```

结果：BUILD SUCCESSFUL；93 tests，0 failures，0 errors，0 skipped。
Java 8 子进程打印 `native:default:one:java`。需要设置上述环境变量才会执行独立 Java 8 子进程；其余测试在构建 JVM 上运行。

## 已验证行为

- 不另加 Kotlin 插件即可混编 `src/main/kotlin` 与 `src/main/java`，互相调用
- 显式内嵌根的完整闭包；runtime-only 版本冲突与编译/打包版本一致，但无关 runtime-only 依赖不入包
- JVM 变体选择：协程实际包含 `Job.class`，不误选多平台 metadata 变体
- class metadata、module/package facade、optional annotations、builtins；同名 module 合并与冲突拒绝
- Java 8 成品独立运行：构造器与函数默认参数 `callBy`、泛型、集合类型、协程 `runBlocking/async/delay`
- 两个不同 targetPackage 的完整成品在两个 parent-null class loader 中同时执行；各自 Kotlin 类型不同，原始 `kotlin.Unit` 不可见
- 字段/方法/参数名 `kotlin`、`xml` 不变；普通字符串 `kotlin`、`[`、`(`、`Lkotlin;` 不变；完整类名、数组名和 callable signature 定向重定位
- multi-release Java 9 overlay 与 manifest 保留，现代 JVM 实际选择 overlay；二进制资源保留
- 单根 `externalLibrary(..., "kotlin")` 不内嵌/重定位 stdlib，并写入 Bukkit libraries
- 既有 Java Guard 行为保留；纯插件内部 Kotlin/stdlib 可打包并独立执行
- Guard 父 Klib API 的方法调用、字段访问、方法句柄及真实继承覆写若在 JVM descriptor 中暴露待私有化 Kotlin 类型则 fail-fast；构建不发布最终 JAR
- Kotlin reflect 六个内建包假设的适配校验官方发行字节 SHA-256 与唯一调用点；未知字节拒绝

另外独立审计了 4262 个官方库 class/module/builtins 条目，转换零失败；35 项类型/成员/构造器/supertype 反射探针通过。

## 成品审计

测试产生 `build/native-kotlin-fixtures/first-all.jar` 与 `second-all.jar`，各有 4295 个 class 条目。
各成品的 4294 个 base class 均不高于 major 52；另一个 class 为合法 Java 9 multi-release overlay。
结构读取全部字段/方法名称，非法成员名为 0。

- first SHA-256: `f835881280011519c39567e10f7070b4584fcf6a8f1071296fb4a4388f1207e8`
- second SHA-256: `ebc77995f9c68687b14e9e00f7d3cab9fdad076d1b9dce1ae211126e3df6e18f`

主插件所有 class 为 major 52。依赖 base class：ASM/commons major 49、metadata/stdlib/coroutines major 52、reflect major 50。版本化 overlay 与模块描述符单独处理，不误当成 Java 8 base class。

## 支持边界

- 反射适配限官方 kotlin-reflect 2.4.21 原始字节；修改过的发行包或其他版本会被拒绝，需先更新适配
- 不支持任意 compiler-plugin payload。class metadata 的已知危险扩展会拒绝；builtins/module 原样保留的深层声明并非穷尽的未知 schema 校验
- 不同非空 optional-annotation 名称表的同名 module 不可安全合并，明确拒绝
- 不保证动态生成实现（代理/SAM）、反射、擦除为 `Object` 的跨插件传值、原生代码、外部配置或不透明资源中的名称自动修复
- Guard 检测仅覆盖静态可见父 Klib 成员描述符；不改变 privateGuard 合同
- 现有 Guard Collector 禁止 multi-release，并限制 4096 条目。官方 stdlib/reflect/coroutines 原始非目录条目分别为 1007/2429/841；仅这些 runtime 的 class 就已超过 4096，且 reflect 包含 Java 9 overlay。因此该组合不能作为当前 Guard 商品发布；没有放宽宿主限制
- coroutine debug-agent instrumentation 不在已验证范围内

Minecraft 服务端启动与真实 Klib 模块验证由集成 QA 单独记录，不用此插件级 fixture 代替服务器验证。

## 依赖可用性补充验证

`DependencyAvailabilityTest` 新增四个可重复的真实 TestKit 场景。测试只使用随机端口的回环 HTTP Maven 仓库、测试进程已有的 stdlib 文件与独立 TestKit 缓存，不依赖公网制造失败；测试坐标使用 exclusiveContent，禁止其他仓库兜底。

- `cachedDependenciesRebuildOfflineWithoutRepositoryRequests`：先在线取 POM/JAR，再在没有任务输出或 configuration cache 的新项目执行 `--offline`；实际重新生成成品并检查 payload，回环请求数保持不变
- `missingOfflineDependencyFailsWithoutRepositoryRequestsOrFinalJar`：从未缓存的新坐标，诊断包含具体坐标与 `No cached version ... available for offline mode`，HTTP 请求为 0，无最终 JAR
- `unavailableDependencyReportsHttp503AndPublishesNothing`：回环端点真实返回 503；诊断包含坐标、请求路径和 503，无最终 JAR
- `slowDependencyHasAnExplicitSocketTimeoutAndPublishesNothing`：服务端接收请求后延迟 5000ms，构建显式设置 Gradle HTTP socket timeout=250ms；实际得到 `SocketTimeoutException: Read timed out`，无最终 JAR

四项首次针对性执行全部通过。原始证据位于 `build/dependency-availability-evidence/*.log`，包含请求计数、路径、配置以及完整 Gradle 输出。全部构建明确禁用 configuration/build cache，缓存命中场景只复用依赖下载缓存。

慢响应场景验证的是 Gradle 9.6.1 显式 HTTP 超时配置，不代表插件新增了默认 250ms 超时，也不宣称已验证人工取消。测试关闭 listener 与 executor，未留下外部服务。仅这些 tests/docs 变化不要求重建既有 Minecraft QA 成品。

### Guard inherited method 补充范围

父 Klib 接口/基类的覆写方法也是宿主 ABI，即使插件代码没有显式调用它。补充验证已使用 classpath 类头和方法表按 name+descriptor 解析父方法，拒绝包含私有重定位 Kotlin 类型的真实 override/implementation；包括经过本地中间类或接口的继承。不能把在宿主子类中新加的普通内部 Kotlin 方法误当成 override。真实 TestKit 覆写负例与内部方法正例及层级单测已通过。此修复仅作用于 Guard 路径，普通 Bukkit QA 成品不受影响；以本地子类作为字节码符号 owner 的继承 virtual/static 调用、字段和 Handle 也已验证；最近本地声明、字段遮蔽及不同 descriptor 不误报。最终 clean check + Portal 共 93 项测试全部通过。

最终纯文本证据统一保存在 `build/validation-evidence/`：`full-check.log`、`test-summary.txt`、`artifact-audit.txt`、`java8-runtime.log`、四份依赖可用性日志，以及 `metadata-unit-tests.log`、`metadata-realjar-audit-final.log`、`metadata-six-reflect-java8.log`、`metadata-reflect-coverage-final.log`。交付时复制该目录的文本文件，不包含构建 JAR 或缓存。
