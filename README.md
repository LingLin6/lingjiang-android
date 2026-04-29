# 翎绛 Android

翎绛是一个手机端实时听觉分析助手。它不是 AI 电话聊天，也不是普通录音或会议纪要工具，而是一个旁路听觉理解系统：用户点击开始后，App 通过手机麦克风持续采集周边声音，实时生成字幕、上下文摘要、风险提示、追问建议和待办；用户点击挂断后，沉淀完整转写、时间戳、关键结论、证据和最终报告。

## 项目目标

- 手机端优先，当前面向 Android 14 / API 34 真机验证。
- 通话式外壳，旁路分析内核，避免把界面做成聊天电话。
- 实时字幕持续更新，分析提示保持克制，只在关键事件出现时提醒。
- 完整转写是底账，滚动摘要只是上下文压缩材料，不能覆盖原始记录。
- 报告必须区分事实、推断和建议，并尽量绑定时间戳与原文依据。

## 核心能力

- 原生 Android 项目：Kotlin + Jetpack Compose + Material 3。
- 手机麦克风持续采音、分片、VAD、降噪和冲击噪声过滤。
- 本机 sherpa-onnx Paraformer 中文离线识别，用于无 PC 场景。
- OpenAI-compatible ASR / Chat 接口抽象，支持替换远端转写和云端分析。
- 上下文管理：实时字幕、短期窗口、长期滚动摘要、结构化状态。
- 本地快分析：责任人、时间、价格、风险、问题、承诺、意图和资料查询候选。
- 会话持久化：Room 保存会话、转写、实时洞察、待办、风险、决策和报告。
- 历史页与报告页：回看会话、完整转写、实时建议、证据时间戳和最终报告。

## 当前重点

当前版本已经完成端到端闭环：开始采音、离线转写、本地快分析、可选云端深分析、本地持久化、历史记录和最终报告。近期重点在提升复杂环境下的识别质量，包括：

- 稳态噪声拒绝；
- 频域降噪和软门限；
- 弱音频与短文本幻听过滤；
- 敲击、碰撞等瞬态冲击噪声过滤；
- 面向 LingLin 工作场景的意图识别和专项提醒。

## 技术结构

```text
app/src/main/java/com/linglin/lingjiang
  audio/      采音、实时字幕、降噪、VAD、冲击噪声过滤
  data/       Room 实体、DAO、仓库与迁移
  model/      会话状态与领域模型
  network/    ASR、LLM、研究查询等 Provider 抽象与实现
  session/    会话编排、上下文管理、事件检测、意图分析、报告生成
  ui/         Compose 主界面、设置页、历史页、报告页
```

## 配置方式

密钥不写入源码。构建时从 `local.properties` 或环境变量读取配置，并注入 `BuildConfig`。

可用配置项：

```properties
LINGJIANG_ASR_BASE_URL=
LINGJIANG_ASR_API_KEY=
LINGJIANG_ASR_MODEL=
LINGJIANG_LLM_BASE_URL=
LINGJIANG_LLM_API_KEY=
LINGJIANG_LLM_MODEL=
LINGJIANG_CLOUD_LLM_BASE_URL=
LINGJIANG_CLOUD_LLM_API_KEY=
LINGJIANG_CLOUD_LLM_MODEL=
```

`local.properties` 已被 `.gitignore` 排除。设置页只展示配置状态、模型名和连通性，不提供密钥编辑。

## 本地构建

要求：

- JDK 17
- Android SDK 34
- Gradle Wrapper
- Android 14 / API 34 arm64 真机

常用命令：

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
```

安装到已连接设备：

```powershell
adb install -r -g --user 0 app\build\outputs\apk\debug\app-debug.apk
```

## 验证口径

- 从开始到挂断能完成一条真实会话。
- 完整转写和时间戳被保存。
- 实时字幕、实时建议和最终报告清晰区分。
- UI 不呈现为 AI 聊天电话。
- 报告中的关键结论带原文证据，并区分事实和推断。
- 无接口配置、模型不可用、权限失败或转写失败时，界面有明确状态且不崩溃。

## 隐私边界

翎绛默认只采集手机麦克风能听到的周边声音，不承诺读取系统电话中的对方音频。涉及他人对话时，请遵守当地法律、会议规则和必要的告知义务。敏感场景应使用暂停、挂断和删除记录等控制能力。

## 仓库说明

仓库包含 Android 工程源码、本机 ASR 所需模型资产和本地构建依赖。录音样本、调试音频、`.codex` 工作目录、`local.properties`、APK 和构建产物不会提交。
