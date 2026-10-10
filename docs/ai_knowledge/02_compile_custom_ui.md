# 编译系统：自定义编译器与编译交互

> 最后核对：2026-10-10
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

`ProcessorRegistration` 默认注册 `ARouterJuggAptProcessor`，不要求 custom compiler 配置或单独 JAR。它复用项目 `annotationProcessor` / `kapt` 依赖，不把 ARouter compiler 打入插件。若外部 `IJuggAptProcessor.id == "ARouter"` 仍在配置中，内置实例跳过，由旧扩展处理；迁移时只移除该扩展配置，保留其他 custom compiler。

处理器比较模块 Java/Kotlin 注解声明、历史检查点与 Gradle 生成源码基线；有相关输入时先解析项目处理器环境再判断缓存。`ARouterProcessorCompatibility.projectProcessor()` 从项目依赖的实际 JSR 269 SPI 发现处理器，生成时仅执行选中的 ARouter 集合；缺失、歧义或不完整的 SPI 会使生成失败。项目参数接受 `AROUTER_MODULE_NAME` 或 `moduleName`，其余选项原样透传，kapt 同键覆盖 Java 配置；未单独适配早期 `@Param` 等规则差异。

Project Info 的 AAR 类路径和 processor JAR 可能分别处于 Jetifier 之后、之前，不能以 `android.enableJetifier` 判断处理器已转换。`ARouterProcessorCompatibility.selectProcessor()` 对照处理器实际 Fragment 要求、APK 基线信号和本轮有效编译类路径：仅单 APK 明确只含 Support 时选择 Support，其余情况默认 AndroidX。`containsApkClass()` 的空结果也可能表示数据库不可用，不能断言 APK 没有该类；选择后仍须验证类路径含目标 Fragment。

Fragment 要求与所选目标不同才转换 processor 的独立副本，并同时替换 KAPT、javac 的 processor path 和辅助 classpath；不覆盖项目文件，不转换其他 runtime/annotation 依赖。转换后校验 Fragment 引用及 SPI，无法可靠识别或转换时生成失败。

生成阶段将相关 Kotlin 声明的 KAPT stubs 与模块 Java 源码交给 javac，生成 Java 再进入既有 Kotlin/Java 编译链；普通语言编译关闭 APT/KAPT。

检查点按模块/变体保存声明、相关常量/继承事实、项目参数、处理器及运行依赖指纹、Gradle 生成源码基线和生成源码内容摘要。library dependency 不依赖名称是否含 arouter，processor 文件读取实际内容 SHA-256。签名和生成内容均未变化时只复用源码，仍交给本轮语言编译；普通方法体变化不重跑处理器。失败不发布新检查点。删除检查同时对比当前 Gradle 基线及有效历史清单，首次增量也能发现生成类消失；删除最后一个声明不能绕过检查。完整 Gradle 构建改变基线后重新建立检查点。文件删除是否进入本轮仍取决于上游变更发现。

`failClosed` 保证处理失败或生成文件的直接语言诊断停止本轮任务。`JuggAptCompiler.isFailClosedGeneratedFile()` 查询执行生成的同一组内置/外部实例，`SourceCompiler` 不对这类文件采用移除 JuggApt 产物的重试；Kuikly 等原有 fail-open 重试继续保留。生成文件只由本实例实际使用的 checkpoint source 目录认领，不按文件名误认其他产物。注册索引沿用已有 `ArtifactRestartPolicy` 重启策略，缓存源码重新编译后也可能触发重启。

源码事实使用文本提取，并非完整 Java/Kotlin 语义：跨模块常量、复杂别名、间接继承或任意 fork 的注解/生成规则仍需真实工程验证。

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
