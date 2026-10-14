# ARouter KSP 环境 fixture

来源：用户提供的 `arouter-environment-bundle.zip`，经 `aigc-bot-client/arouter-ksp-reproduction` 复现工程归档。

- 保留真实 ARouter KSP provider/collector 源码、`Route.app` 与八参数 `RouteMeta.build` 定义；仅注释已统一为英文。
- 固定 Kotlin 2.1.20、KSP 2.1.20-1.0.32、KotlinPoet 1.18.1；同一 provider 分别运行 KSP1/KSP2。
- `ARouterKspFixture` 在测试 JVM 中编译 annotation/provider JAR。依赖由 `main/build.gradle` 的测试 configuration 解析；无需 Android 工程 assemble、外部 demo 路径或环境变量。
- `test-providers/UnrelatedProvider.kt` 是额外的执行即失败 provider；与真实 provider 共用 SPI/classpath，保护仅执行所选 ARouter 的边界。
- L1：`KspProcessorRunnerTest` 验证生成、参数、可执行路由、失败恢复和旧输出隔离。
- L2：`ARouterJuggAptProcessorTest.projectKsp*` 验证真实双引擎接入、commonMain/androidMain 完整聚合、首轮 Gradle 基线比较与 checkpoint 保留。

fixture 构建产物在 `main/build/ksp-fixture`，每个测试临时目录保存其生成物。仓库不会把该处理器打入 Jugg 插件。
