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
- `Text`：`text`、`fontSize`（整数 sp）、`color`、`bold` 或 `medium`。两个字重标志不能同时为 true。默认字型来自宿主，未固定 Figma 字体。
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
- 支持：横/纵向、不换行、起点或居中对齐的 Auto Layout FRAME，常规固定/HUG 尺寸、纵向父节点下子节点的横向 FILL、不对称内边距、可选裁切、单色 RGBA 填充、真实 TEXT 文本（泛型字族、400/500/700、常规字间距和部分行高）。显式隐藏的子树不参与布局或生成。设计坐标按 1 设计 px → 1 dp/sp 解释，保留最多 6 位小数；颜色转 8 位 ARGB。
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

## 可选：展开实例与居中 Auto Layout

`generate-figma --flatten-instances` 将已带完整子树的 Figma
`INSTANCE` 按已有 Auto Layout 规则转换成 Compose 容器。**默认仍拒绝
可见的 INSTANCE**。该选项只使用当前输入里的可见子节点和已解析的
文本样式；不查询组件库、不实例化运行时组件、不推断交互或覆盖规则，
也不把 Figma 组件绑定成 MadUIKit 组件。没有子节点的实例、格式无效
的组件 ID、实例上的非空交互定义以及不支持的可见子节点会失败，不会
悄悄生成占位组件。调用方须提供完整子树：工具无法判断一个非空但被截断的输入是否完整。Frame / 文本 / 填充仍需符合前述支持边界。

普通 Auto Layout Frame 与展开的实例都接受主、次轴
`CENTER`，不需要额外的居中开关。`MIN` 仍按原方式生成，
`SPACE_BETWEEN` 的有界范围见下文；环绕、基线等其他对齐仍不在支持范围。
居中是布局对齐，不等于文字字形或排版特性的逐像素还原。
该里程碑不验收真实稿件、Android/iOS/HarmonyOS 渲染或像素一致性。

```sh
./gradlew check installDist
build/install/MadUIKitCodegen/bin/MadUIKitCodegen generate-figma \
  --input fixtures/figma/instance.file-nodes.json --node 10:1 \
  --out build/generated/instance --flatten-instances
```

例子是手工构造的数据，完全离线。可与 `--fit-width` 合用。
开启实例展开时，清单记录 `instanceMode: expanded-visual-v1`；
对于展开的实例，源码映射另外记录 `instanceComponents`
（实例 ID 到源组件 ID），仅作诊断。真实输入及其生成输出可能包含
设计文字和标识，公开分享前请检查内容。

### Circular corner radii (synthetic coverage)
Horizontal and vertical Auto Layout frames (including opt-in expanded visual
instances) accept a uniform Figma `cornerRadius` or four
`rectangleCornerRadii` in clockwise physical order TL, TR, BR, BL. Generated
CMP uses `AbsoluteRoundedCornerShape` so RTL does not mirror physical Figma
corners. Solid and supported axis-gradient backgrounds are shaped.
`clipsContent: true` clips descendants to the same shape; otherwise only the
paint follows the outline. Zero radii preserve the previous output. Nonzero
corner smoothing (squircles), malformed or conflicting radii, and unsupported
node types remain explicit failures. The synthetic sample is not a private
design or a pixel-parity claim. [Figma REST node types](https://developers.figma.com/docs/rest-api/file-node-types/).

### 自动分布间距（窄范围）

识别 Figma `primaryAxisAlignItems: SPACE_BETWEEN`。子元素贴主轴两端，
剩余空间平均分配；不生成固定像素间隙。横向元素按 Figma 图层物理从左到右，
不会因 CMP 的 RTL 方向自动交换；纵向从上到下。交叉轴仍遵循既有规则。

仅接受固定主轴，或处在宽度受限纵向容器中的横向 FILL；例如，固定宽度的
纵向 Figma 根 Frame 使用 `--fit-width` 后，其中的 FILL 工具栏可随宿主改宽。
宿主必须提供有限宽度，且至少容得下所有子项与内边距。可见子项至少两个，
且主轴尺寸明确为 FIXED；显式隐藏的子项不参与。非零 itemSpacing、换行、
HUG 主轴、伸缩子项、无界父容器与已知溢出只生成诊断，不猜测。产物中的
`mainSpaceBetween` 是布尔值，不能和居中或非零固定间距冲突；不表示 ArkUI 支持。
参考 [Figma 节点模型](https://developers.figma.com/docs/rest-api/file-node-types/)。

```sh
./gradlew check installDist
build/install/MadUIKitCodegen/bin/MadUIKitCodegen generate-figma \
  --input fixtures/figma/distribution.file-nodes.json --node 25:1 \
  --out build/generated/distribution --fit-width
```

公开合成测试包含随宿主变化的工具栏和固定高度面板，不代表实稿或跨端像素一致。

### 离线单个装饰图标（显式绑定）

`generate-figma --asset-map path/to/map.json` 支持给**一个**可见的无交互 VECTOR
叶节点声明已经准备好的 PNG；默认仍拒绝 VECTOR。生成器不访问 Figma/CDN，不下载、
解析或重画矢量，也不使用整页截图。它验证绑定、逐字节保留 PNG 并生成可编译的 CMP
`Image(painterResource(Res.drawable.…))`；这是纯装饰图，`contentDescription = null`，
不适用于需要可访问名称的图标。

映射的四个字段为 `schemaVersion: 1`、规范 `nodeId`、相邻普通文件的安全基础名
`file: "mad_icon_….png"` 和预先核验的小写 `sha256`。不允许 URL、子目录、遍历路径或
符号链接 PNG。只支持带透明及可见像素的 8 位 RGBA、非隔行 PNG，不接受 APNG/其他
附加块；上限 32 KiB、每轴 64px。VECTOR 节点必须显式固定尺寸，PNG 像素宽高
必须与节点的 dp 尺寸相同，不使用遮罩、旋转、变形、节点效果、描边或交互。
不从未知字段推断素材；错误会输出诊断，不生成新的成功 Kotlin/清单。

```sh
./gradlew check installDist
build/install/MadUIKitCodegen/bin/MadUIKitCodegen generate-figma \
  --input fixtures/figma/resource.file-nodes.json --node 30:1 \
  --asset-map fixtures/figma/resource/asset-map.json --out build/generated/icon
```

将生成的 Kotlin 与 `composeResources/drawable/*.png` 一并复制到同一消费者模块的源码集，
KMP 例子是 `src/commonMain/kotlin` 和 `src/commonMain/composeResources/drawable`。
消费者启用 CMP Resources （`implementation(compose.components.resources)`），并设
`compose.resources { packageOfResClass = "dev.maduikit.demo.resources" }`。生成 manifest
记录输入、映射、图像的 SHA-256，以及节点 ID、固定尺寸和产物相对路径。普通
`generate` 不接受缺少已验证素材的归一化图像节点。演示金蓝菱形为网格手写合成 PNG，
可用 `python3 scripts/make-synthetic-icon.py` 复建。这不是通用图像流水线，也未验收
Android/iOS/HarmonyOS 打包。

装饰图绑定也可接收单层复合 ID（如 `I40:3;41:7`），但必须同时启用
`--flatten-instances`，且它必须是其声明的普通 ID 实例的**直接可见 VECTOR
子节点**。映射仍只允许一个 PNG，不把整个 INSTANCE 转成图片；不支持间接、
多段或归属不符的 ID，不修改 DPR / dp 规则。示例完全合成：

```sh
build/install/MadUIKitCodegen/bin/MadUIKitCodegen generate-figma \
  --input fixtures/figma/compound-resource.file-nodes.json --node 40:1 \
  --flatten-instances --asset-map fixtures/figma/compound-resource/asset-map.json \
  --out build/generated/compound-icon
```

单层透明包装框也可显式绑定：如 `I42:3;43:6;43:7`，必须在展开的
`42:3` 实例下找到**直接**的 `I42:3;43:6` FRAME，再找到其唯一直接可见
子节点 `I42:3;43:6;43:7` VECTOR。FRAME 只接受固定 Auto Layout、
明确相同的 x/y/宽/高、无填充/背景/描边/效果/裁剪/交互，
不接受非零间距、内边距、变换偏移、未知字段或额外（即使隐藏）子节点。
包装框仍生成常规 Compose 容器，不替换组件或任何控制交互。任何更多层级、
未知真实稿结构或不全数据均未验收。

```sh
build/install/MadUIKitCodegen/bin/MadUIKitCodegen generate-figma \
  --input fixtures/figma/wrapped-resource.file-nodes.json --node 42:1 \
  --flatten-instances --asset-map fixtures/figma/wrapped-resource/asset-map.json \
  --out build/generated/wrapped-icon
```

### 安全交付生成产物（可选包装器）

自动化消费代码时，推荐用 `scripts/generate-verified.py`，而不是反复写入同一目录：

```sh
mkdir -p build/verified
python3 scripts/generate-verified.py \
  --launcher build/install/MadUIKitCodegen/bin/MadUIKitCodegen \
  --input fixtures/figma/wrapped-resource.file-nodes.json --node 42:1 \
  --asset-map fixtures/figma/wrapped-resource/asset-map.json --flatten-instances \
  --out build/verified/wrapped-v1
```

包装器在输出父目录下的新临时目录执行生成器，仅在退出码为 0、诊断成功、
输入/映射哈希与调用选项一致、Kotlin/资源文件哈希匹配后发布目录。已存在的
输出（包括符号链接）一律拒绝，不覆盖旧版本；失败清理临时目录，不留下看似
成功的目标目录。父目录需预先存在，每个调用使用独立目标，不允许并发写同一
目标或不可信进程修改父目录。直接调用原 CLI 的行为不变。

验证包括源映射文件名一致性和多余文件检查；不是签名验证、恶意生成器沙箱、
完整元数据真实性证明，也不替代 CMP 编译、设备运行或视觉验收。调用者仍须检查
包装器退出码，仅在成功后将该新目录交给消费者。24 项公开合成安全测试纳入
`./gradlew check`，覆盖成功路径、旧输出保护、生成失败、哈希篡改与路径/元数据异常。

生成包内部仍拒绝符号链接；可信父目录的系统级别名（例如 macOS 的
`/var` → `/private/var`）不属于生成包，不会被误判为生成素材链接。

### Opt-in atomic decorative frames (asset map schema 2)

For a small decorative illustration made from multiple vector layers, bind one
approved transparent PNG to the **whole** FRAME, not to individual paths. This is
not automatic flattening. Retain the ordinary `--flatten-instances` opt-in and
use a schema-2 asset map with the existing `nodeId`, `file`, and `sha256`
fields plus:

- `framePath`: exact ordered IDs from the selected root, through at least one
  expanded visual INSTANCE, to the asset FRAME; last ID equals `nodeId`.
- `vectorIds`: the complete ordered list (2–4 entries) of direct visible VECTOR
  children. Paths and vector IDs must be unique in the source.

The FRAME has explicit fixed dimensions matching the PNG, no layout, clipping,
paints, effects or interactions. Only small, contained visible VECTOR leaves
with one normal solid fill are supported; the generator rejects extra or
unknown visual features rather than discarding them. The PNG is an explicitly
approved, hash-pinned, local representation, not synthesized or inferred by the
generator. The generator checks layout, bounds, ancestry and asset integrity;
the caller still must visually verify that the provided PNG represents the
selected layer correctly. Existing schema-1 VECTOR maps are unchanged.

`scripts/atomic-frame-smoke.py` constructs a generic three-bar illustration
and validates deterministic emission and fail-closed rejection cases.


### Generic medium text weight

For generic font families, the Figma adapter maps numeric `fontWeight: 500`
to normalized `Text.medium: true` and emits `FontWeight.Medium`. This
does not select a named font binary or guarantee that every native host has a
distinct medium face. Schema 1 remains accepted: the old regular default and
`bold: true` representation keep their existing output. Medium and bold cannot
both be enabled. Unsupported weights, textual weights, and malformed flags fail
with diagnostics instead of rounding or choosing a fallback.
