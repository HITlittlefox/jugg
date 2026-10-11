# resources.arsc 变更退回全量资源重建

## 用户场景

排查 DataBinding 模块增量部署崩溃（另见 [databinding_plain_layout_hot_deploy_crash.md](databinding_plain_layout_hot_deploy_crash.md)）过程中发现的同类资源状态一致性隐患：resource.ap_ 增量更新时对 `resources.arsc` 只做部分 entry 覆盖，可能使 overlay apk 与全量资源集错位，诱发与本次崩溃同类的页面资源异常。

## 根因

`ResourceApkGenerator.getResourceApkDeployItem()` 增量生成 resource.ap_ 时按单 entry 补齐；但 `resources.arsc` 是整张资源表，部分覆盖会使生成的资源 apk 与全量资源集不一致。

## 修复方案

`ResourceApkGenerator.kt`：变更集合含 `resources.arsc` 时禁止增量，退回全量重建分支；debug 日志增加 `contentServiceResourceTable` 字段。

## 验证证据

```text
./gradlew :main:test --tests "com.sickworm.intellij.jugg.deploy.data.ResourceApkGeneratorTest"
```

- `ResourceApkGeneratorTest` 新增用例 `testResourceApkFallsBackToFullRebuildWhenResourceTableChanged`：基线建成后，变更集含 `resources.arsc` 必须再次走全量重建而非增量 patch。

## 未完成的验证

- 未在真机验证 arsc 变更场景的实际部署表现；以单测覆盖重建分支判定作为替代证据。
