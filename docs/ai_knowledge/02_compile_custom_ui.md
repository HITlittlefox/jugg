# 编译系统：自定义编译器与编译交互

> 最后核对：2026-10-11
> 一致性规则：文档与代码冲突时，以代码为准。

---

## 1. 文档定位

本页描述两个扩展面：

- 自定义编译器如何从配置 jar 变成 `ICompiler`，以及如何插入增量编译阶段。
- 编译流程如何通过 `CompileUiHandler` 与 IDE/CLI 交互。

不展开内置编译主链，主流程见 `02_compile_core.md`；IDE 运行配置和任务调度见 `04_engineering_ide.md`。

---

## 2. 核心源码索引

| 类 | 文件 | 作用 |
|----|------|------|
| `CustomCompilerManager` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/custom/CustomCompilerManager.kt` | 接收 server 配置，解析本地/远端 jar，校验 md5，懒加载 SPI 编译器 |
| `ICompilerCreator` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/custom/ICompilerCreator.kt` | SPI 入口，为当前 `ICompileContext` 和 `Disposable` 创建一个 `ICompiler` |
| `BaseCompiler` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/BaseCompiler.kt` | 根据 `CompileOrder` 在内置阶段前后执行自定义编译器 |
| `CompileOrder` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/CompileOrder.kt` | 定义 `before/after asset/res/source/minify/dex` 与 `atFirst/atLast` 插入区间 |
| `CompileUiHandler` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/CompileUiHandler.kt` | 编译侧交互抽象，屏蔽 IDE UI、CLI 默认实现和 androidTest 事件 sink |
| `CustomConfigManager.readConfig()` | `main/src/main/java/com/sickworm/intellij/jugg/project/CustomConfigManager.kt` | 本地与默认项目配置的读取边界 |
| `CustomCompilerInfo` | `main/src/main/java/com/sickworm/intellij/jugg/server/protocols/Protocols.kt` | server 下发的 jar 名、路径和 md5 配置模型 |
| `ARouterJuggAptProcessor` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/source/apt/processors/ARouterJuggAptProcessor.kt` | 内置发现项目 ARouter 处理器、Fragment 适配、检查点和 failClosed 生成 |
| `AptSourceAnalysis`, `AptSemanticResolver` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/source/apt/` | 本轮共享只读源码、语法事实及按需常量/类型解析 |
| `AnnotationProcessorRunner`, `AnnotationProcessorClasspath`, `GeneratedSourceCheckpoint` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/source/apt/` | 共用 KAPT/APT 执行、JSR 269 SPI 和生成发布 |
| `ARouterInputSemantics`, `ARouterBaseline`, `ARouterProcessorCompatibility` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/source/apt/processors/` | ARouter 语义差异、旧产物恢复及 Fragment/Jetifier 策略 |
| `Example*CustomCompiler` | `custom_compilers/src/main/java/com/sickworm/intellij/jugg/compiler/demo/` | 示例 SPI 实现，覆盖 assemble、delay、hook init 等常见插入形态 |

---

## 3. 核心数据模型

| 对象 | 来源 | 关键语义 |
|------|------|----------|
| `CustomCompilerInfo.jarFileName` | server config | 远端 jar 下载到 `customCompilerDir` 时使用的文件名 |
| `CustomCompilerInfo.path` | server config | 可以是绝对路径、相对 `projectDir` 路径、或 `http(s)` URL |
| `CustomCompilerInfo.md5` | server config | 本地已有 jar 必须匹配；远端下载后也必须匹配，不匹配会删除 |
| `customCompilerJars` | `CustomCompilerManager` 内存状态 | 当前有效 jar 列表；废弃 jar 会从 `customCompilerDir` 清理 |
| `customCompilers` | `CustomCompilerManager` 懒加载缓存 | 首次 `getCustomCompilers()` 时通过 `ServiceLoader` 创建，`resetCompilerJars()` 后清空并下次重建 |
| `ICompiler.order` | 自定义编译器实现 | 决定被哪个 `BaseCompiler` 的 before/after hook 执行 |

---

## 4. 装载与执行链路

### 4.1 配置到 jar 状态

`JuggManager` 收到 server config 后交给 `CustomCompilerManager` 维护 jar 状态。这里不需要记住单文件内的方法顺序，只需要关注以下规则：

- `null` config 不清空旧状态；非 null 列表才会重算有效 jar。
- 本地 jar 必须存在且 md5 匹配才进入 `customCompilerJars`。
- 远端 jar 先复用缓存；缓存不存在时后台下载，下载后校验 md5。
- 下载成功或缓存变化后会清空已创建的 `customCompilers`，下次 `getCustomCompilers()` 再懒加载 SPI。

### 4.2 SPI 实例到编译阶段

```text
BaseCompileContext.customCompilers
  -> CustomCompilerManager.getCustomCompilers()
     -> URLClassLoader(customCompilerJars, current classloader)
     -> ServiceLoader.load(ICompilerCreator)
     -> creator.create(context, parent)
  -> BaseCompiler.compile(task)
     -> executeBeforeCustomCompilers(beforeCompileOrderRange, task)
        -> consumeFiles() 先过滤后续输入
        -> compile(filteredTask)
     -> 内置 doCompile(filteredTask)
     -> executeAfterCustomCompilers(afterCompileOrderRange, filteredTask, result)
        -> 把内置 outputs 转回 CompileFile 后交给自定义编译器
```

实现了 `IJuggAptProcessor` 的 custom compiler 还有源码预处理入口：`SourceCompiler.prepareSourceCompile()` 在 Kotlin/Java 编译前调用一次 `JuggAptCompiler`，它从 `context.customCompilers` 取得这类扩展。此入口不依赖 `ICompiler.order`，可对混合 Java/Kotlin 模块生成 Java 后交给既有语言编译阶段。适配器可设置 `failClosed`，使生成异常和生成源码编译错误停止本轮增量任务；普通处理器保持原降级行为；内置 ARouter 使用 `failClosed`。

### 4.3 内置 ARouter 处理

`ProcessorRegistration` 默认注册 `ARouterJuggAptProcessor`，不要求 custom compiler 配置或单独 JAR。它复用项目 `annotationProcessor` / `kapt` 依赖，不把 ARouter compiler 打入插件。若外部 `IJuggAptProcessor.id == "ARouter"` 仍在配置中，内置实例跳过，由外部扩展处理；扩展需按当前接口重新编译，迁移时只移除该扩展配置，保留其他 custom compiler。

检测只消费本模块本轮 Java/Kotlin 输入，包括已有常量/结构影响传播送来的跟编文件；先用共享文本筛选候选，并核对历史源码身份，避免最后一个注解和 import 删除漏检。`ARouterInputSemantics` 比较 Route、Autowired、Interceptor 实际生成所需的值、类型和身份；同值常量、显式默认值、别名和全限定名归一化，文档选项关闭时忽略说明性参数。普通方法体、注释、无关成员与 import 不作为触发条件。只有确认差异后才遍历模块源码并运行聚合 APT；完成产物消费后的无变化轮次返回空列表。

`JuggAptCompiler` 每模块每轮创建一个 `AptSourceAnalysis`，供 ARouter、Kuikly 和外部处理器共享源码快照；改写文件后，后续消费者必须读到失效重建的快照。`IJuggAptProcessor.process()` 使用统一四参数入口，外部扩展需同步适配并重新编译。

常量影响传播复用既有 ConstRef 链路，使用方跟编时按新常量值重新比较，即使其源码文本未变化。调用链和阶段职责见 `03_deploy_const_ref.md` §4。

`ARouterProcessorCompatibility.projectProcessor()` 从项目依赖的实际 JSR 269 SPI 发现处理器，生成时仅执行选中的 ARouter 集合；缺失、歧义或不完整的 SPI 会使生成失败。项目参数接受 `AROUTER_MODULE_NAME` 或 `moduleName`，其余选项原样透传，kapt 同键覆盖 Java 配置；未单独适配早期 `@Param` 等规则差异。

Project Info 的 AAR 类路径和 processor JAR 可能分别处于 Jetifier 之后、之前，不能以 `android.enableJetifier` 判断处理器已转换。`ARouterProcessorCompatibility.selectProcessor()` 对照处理器实际 Fragment 要求、APK 基线信号和本轮有效编译类路径：仅单 APK 明确只含 Support 时选择 Support，其余情况默认 AndroidX。`containsApkClass()` 的空结果也可能表示数据库不可用，不能断言 APK 没有该类；选择后仍须验证类路径含目标 Fragment。

Fragment 要求与所选目标不同才转换 processor 的独立副本，并同时替换 KAPT、javac 的 processor path 和辅助 classpath；不覆盖项目文件，不转换其他 runtime/annotation 依赖。转换后校验 Fragment 引用及 SPI，无法可靠识别或转换时生成失败。

`AnnotationProcessorRunner.generate()` 通过 KAPT 生成 Kotlin stubs，再交 javac 执行选定的项目处理器。产物先留在独立临时目录，完整生成成功后由 `GeneratedSourceCheckpoint.publish()` 发布，再交既有 Kotlin/Java 编译链；普通语言编译保持 APT/KAPT 关闭，DataBinding 的既有输出语义不变。

`GeneratedSourceCheckpoint` 只负责生成源码暂存发布、清单及内容校验。Gradle 生成物基线只存于 checkpoint 签名，inputs.json v3 仅保留处理器环境摘要；每个参与源码仅存 JVM 声明身份与归一化语义的 SHA-256，不再保存注解值、字段类型和泛型等完整语义副本。生成索引可恢复路由注册等结果，却无法完整恢复源码归属、精确注入类型及文档参数；最小摘要维持现有比较，不增加另一套反向语义解析。源码移动可按文件名/声明身份定位，歧义时跳过。checkpoint 签名绑定输入记录与最新生成源码内容；仅在相关判断时校验完整产物。处理器 JAR 内容和选项等环境变化可触发相关输入重新生成，普通无关轮次不扫描 processor JAR 或查询 Fragment。

尚未发生 Jugg 生成时，`ARouterBaseline` 按 SourceFile 和声明 JVM 身份恢复同文件多类/嵌套类，按有效生成物版本缓存原始 class。只遍历 class 输出，不扫描模块源码。标准七参数 RouteMeta.build、Provider 注册集合、Interceptor priority、路由参数表和已识别的 getter、cast、TypeWrapper/class literal 注入模板须与 class 匹配；缺 class、未知 fork 模板、未识别的泛型/引用注入旧类型或文档输入无法证明时不把它们当成空旧集合。没有记录且本模块或依赖已有 class/DEX 部署 overlay，或 overlay 模块身份未知时，磁盘 Gradle class 无法证明 overlay 的旧值，本轮跳过。后续比较直接消费与最新 Jugg 生成物绑定的记录，未记录的新源码贡献视为空旧集合，不再混用原 Gradle class。记录或生成物损坏时 warn 并跳过，不能静默退回原 Gradle 值；完整 Gradle 生成基线变化后才重新恢复。v2 磁盘记录先校验原签名，再将旧语义确定性转换为身份和摘要；旧版三段签名只有清单时，必须证明生成源码仍逐字节匹配原 Gradle 基线，才能沿原 class 恢复。旧清单仍用于收缩检查。纯文件删除是否进入本轮仍由上游决定。

检测、恢复和聚合输入分析失败只 warn，并跳过当前模块本轮 ARouter 生成，保留旧记录和其他处理器/正常源码编译；warn 明确注解生成物本轮可能未更新。分析失败不发布部分索引，也不缓存跨轮失败。真实 APT 执行失败、生成类收缩和生成文件直接语言诊断仍 failClosed；收缩要求完整 Gradle 构建。`SourceCompiler` 保留已生成但未完成语言编译/部署的 changed-file 跟踪，重试不因语义相同重新 APT；已消费索引也不反复加入编译。生成源码只由本实例使用的 checkpoint source 目录认领，注册索引沿用既有 App 重启策略。

该求值能力覆盖经真实 processor 验证的最小语义范围，未提供完整 Java/Kotlin 编译器符号分析；复杂 Kotlin 表达式、跨文件顶层常量定位、泛型 Provider 或改变生成规则的 fork 无法可靠判定时局部跳过。实测和支持边界见 `docs/task/2026-10/arouter_processor_capability_refactor_plan.md`。

---

## 5. 编译交互协议

`CompileUiHandler` 是编译流程唯一应该依赖的交互面。IDE、CLI、测试默认实现都通过它提供行为，编译核心不直接操作具体 UI。

| 对象 | 文件 | 用途 |
|------|------|------|
| `isForceGradleCompile` | `CompileUiHandler` | 用户强制 Gradle 编译开关 |
| `isSkipDeploy` / `isAlwaysRestartApp` | `CompileUiHandler` | 编译后的部署策略输入 |
| `createCompileStatusHolder()` | `CompileUiHandler` | 创建取消与当前文件状态对象 |
| `createOutputParser()` | `CompileUiHandler` | Gradle 编译输出解析入口 |
| `confirmBuildChanges()` / `confirmDependencyChanges()` / `confirmTooManyChanges()` | `CompileUiHandler` | 构建文件/依赖变化/源码过多时的用户确认 |
| `notifyByBalloon()` / `updateIndicatorText()` | `CompileUiHandler` | 用户可见进度提示 |
| `testEventSinkFactory` | `CompileUiHandler` | androidTest 运行时把 instrumentation 事件接到 Test Results |
| `RunResult` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/ui/RunResult.kt` | 编译/部署终态描述 |
| `BuildChangesConfirmResult` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/ui/BuildChangesConfirmResult.kt` | 构建变更确认结果 |
| `TooManyChangesConfirmResult` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/ui/TooManyChangesConfirmResult.kt` | 源码过多确认结果 |

---

## 6. 隐形约束 / 设计思路

- `updateCustomCompilers(null)` 不会清空旧配置；只有收到非 null 列表才会重算 jar 并清理废弃缓存。
- 远端 jar 下载是异步的。首次更新配置后，如果 jar 尚未存在，本轮 `getCustomCompilers()` 可能仍为空；下载成功后 `resetCompilerJars()` 会让下一轮重新加载。
- 自定义编译器异常会被 `BaseCompiler` 捕获，打印用户可见 warn，并把当前 task 收口为失败；异常不会穿透到 IDE 进程。
- before hook 可以通过 `consumeFiles()` 改写后续内置编译输入；after hook 只能看到内置产物转成的 `CompileFile`。
- `order` 必须落在具体编译器暴露的区间内才会执行。例如想处理 Java/Kotlin 编译后的产物，应使用 `CompileOrder.afterSource`，并确认目标阶段由 `JavaCompiler` / `KotlinCompiler` / `SourceCompiler` 暴露。
- `URLClassLoader` 的 parent 是 Jugg 当前 classloader；自定义 jar 可以复用 Jugg API，但要避免打包冲突版本导致类加载行为不可预期。
- `CompileUiHandler.DEFAULT` 是无 UI 的安全默认值，适合 CLI/测试，但不会弹确认或展示 Run 窗口。

---

## 7. 新增自定义编译器

源码目录、SPI 注册、打包、JAR 校验与项目配置步骤见 [custom_compilers/README.md](../../custom_compilers/README.md)；普通 hook 与源码预处理的执行契约见本页 §4。

---

## 8. 排查入口

| 现象 | 优先入口 |
|------|----------|
| 配置了 jar 但没生效 | `CustomCompilerManager.updateCustomCompiler()`，检查路径类型与 md5 |
| 远端 jar 下载成功但本轮没执行 | `downloadCompilers()` / `resetCompilerJars()`，确认是否需要下一轮 compile 重新加载 |
| `ServiceLoader` 没找到实现 | jar 内 `META-INF/services/com.sickworm.intellij.jugg.compiler.custom.ICompilerCreator` |
| 编译器执行阶段不对 | `ICompiler.order` 与 `CompileOrder` 区间，目标内置编译器的 `beforeCompileOrderRange` / `afterCompileOrderRange` |
| 自定义编译器失败导致整轮失败 | `BaseCompiler.executeBeforeCustomCompilers()` / `executeAfterCustomCompilers()` warn 日志 |
| UI 确认或取消行为与预期不一致 | 当前 `CompileUiHandler` 实现，而不是 `CompileUiHandler.DEFAULT` |

---

## 9. 关联文档

- 编译核心：`02_compile_core.md`
- IDE 执行链：`04_engineering_ide.md`
- 工程/配置：`04_engineering_project.md`
- 历史适配器、迁移及验证：`docs/task/2026-10/arouter_generic_custom_compiler_plan.md`
