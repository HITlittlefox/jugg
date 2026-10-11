---
title: 自定义编译器
description: 说明 Jugg 自定义编译器 SPI、阶段插入点和用户可获得的扩展能力。
status: active
tags:
  - capability
  - compile
  - custom
---

# 自定义编译器

Jugg 支持通过自定义编译器 SPI 扩展内置增量编译链。自定义编译器可以在 asset、resource、source、minify、dex 等阶段前后插入逻辑，处理项目特有的生成、转换或校验需求。本页说明接入能力和常见插入点，加载与阶段边界见[自定义编译器原理](../../concepts/incremental-compile/custom-compiler.md)。

## 支持的接入方式

| 场景 | 当前支持情况 | 用户可见结果 |
|---|---|---|
| 本地 jar 自定义编译器 | 支持 | 本地扩展逻辑可进入 Jugg 编译链 |
| 远端 jar 自定义编译器 | 支持 | 远端扩展下载校验后参与编译 |
| 阶段前置处理 | 支持 | 可在内置阶段前消费或改写输入 |
| 阶段后置处理 | 支持 | 可在内置阶段后处理产物 |
| 源码编译前生成 Java | 支持项目提供的适配器 | Java/Kotlin 注解输入可交给项目处理器，生成 Java 继续参与本轮编译 |
| 扩展执行失败 | 支持 | 扩展编译逻辑抛出异常时，当前任务失败并给出可见提示 |

## 接入方式

自定义编译器以 jar 提供。扩展需要实现 `ICompilerCreator`，由它为当前编译上下文创建 `ICompiler`；Jugg 通过 SPI 加载 jar 中的实现。

项目后台只需在 `customCompilers` 配置中声明 jar 文件名、路径和 md5。路径可以是本地绝对路径、相对项目目录的路径或 HTTP / HTTPS 地址。

ARouter 的 `@Route`、`@Autowired`、`@Interceptor` 已有内置增量处理，无需下发适配器。项目需保留自己的 annotation processor 依赖及 `AROUTER_MODULE_NAME` 或 `moduleName` 参数。Jugg 比较本轮源码影响生成的注解值、字段类型和路由身份，确认变化后才收集完整模块输入、运行项目处理器；生成 Java 继续参与本轮编译。同值常量、显式默认值、等价 import/alias、普通方法体、注释和格式变化不会重新生成。已完成编译与部署的索引也不会反复加入普通轮次。

只修改路由常量时，已有常量引用分析先把使用方送入跟编。即使使用方文本未变化，Jugg 仍读取当前常量值，确实影响注解结果才重新生成。ARouter 与 Kuikly 在同一轮共用源码读取和语法分析结果。

首次没有增量生成记录时，Jugg 从匹配的 Gradle 原始 class 和生成源码恢复旧信息，支持同文件多类与嵌套类；不必先运行一次 APT 建立记录。当前可恢复标准路由、Provider/Interceptor 注册及可识别的字段注入模板。原始 class 缺失、生成物与 class 不一致、本模块或依赖已有 class/DEX 部署 overlay 或其模块身份未知，但无对应记录、未知 fork 模板、未识别的泛型或引用注入旧类型无法证明、文档生成输入无法恢复时，本轮跳过 ARouter 生成并显示 warn。当前语义解析不覆盖完整 Java/Kotlin 语言；无法解析的表达式或类型也按相同边界处理。

后续比较以最近一次有效的 Jugg 生成源码及其最小比较记录为依据。APT 成功但语言编译失败时，不混用新生成源码与旧 class；生成记录或源码不匹配时，本轮 warn 并跳过，不退回更早的 Gradle 注解值。Gradle 生成基线变化后重新恢复比较依据。

检测或完整聚合输入分析失败时，旧索引可能尚未更新；其他注解器和正常源码编译继续。信息恢复后下一轮重新判断。真实 APT 执行失败和生成源码编译错误仍停止本轮任务。生成成功但后续编译或部署失败的源码由原有待完成产物跟踪补齐，重试无需重复 APT。

Jugg 按 APK 基线选择 AndroidX 或旧 Support Fragment，必要时转换 processor JAR。两种类型都有、没有查询结果或多 APK 无法确定归属时使用 AndroidX；本轮编译类路径缺少目标 Fragment 时明确失败。删除路由等导致旧生成类消失，或生成源码无法编译时，本轮任务停止；需要完整 Gradle 构建刷新基线。路由注册索引部署沿用现有 App 重启策略。

保留 ARouter 自定义编译器配置时，Jugg 只运行该适配器；源码预处理适配器需按当前 Jugg 接口重新编译后使用。迁移到内置处理应移除该项配置，保留其他扩展。当前识别上述三种官方注解；早期 `@Param` 和改变注解或生成规则的 fork 需要核对实际结果，不能仅凭 ARouter 名称认定兼容。支持范围见[注解器](./annotation-processors.md)。

> [!NOTE]
> 自定义编译器运行在 Jugg 编译流程内，适合补充增量链路，不适合替代完整 Gradle task graph。

## 触发与结果

```text
自定义编译器配置可用
  -> 加载并校验扩展 jar
  -> 按配置插入编译阶段
  -> 参与本轮增量编译
  -> 成功产物继续交给后续阶段
```

选择插入点时，应按用户目标选择阶段，而不是按 jar 加载时机判断。

## 常见插入点

| 目标 | 推荐区间 |
|---|---|
| 最早或最后处理本轮输入和产物 | `atFirst` / `atLast` |
| assets 和 native lib 前后处理 | `beforeAsset` / `afterAsset` |
| 资源前后处理 | `beforeRes` / `afterRes` |
| Java/Kotlin 前后处理 | `beforeSource` / `afterSource` |
| 混淆前后处理 | `beforeMinify` / `afterMinify` |
| dex 前后处理 | `beforeDex` / `afterDex` |

## 相关页面

- [自定义编译器指南](../../guide/custom-compiler.md)
- [Jugg 后台项目配置下发](../../guide/jugg-backend/project-config.md)
- [源码编译](./source-compile.md)
- [自定义编译器原理](../../concepts/incremental-compile/custom-compiler.md)
