---
title: 注解器
description: 说明 Jugg 当前明确支持的注解入口、Hilt 入口转换，以及哪些生成代码变化仍需 Gradle。
status: active
tags:
  - capability
  - compile
  - apt
  - kapt
  - ksp
---

# 注解器

Jugg 只对少量明确列出的注解入口提供内置增量处理，并把对应生成源码纳入本轮源码编译。对于已有 Hilt 生成物，Jugg 还能在 DEX 生成前恢复 Android 入口 class 的 Hilt 字节码形态。其他 APT、KAPT 或 KSP processor 默认交给 Gradle；项目可通过自定义编译器接入专用适配器。

## 支持范围

| 注解 / 入口 | 是否支持 | 实现方式 |
|---|---|---|
| 新增 `com.tencent.kuikly.core.annotations.Page` / `@Page` 页面 | 支持 | 基于已有路由入口补充缺失注册，并将更新后的入口加入本轮源码编译 |
| 删除、重命名或修改已有 Kuikly `@Page` 路由 | 不支持 | 不移除旧路由注册，需要由 Gradle 重新生成完整入口 |
| ARouter `@Route` / `@Autowired` / `@Interceptor` | 支持 | 本轮生成语义实际改变时运行项目处理器，必要时适配 Fragment，生成 Java 参与编译；检测失败仅 warn 并跳过，真实生成失败或生成类消失时停止 |
| 使用 KSP1 的 `com.squareup.moshi.JsonClass` / `@JsonClass` | 支持 | 通过项目 KSP1 compiler plugin 生成 Moshi adapter，并继续源码编译 |
| 使用 KSP2 的 `com.squareup.moshi.JsonClass` / `@JsonClass` | 不支持 | 不独立运行 KSP2 processor，只能继续编译 Gradle 已生成的源码 |
| [DataBinding `<layout>`](./databinding-viewbinding.md) | 支持 | 通过专用 DataBinding annotation processor 生成 mapper、BR 和绑定相关源码 |
| 使用 Hilt `2.41`～`2.60.1` 修改已有 `@AndroidEntryPoint` / `@HiltAndroidApp` 入口的普通逻辑 | 支持入口转换 | 复用最近一次 Gradle/Hilt 构建生成的 `Hilt_*` 等产物，在 DEX 前恢复父类、`super` 调用和 Receiver 注入入口 |
| 修改 Hilt 注入字段、binding、构造依赖或入口注解 | 不支持重新生成 | 不运行 Hilt/Dagger processor；需要主动执行 Gradle 构建刷新生成代码和组件图 |

> [!NOTE]
> 除上表明确列出的入口外，Jugg 不会默认独立运行其他 annotation processor。项目可以通过[自定义编译器](./custom-compiler.md)提供对应适配器；没有适配器时，相关源码需要重新生成应使用 Gradle 构建。

## 使用边界

- Kuikly `@Page` 增量处理依赖最近一次 Gradle/KSP 生成的路由入口基线，并且只补充缺失注册。删除页面、修改路由或重命名页面类时，应通过 Gradle 清理旧注册。
- ARouter 需项目 processor 依赖及 `AROUTER_MODULE_NAME` 或 `moduleName` 参数；自定义适配器仍配置时只运行该路径，适配器需按当前 Jugg 接口重新编译。Fragment 选择、迁移和已知边界见[自定义编译器](./custom-compiler.md)。
- ARouter 普通方法体、同值常量及默认值的等价写法不重新生成；首次从有效旧 class/生成源码比较。无法恢复或解析时本轮旧注解产物可能未更新，正常源码编译继续；生成类收缩仍要求完整 Gradle 构建。具体支持边界见[自定义编译器](./custom-compiler.md)。
- Moshi KSP 只在本轮 Kotlin 源码明确使用 `com.squareup.moshi.JsonClass` 且项目存在对应 KSP 依赖时触发。
- KSP2 不由 Jugg 独立运行 processor；Jugg 只能继续编译 Gradle 已经生成的源码。
- Hilt 入口转换已对照 Hilt `2.41`～`2.60.1` 的官方转换语义，并兼容 `2.41`～`2.48.1` 的 Receiver 字段标记与 `2.49` 起使用的注解标记。它只处理已有生成物对应的 Android 入口 class，不代表 Jugg 可以独立运行 Hilt/Dagger 注解处理器。普通方法逻辑变化可以增量生效；任何需要更新生成代码或组件图的修改都应先执行完整 Gradle 构建。
- 如果入口 class 对应的 `Hilt_*` 生成父类不存在或不可读，本轮编译会明确失败并提示运行完整 Gradle 构建，不会把未转换的 class 继续生成 DEX。
- 修改 processor 依赖、compiler plugin、参数或生成规则后，工程模型变化时先完成 Sync，再执行 Gradle 构建刷新生成源码基线。

## 相关页面

- [DataBinding/ViewBinding](./databinding-viewbinding.md)
- [源码编译](./source-compile.md)
- [Gradle 回退](./gradle-fallback.md)
