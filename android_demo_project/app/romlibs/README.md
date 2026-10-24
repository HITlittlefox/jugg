# ROM API 编译复现

本目录用最小 Java 桩模拟设备 framework，依赖通过 `compileOnly` 引入，不应打包进 APK。桩只模拟编译接口，不代表设备具备这些 API；不要在普通设备上实例化或调用复现类。

## 场景与入口

- `src/main/.../romhiddenapi/RomHiddenApiChild.kt`：间接继承 ROM SDK 的隐藏嵌套父类型，保留原场景。
- `src/romApi/.../AccessoriesFragmentCase.java`：报告 `261e2001` 中的 `BluetoothProfile.HID_HOST`。
- `src/romApi/.../WirelessNetworkManagerCase.kt`：报告 `752724a0` 中六个 Wi-Fi 方法的 `unresolved reference`。

后两项通过 `-PjuggRomApiFixture=true` 开启，默认不加入 app source set。各版本 app 构建模板均引用同一 `fixture.gradle`；本次实际验证 Kotlin 1.9.22 / AGP 7.3.1 配置，其他版本未验证。

## 重新生成桩

在工程根目录执行（需要 JDK 9+）：

```bash
python3 app/romlibs/build_fixtures.py
```

脚本将 `src/android` 打入 `rom_framework.jar`，将 `src/com` 打入 `rom_sdk.jar`，不把辅助 SDK 类混入 framework jar。

## 建立全量基线

```bash
./gradlew :app:assembleDebug -PjuggRomApiFixture=true
```

`fixture.gradle` 为 Gradle 的 Kotlin 编译依赖和 javac 引导类路径前置 framework 桩，使公开 SDK 缺失的方法也可建立全量基线。该顺序适配仅在复现开关开启时生效；依赖仍为 compile-only。

## 在 Android Studio 验证 Jugg

1. 使用包含 framework 初始化探测修复的插件打开本 demo。
2. 将 Jugg 全量编译命令设置为 `:app:assembleDebug -PjuggRomApiFixture=true`，建立基线并刷新项目模型。需要 IDE 索引包含复现源码时，同步为 Gradle 设置该项目属性。
3. 修改上述复现类的方法体，再运行 Jugg 增量编译。
4. 检查 `Framework classpath detection`：应命中 `rom_framework.jar`；实际 javac/kotlinc classpath 应在 SDK 前包含该 jar，不应出现旧的 SDK 后置重试。
5. 编译产物应包含复现类；APK DEX 不应定义桩中的 `android.bluetooth.BluetoothProfile`、`android.net.wifi.WifiManager`、`WifiInfo`、`WifiConfiguration` 或 `com.rom.RomSoftApCallback`。

本次没有 demo 专属有效 Jugg Run Configuration，CLI 未完成 IDE 内的增量编译；该步骤需在配置好的环境补验。

## 自动化矩阵

仓库根目录：

```bash
ANDROID_HOME=/path/to/sdk ./gradlew :main:test --tests '*RomApiCompileTest'
```

矩阵覆盖 Java/Kotlin 的字段、方法、嵌套类型、重载、构造函数、间接父类型，共 24 项。使用真实 SDK 35、javac 和 Kotlin compiler；先保留 SDK-first 失败基线，再通过真实 `BaseCompileContext` 验证 compile-only 探测及前置后成功，禁止依赖编译器自动重试。两份报告对应的 Java/Kotlin 源码直接读取 demo 文件；其余场景使用临时源码，Activity 重载桩只存在于临时 jar，不覆盖 demo 的正常 Activity API。
