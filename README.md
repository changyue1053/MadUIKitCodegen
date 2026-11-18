# MadUIKitCodegen

MadUIKit 的开发期代码生成器。P1 增加 **离线 Figma REST 文件节点响应 → 规范化 Frame → 确定性 CMP 源码**。不调用线上 Figma、不依赖 MadUIKit SDK，也不宣称视觉一致。P0 的规范化输入仍能运行。

## 本地运行

需要 JDK 17、Bash 和 Python 3（部分测试脚本），Gradle Wrapper 固定到 8.13。首次构建需要访问 Gradle 分发、Gradle Plugin Portal 与 Maven Central。Kotlin 2.2.21，JVM target 17。

```sh
./gradlew check installDist
build/install/MadUIKitCodegen/bin/MadUIKitCodegen generate \
  --input fixtures/synthetic-profile.json --out build/generated/profile
diff -r sample-output build/generated/profile
```

`sample-output/` 是对应合成稿的源码和元数据，可供 Demo 作为干净宿主消费。复制 `SyntheticProfileScreen.generated.kt` 到宿主 `commonMain`，其包名为 `dev.maduikit.demo.generated`。宿主依赖 Compose Material3 + Foundation，并向 `SyntheticProfileScreen(onSave = …)` 提供点击回调。

## P0 规范化输入与错误

- `schemaVersion: 1` 配一个根 `Column` 和唯一、稳定的 `id`。
- 布局：`Column`、`Row`、`Box`。支持 `children`、`padding`、`fillWidth`、`height`、`background`；Column 和 Row 另外支持 `gap`。尺寸为整数 dp。
- `Text`：`text`、`fontSize`（整数 sp）、`color`、`bold`。默认字型来自宿主，未固定 Figma 字体。
- `Button`：`text`、`fillWidth`，只支持显式 `action: "save"`。P0 按钮样式来自 Material3，不假装复刻设计。
- 色值使用 `#AARRGGBB`。未知节点/属性、重复 ID 或无效输入严格退出 2；能定位到节点时，`diagnostics.json` 包含节点 ID 和路径。请使用新的输出目录或以退出码与输入哈希判断结果，失败不会将上一次的文件视作本次产物。

## P1：导入文件节点响应

```sh
build/install/MadUIKitCodegen/bin/MadUIKitCodegen generate-figma \
  --input fixtures/figma/profile.file-nodes.json --node 1:2 --out build/generated/figma-profile
diff -r sample-figma-output build/generated/figma-profile
```

这份 fixture 是按照 Figma REST 文件节点接口的 `nodes[id].document` 结构编写的**公开合成样例**；不是从真实 Figma 文件下载。输入必须包含指定节点的完整子树；请勿使用截断子树的数据。此仓库不包含网络采集脚本、真实稿件或素材。节点 ID 用 API 的冒号格式（如 `1:2`）。程序不需要 Token、网络或 Figma 的实时连接。

- 输出：`FigmaProfileScreen.generated.kt`、`normalized-frame.json`、`manifest.json`、`source-map.json`、`diagnostics.json`。清单含输入 SHA-256、源版本（响应里提供时）、适配器版本、生成代码哈希；源码映射保留可见图层的原始 id。Demo 引用 `dev.maduikit.demo.generated.FigmaProfileScreen()`。
- 支持：横/纵向、不换行、起点对齐的 Auto Layout FRAME，常规固定/HUG 尺寸、纵向父节点下子节点的横向 FILL、不对称内边距、可选裁切、单色 RGBA 填充、真实 TEXT 文本（泛型字族、400/700、常规字间距和部分行高）。显式隐藏的子树不参与布局或生成。设计坐标按 1 设计 px → 1 dp/sp 解释，保留最多 6 位小数；颜色转 8 位 ARGB。
- P1 路径不支持：图片填充、矢量、常规渐变（P5 的窄范围轴对齐扩展见下文）、阴影、描边、多重混合、旋转/绝对定位、复杂对齐、Rich Text、字体素材及交互推断等。命名字体（包括 Inter）需要明确字体素材/映射，当前严格拒绝，不用另一字体替换。合成样例中的 CSS 泛型字族是用于验证流程的输入值，不代表真实设计已复刻该字体。
- 超出范围的可见节点或已识别但未支持的视觉属性返回退出码 2，报告节点 ID 与字段路径；新输出目录不会得到成功产物。前次结果如果仍在原目录，不是当次产物，应以退出码和输入哈希为准。流程演示不等于实稿验收或像素级一致。

## P5 扩展实验：轴对齐线性渐变与宿主宽度

P5 预览仍兼容 P0/P1 fixture。新增支持 Auto Layout FRAME 的单层
`GRADIENT_LINEAR`：横向或纵向（含反向），三点均在归一化坐标
0–1 区间，且第三点配合主轴严格垂直；2–16 个严格递增色标，8-bit
ARGB 色值及可选绘制透明度。生成器发出 `Brush.linearGradient` 与
`drawWithCache`，在实际绘制边界上换算坐标；不使用截图背景。

默认仍使用 Figma 的固定根宽度。`--fit-width` 显式允许把固定宽的纵向根
Frame 改为匹配有界宿主宽度，保留固定高度、内边距和文字重排。该选项
不是 Figma 的原始固定坐标复刻、自动断点设计或任意尺寸适配。只有指定
Frame 可用；无界宿主不在保证范围内。

```sh
./gradlew check installDist
build/install/MadUIKitCodegen/bin/MadUIKitCodegen generate-figma \
  --input fixtures/figma/gradient.file-nodes.json --node 3:1 \
  --out build/generated/gradient --fit-width
diff -r sample-gradient-output build/generated/gradient
```

斜向/仿射、径向、角向、菱形、绑定变量、多重填充、重复色标、未知字体和
其它原有未实现特性仍严格拒绝并报告节点。P5 带来的新 IR 字段不应直接交给
尚未支持的 ArkUI emitter。渐变在真实 Figma 与各端渲染器之间的插值、字体及
抗锯齿一致性尚未验收。
