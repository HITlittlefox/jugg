---
title: MCP 工具
description: 汇总 Jugg MCP 服务信息、公开工具、返回结构、异步行为和错误码。
status: active
tags:
  - reference
  - mcp
---

# MCP 工具

本页是 Jugg MCP 工具名、输入输出和公开可用性的参考入口。需要判断工作流时先看能力页；需要确认具体工具契约时看这里。

运行时权威来源：MCP `tools/list`。

## 服务信息

| 项 | 值 |
|---|---|
| 端口范围 | `12320..12329` |
| HTTP 路径 | `/jugg-mcp` |
| 协议 | JSON-RPC `2.0` |
| 支持请求头 | `MCP-Protocol-Version`，支持 `2025-06-18`、`2025-11-25` |

## 返回结构

`tools/call` 的 `structuredContent` 统一包含以下字段：

```json
{
  "status": "OK|ERROR",
  "message": "string",
  "data": {},
  "artifacts": [],
  "errorCode": "string|null"
}
```

编译类工具可能返回 `isFinal=false` 和 `jobId`，此时需要继续调用 `get-compile-status` 轮询终态。

## 公开工具

当前注册的公开 MCP tool 共 18 个。

| Tool | 主要参数 | 用途 |
|---|---|---|
| `version` | 无 | 返回 Jugg 插件版本。 |
| `list-projects` | 无 | 列出当前 IDE 已初始化项目。 |
| `restart` | `projectDir`、`waitAppReadyAfterSuccess` | 重启目标 App；没有 launch/HOME Activity 时只执行 `am force-stop`。 |
| `compile` | `projectDir` | 仅编译不部署。 |
| `deploy` | `projectDir`、`alwaysRestartApp`、`waitAppReadyAfterSuccess` | 编译并部署。 |
| `clean-reinstall` | `projectDir`、`waitAppReadyAfterSuccess` | 清数据并重装 APK。 |
| `gradle-build` | `projectDir`、`waitAppReadyAfterSuccess` | 强制 Gradle 构建并走后续安装/启动链路。 |
| `instrument` | `projectDir`、`sourcePath`、`class`、`method`、`runner`、`extras` | 从 androidTest 源文件锚点运行测试。 |
| `get-compile-status` | `projectDir`、`jobId`、`waitTimeoutMs` | 查询异步编译任务状态。 |
| `ssh-info` | `projectDir`、`reason`、`requestedBy` | 申请远端 SSH 排障信息。 |
| `devices` | `projectDir` | 列出设备并标记 selected。 |
| `layout-dump` | `projectDir`、`mode`、`rootLayout`、`includeGone`、`allWindows` | 导出 interaction/ui 稀疏 JSON。 |
| `view-locate` | `projectDir`、`target` | 查找 UI 元素位置。 |
| `view-inspect` | `projectDir`、`target`、`expressions` | 反射读取 View 只读属性，含 getter、Kotlin property 和 public 字段。 |
| `activity-stack` | `projectDir` | 读取 Activity 栈。 |
| `tap` | `projectDir`、坐标/百分比/元素 selector | 执行 tap、long-press 或 swipe。 |
| `status` | `projectDir`、`refreshChanges` | 查询部署状态和未编译文件摘要。 |
| `wait-logs` | `projectDir`、`marker`、`tags`、`timeoutMs` | 等待 App 日志 marker、crash 或 timeout。 |

`version` 和 `list-projects` 不需要 `projectDir`。其他工具都需要项目绝对路径。

## 编译类异步行为

`deploy`、`gradle-build`、`instrument` 可能先返回运行中状态：

```json
{
  "data": {
    "isFinal": false,
    "jobId": "..."
  }
}
```

客户端应使用：

```json
{
  "projectDir": "/path/to/project",
  "jobId": "...",
  "waitTimeoutMs": 5000
}
```

调用 `get-compile-status`，直到 `data.status` 为 `success`、`failed`、`canceled` 或 `unknown`。终态会返回 `isCompileSuccess` 和 `isDeploySuccess`；失败时可能附带 `detail`、`detailLength`、`detailTruncated`。

## UI 工具行为

| 工具 | 关键边界 |
|---|---|
| `layout-dump` | 输出 JSON artifact；mode=interaction（默认）裁剪摘要，mode=ui 保留采集树。非法 mode 返回 INVALID_PARAMS。 |
| `view-locate` | 坐标和尺寸单位为 dp；多匹配时不能把首个结果当作安全点击目标。 |
| `view-inspect` | 只允许只读表达式。显式 `foo()` 走 getter/query 白名单；无括号名字先读 public 字段，再按 `getXxx()` / `isXxx()` 解析。 |
| `tap` | 模式优先级为 coordinate > percent > element；元素多匹配时不执行。 |
| `activity-stack` | 用于确认前台 Activity 和页面稳定性。 |

ViewHierarchy 相关工具执行前会等待 App 在线。设备息屏或未解锁会返回 `DEVICE_NOT_INTERACTIVE`；目标 App 不在前台会返回 `APP_NOT_FOREGROUND`。

文件自包含 mode、units、windows、deviceInfo、truncated 和可选 warnings/rootLayout。bounds/padding 正常为整数 dp；缺少有效 density 时保留 px，由 units 明示。空值和默认 visibility=visible、alpha=1、clickable=false、enabled=true、全零 padding、空 children 省略。textColor 缺失也可能表示未采集或默认值。摘要统计实际输出节点，裁剪不设置预算 truncated。

`data.file` 始终指向完整产物，`contentBytes` 是包含节点换行的 UTF-8 文件字节数。不超过 16 KiB（16,384 字节）时，两种模式都会在 `structuredContent.data.layout` 中附带完整 JSON 对象；超出时省略该字段，不返回截取的部分树。文本 content 只包含摘要，整棵树不重复发送。CLI 的 plain/json 输出保留 Unicode。16 KiB 是文件阈值，不是响应总大小或 Token 上限；客户端需将 structuredContent 提供给 Agent 才能直接使用内联对象。

局部 UI 采集失败时，`layout-dump` 保留有效 JSON，并在摘要标记 `incomplete hierarchy`；结果可选 `data.warnings` 和 JSON 节点诊断给出原因。定位、查询和元素触控命中有效节点时仍返回结果并携带诊断；未命中且当前窗口采集不完整时返回 `UI_HIERARCHY_INCOMPLETE`。预算截断仍单独使用 `truncated`。恢复步骤见 [UI 自动化](../capabilities/tools/ui-automation.md#compose-内容缺失时)。

## 常见错误码

| 错误码 | 含义 |
|---|---|
| `INVALID_JSON_RPC` | JSON-RPC 格式错误。 |
| `METHOD_NOT_SUPPORTED` | 不支持的方法。 |
| `TOOL_NOT_FOUND` | 工具未注册。 |
| `INVALID_PARAMS` | 参数错误。 |
| `INVALID_REGEX` | 日志 marker 正则非法。 |
| `PROJECT_NOT_INITIALIZED` | 项目未完成 Jugg 初始化。错误信息会给出请求路径和当前已初始化项目。 |
| `NO_DEPLOY_BASELINE` | 缺少部署或 full build 基线。 |
| `NO_DEVICE` | 无可用设备。 |
| `DEVICE_NOT_INTERACTIVE` | 设备息屏或非交互态。 |
| `APP_NOT_FOREGROUND` | 目标 App 不在前台。 |
| `UI_HIERARCHY_INCOMPLETE` | 当前窗口的 UI 层级采集不完整，无法断言目标元素不存在。 |
| `INTERNAL_ERROR` | 内部错误。 |

## 未公开 Action

代码中存在但未注册的 action 不能被外部 MCP 客户端调用，包括 screenshot、record、start activity、start app、emulator 和 layout verify 相关 action。判断工具是否公开时，以 `tools/list` 和本页公开工具表为准。

## 相关页面

- [CLI 命令](./cli-commands.md)
- [MCP 使用指南](../guide/mcp.md)
- [UI 检查](../guide/ui-inspection.md)
