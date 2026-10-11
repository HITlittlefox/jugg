# DataBinding 模块普通布局增量部署崩溃修复

## 用户场景

模块开启 `dataBinding=true` 且包含普通 findViewById 布局时，完整 baseline 安装一切正常；Jugg 增量部署修改过的普通布局后，进入无关页面即崩溃：基础库 DataBinding 控件 `inflate(...)` 返回 null → NPE。删除/重建 Jugg 资源缓存可临时恢复，再次增量部署后复现。

## 根因

`DataBindingArgsManager.isUseDataBinding()` 原第一行 `if (moduleInfo.isUseDataBinding == true) return true` 直接短路，使传入具体布局时「布局是否声明 `<layout>`」的判定失效。普通布局被当作 DataBinding 布局处理，触发 Binding 类与全局 `DataBinderMapper` 重新生成并热替换进 App，污染全局 Mapper，导致其它 DataBinding 布局 inflate 失败。该行为也与 [02_compile_databinding.md](../../ai_knowledge/02_compile_databinding.md) 记载的预期（普通 layout 不应触发 DataBinding mapper）不符。

## 修复方案

`DataBindingArgsManager.kt`：传入具体布局时模块开关不再短路，逐文件 `guessXmlFileHasDataBinding()` 判定是否真正含 `<layout>`；不传 `xmlFile` 的模块级查询保持原语义；Gradle kapt 输出目录探测抽成私有方法 `hasGradleDataBindingOutputDir()`。

## 验证证据

```text
./gradlew :main:test --tests "com.sickworm.intellij.jugg.compiler.databinding.DataBindingArgsManagerTest"
```

- 新增 `DataBindingArgsManagerTest`（6 用例）：核心回归为「模块开关开 + 普通 layout → 返回 false，不进 DataBinding 阶段」；另覆盖模块级语义保持、Gradle 目录回退、无开关时单布局检测、`<layout>` 布局正常进阶段、混合布局判定。全部通过。
- 真实工程验证（2026-10-11）：工程为 dataBinding、viewBinding、findViewById 混杂，基线编译后换用本 PR 构建的插件，增量修改 findViewById 的 xml 可被正确识别并走非 DataBinding 增量编译路径，部署后无关页面不再崩溃。
