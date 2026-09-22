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

v3.0 之后还有一批同属 3.0 的修复（取词链路重做、Tab 样式统一、清空上下文残留修复等），
都已包含在 `v3.0` 标签里。

## 编译

前置：JDK 17+、Android SDK（`compileSdk 35`）。

```bash
# 1. 指向本机 SDK（此文件不入库）
echo 'sdk.dir=/path/to/Android/Sdk' > local.properties

# 2. 跑单元测试（304 个，纯 JVM，不需要设备）
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
- 默认值见 `Prefs.java`。未填 key 时，听力区与朗读会给出明确提示并**不发请求**；
  **取词翻译不受影响**（OCR 在端上跑，翻译走免费接口，都不需要 key）。

## 需要的权限

| 权限 | 用途 |
|---|---|
| `RECORD_AUDIO` | 录系统内声音（`AudioPlaybackCapture` 要求） |
| `FOREGROUND_SERVICE_MEDIA_PROJECTION` | 前台服务持有投屏会话 |
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
