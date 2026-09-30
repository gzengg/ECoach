# ECoach — 英语听说考试陪练（悬浮窗版）

一个**自用、侧载、不上架**的 Android App（项目名 EnglishCoach，应用显示名 **ECoach**，包名 `com.rd.englishcoach`）。

在西柚英语之类的听力 App 里练听力时，用**悬浮窗**浮在学习 App 上方：

> 听到一句想问的内容 → 点悬浮窗里的「暂停」→ App 把刚才那段**系统内声音**转成英文文字（ASR）
> → 让 AI 给一句**英文参考回答** → 显示在悬浮窗里，照着念。

整个流程都在悬浮窗内完成，不用切来切去。

## 功能一览

| 版本 | 内容 |
|---|---|
| **v1.0** | 核心功能落地：录系统内声音 → ASR → AI 英文参考回答；悬浮窗（开关 / 字号 / 宽度 / 拖动）；设置页；转录历史持久化；release 签名 |
| **v1.1 / v1.2** | 真机修复：设置页一屏放得下、ColorOS 悬浮窗权限引导、Android 16 edge-to-edge 裁切、停止后进程退出 |
| **v2.0** | 外观大改「深空玻璃 Aurora Glass」：design token + drawable 组件库，主界面 / 设置页 / 悬浮窗 / 轮次卡片全部重做 |
| **v3.0** | 功能增强：**朗读**（mimo TTS，系统 TTS 兜底）、**取词翻译**（框选屏幕 → 端上 OCR → 免费翻译）、悬浮窗「听力 / 取词」双 Tab、主界面状态同步修复 |
| **v4.0** | **离线化（可选，不内置模型）**：离线**识别**（VAD + Whisper turbo / SenseVoice / Whisper large-v3 / **Qwen3-ASR 0.6B**，由用户在应用内下载，模型页按系列分组）与离线**朗读**（Piper 中英）；在线 mimo **9 个音色可选**；设置页与模型管理合并为「设置 / 模型」两个 Tab；取词长文本分段翻译；长文本朗读分段合成 |
| **v4.0.4** | **本地文件转录**：选音频/视频文件 → 文本（视频自动抽音轨，可多选排队）；转完可复制 / 分享 / 导出 txt；**离线模型识别时可导出 SRT 字幕**；任务在后台前台服务里跑，离开页面不中断 |

v3.0 之后还有一批同属 3.0 的修复（取词链路重做、Tab 样式统一、清空上下文残留修复等），
都已包含在 `v3.0` 标签里。

**v4 的离线能力默认不生效**：不装任何模型时行为与 v3.0 完全一致（全部走在线）。
模型在「设置 → 模型」里下载（识别 156MB~1GB / 朗读 64MB 每语种），存在应用私有目录，**不进 APK、不进 git**。

**文件转录（v4.0.4）**在「监听」页顶部的入口卡片里：选一个或多个音视频文件排队转录，
识别引擎跟随「设置 → 模型」的运行模式（自动 / 仅在线 / 仅离线），结果进「历史 → 文件」Tab。
几条它特有的行为：

- 文件读取走系统文件选择器（SAF），**不申请任何存储权限**；文件被删或权限失效时会给出可读错误。
- **SRT 字幕只在离线模型识别时可用**：在线三个识别协议都只返回纯文本，没有时间轴，
  此时点「导出字幕」会直接说明原因，不会生成一个时间全错的文件。
- **转写期间与「实时监听」互斥**：两处都持有离线识别模型（0.1~1GB native 内存），
  同时跑会吃掉双份内存并互相抢核，所以开始转写前会要求先停止监听。
- 1 小时的音频约需 5~30 分钟（取决于模型档位：SenseVoice 快、Whisper/Qwen3 慢）。

## 编译

前置：JDK 17+、Android SDK（`compileSdk 35`）。

```bash
# 1. 指向本机 SDK（此文件不入库）
echo 'sdk.dir=/path/to/Android/Sdk' > local.properties

# 2. 跑单元测试（纯 JVM，不需要设备）
./gradlew testDebugUnitTest

# 3. 打 release 包
./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

**签名**：把凭证放在项目根的 `keystore.properties`（已 gitignore，不入库）：

```properties
storeFile=/absolute/path/to/your.jks
storePassword=...
keyAlias=...
keyPassword=...
```

文件不存在时 release 会退回 debug 签名，保证在没有密钥的机器上也能编译通过。

## 配置

首次使用需要**自己填 API Key**（默认留空，不内置任何 key）：

- App 内「设置」页可改：Base URL / API Key / ASR 模型 / 回答模型 / TTS 模型 / 声音 / 字号 / 窗口宽度 / 系统提示词。
  「模型」Tab 可改：运行模式（自动 / 仅在线 / 仅离线）、离线识别用哪个模型、朗读音色、下载源。
- 默认值见 `Prefs.java`。未填 key 时，听力区与朗读会给出明确提示并**不发请求**；
  **取词翻译不受影响**（OCR 在端上跑，翻译走免费接口，都不需要 key）。
  **装了离线模型后识别与朗读也不需要 key**。

## 需要的权限

| 权限 | 用途 |
|---|---|
| `RECORD_AUDIO` | 录系统内声音（`AudioPlaybackCapture` 要求） |
| `FOREGROUND_SERVICE_MEDIA_PROJECTION` | 前台服务持有投屏会话 |
| `FOREGROUND_SERVICE_DATA_SYNC` | 后台转写本地文件（前台服务，Android 14+ 需声明具体类型） |
| `SYSTEM_ALERT_WINDOW` | 悬浮窗 |
| `POST_NOTIFICATIONS` | 前台服务通知（Android 13+） |
| `INTERNET` | ASR / AI / TTS |

> ⚠️ 少数 ROM（ColorOS / 一加等）会对侧载应用锁死「显示在其他应用上层」，
> 系统设置页里的开关点不动。App 会弹出提示并给出绕行命令：
> `adb shell appops set com.rd.englishcoach SYSTEM_ALERT_WINDOW allow`

## 为什么不上架

- 需要 `MediaProjection` 录制**系统内声音**并常驻投屏会话；
- 需要悬浮窗覆盖在其他 App 之上；
- 是给一个人用的工具，不是产品。

## 版本标签

| 标签 | 含义 |
|---|---|
| `v1.0` | 功能实现 |
| `v1.1` / `v1.2` | 中间修复版本 |
| `v2.0` | 外观大改 |
| `v3.0` | 功能增强（含全部 3.0 修复） |
| `v4.0` | 离线识别（Whisper / SenseVoice / Qwen3-ASR）+ 离线朗读（模型用户自选下载，不内置） |
