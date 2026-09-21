# AGENTS.md — ECoach 英语陪练（悬浮窗版）

> 本文件是**后续开发的唯一依据**。里面每一条"已确认"都是和用户逐条确认过的结论，不要重新假设、不要擅自扩大范围。
> 与用户沟通一律用中文。

**当前目标版本：v3.0**（详见 §11 与 `PLAN_v3.md`）

---

## 1. 项目是什么

一个**自用、侧载、不上架**的 Android App（项目名 EnglishCoach，应用显示名 **ECoach**，包名 `com.rd.englishcoach`）。

用户在**西柚英语**（`com.xiyou.english`）里练听力，本 App 用一个**悬浮窗**浮在学习 App 上方：
听到一句想问的内容 → 点悬浮窗里的「这句完了」→ App 把刚才那段**系统内声音**转成英文文字（ASR）→ 再让 AI 给一句**英文参考回答** → 显示在悬浮窗里，用户照着念。

**核心诉求：整个流程都在悬浮窗内完成，不切来切去。**

v3.0 起额外提供两件事：**朗读**（照着念的时候能听读音）和**取词翻译**（框住屏幕上的文字翻成中文）。

---

## 2. 需求结论（已逐条确认，勿再追问）

### 2.1 v1 时代已确认

| # | 项 | 确认结果 |
|---|---|---|
| 1 | 声音来源 | 英语学习 App（西柚英语）播放的声音，即**录系统内声音**，不是麦克风 |
| 2 | 实时程度 | **不需要接近实时**，按句/按段出结果即可 |
| 3 | 怎么切句 | **用户手动点「这句完了」**，不做自动停顿检测 |
| 4 | AI 输出 | **只要英文参考回答**：不要中文翻译、不要讲解、不要多种说法 |
| 5 | 手机 | PJX110（OPPO/一加，ColorOS），**Android 16 / API 36** |
| 6 | 授权方式 | **方案 A**：授权一次 → 保持 MediaProjection 会话不释放 → 悬浮窗里随时开关，不再弹窗 |
| 7 | 悬浮窗功能 | 开始听/暂停听开关、AI 英文参考回答、手动「这句完了」按钮、字号可调、窗口大小可调、可拖动不遮挡字幕 |
| 8 | API Key | **写死为默认值，但 App 里能改成别的**（Base URL / Key / 模型名都可改）<br>⚠️ **v3.0 改为：默认留空**（见 2.2 第 4 条） |
| 9 | 视觉风格 | **「深空玻璃」Aurora Glass，锁定深色**，覆盖主界面 / 设置页 / 悬浮窗 / 图标。完整规范见第 10 节 |

### 2.2 v3.0 新增确认

| # | 项 | 确认结果 |
|---|---|---|
| 1 | 遗存 bug | 主界面状态更新不及时：**悬浮窗关了仍显示「已启动」**。要求修掉 |
| 2 | 新增：朗读 | 朗读模型 **`mimo-v2.5-tts`**。<br>**交互（用户明确要求「每个文字旁边都能点读」）**：ASR 原文、AI 参考回答、取词译文**各自都有一个独立的小喇叭按钮**，点一下读、再点同一个停、同一时刻只播一路 |
| 3 | 新增：取词翻译 | 「提取屏幕文字 → 用户选择文字 → 交给 AI 翻译」，且**与录音是同级功能**（不是附属小按钮） |
| 4 | 默认 API Key | **默认留空**，其他默认值一律不变 |
| 5 | 翻译方向 | **以中译英为主，但自动判断方向**：给的是中文就译成英文，给的是英文就译成中文 |

### 2.3 v3.0 交互细节（逐条问过、已确认）

| 问题 | 确认结果 |
|---|---|
| **取词怎么选文字** | **框选**：拖一个矩形，框里的内容送去翻译 |
| **取词时悬浮窗** | **完整隐藏**（用户确认能接受短暂看不到答案，取完马上恢复） |
| **取词结果放哪** | **悬浮窗顶部两个 Tab：`听力` / `取词`**，窗口尺寸不变，两边各自有完整卡片列表 |
| **翻译要不要用 AI** | **尽量不用 AI，用免费翻译接口**（已实测可用的见 §4.4） |
| **取字用什么** | **端上 OCR**（离线、免费、不要 key），见 §4.5 |

**为什么要隐藏悬浮窗（技术原因，不是审美）**：MediaProjection 截的是**合成后的屏幕**，
悬浮窗自己的文字会被一起截进去，OCR 就会把「我们显示的答案」也认出来。所以取词前**必须**先隐藏悬浮窗。

---

## 3. 功能范围

### v1（已完成，tag `v1.1` / `v1.2`）

1. 打开 App → 点「开始监听」→ 系统弹一次投屏授权 → 点「开始录制」→ 会话一直有效。
2. 悬浮窗（`TYPE_APPLICATION_OVERLAY`）：可拖动、半透明、不挡字幕、字号与窗口宽度可调。
3. 悬浮窗内三件事：**开始听/暂停听**、**「这句完了」**、**英文参考回答列表**（最新在最上，可滚动）。
4. 点「这句完了」后链路：截取最近 N 秒音频（环形缓冲）→ 编 WAV → ASR 出原文 → `deepseek-flash` 出英文参考回答 → 显示。
5. 设置页：Base URL / API Key / ASR 模型名 / 回答模型名 / 字号 / 窗口宽度 / 显示原文开关 / 系统提示词，全部可改，带默认值。
6. 音量指示条：让用户一眼看出"到底有没有录到声音"。
7. 投屏被系统停止时，悬浮窗显示提示 + **「重新授权」按钮**。

### v2.0（已完成，tag `v2.0` — 见 §8）

- **视觉改版「深空玻璃 Aurora Glass」**：重建 design token（19 色）+ drawable 组件库（16 个），重做主界面 / 设置页 / 悬浮窗 / 通知小图标。规范见第 10 节。
- 版本号升至 `versionCode 4` / `versionName 2.0`。
- 原则：**只动外观，不动行为**——所有 view id、窗口标志位逻辑、`ListenToggle` 状态机、网络与采集链路一律不改。

### v3.0（本次目标，详情见 `PLAN_v3.md`）

1. **修遗存 bug**：主界面状态实时刷新（服务主动广播）；顺带修通知栏「停止」按钮只撤通知、不停服务的问题（**行为与文案不符，不是意外 bug，见 §11.1**）。
2. **朗读**：原文 / 回答 / 译文各自一个小喇叭，`mimo-v2.5-tts` 优先，安卓系统 TTS 兜底。
3. **悬浮窗改双 Tab**：`听力` / `取词`，窗口尺寸不变。
4. **框选取词翻译**：隐藏悬浮窗 → 定格截图可拖框 → 端上 OCR → 免费翻译（自动判方向）→ 恢复悬浮窗出卡片。
5. **取词结果接入对话上下文**：取词后能切到「听力」页签直接追问。
6. **默认 API Key 留空** + 未填 key 时给出明确提示（取词翻译不受影响）。
7. 首次引入第三方依赖：ML Kit `text-recognition-chinese`（see §6.3）。

### 明确不做（记在待办，别顺手实现）

- **行选 / 精确选词**（自动识别整屏文字行、点哪行翻哪行）→ 等框选真机跑通再评估。
- **云端 OCR**（百度/腾讯）→ 只作为「端上 OCR 不可用」时的兜底，**不主动做**。
- 取词历史持久化 / 导出 / 整屏一键复制。
- 复杂的中英双语语音设置（朗读书签、语速、音调等）。
- 无障碍服务自动点授权弹窗（**方案 B**）：等方案 A 跑通再真机验证 B 是否可行。
- 自动按停顿切句、录自己的声音 / 发音打分、中文翻译与讲解（听力区）、
  历史记录持久化与导出、一键复制、最小化成悬浮小球、息屏后台继续录、分享给别人用、上架商店。

> 例外：**"显示识别原文"在设置页留了一个开关，默认关闭**——因为它对排查"是 ASR 听错了还是 AI 答偏了"极其有用，属于调试价值，不占悬浮窗版面。

---

## 4. 服务端 API（已实测通过，含坑）

- **Base URL**：`https://mimo.ezlook.top/v1`
- **API Key**：**不入库、不写进本文件**。
  - App 默认值为**空字符串**（v3.0 起）。
  - 开发者本地跑测试用的 key 放在**被 gitignore 忽略**的 `E:/rd/EnglishCoach/.local/api-key.txt`。
  - ⚠️ 用户会**经常更换 key**，不要把任何 key 写进 git 跟踪的文件。

### 4.1 ASR：`mimo-v2.5-asr` ✅ 实测通过

`POST /v1/chat/completions`，请求体：

```json
{"model":"mimo-v2.5-asr","messages":[{"role":"user","content":[
  {"type":"input_audio","input_audio":{"data":"<base64>","format":"wav"}}]}]}
```

- `data` 里**直接放纯 base64，不要加 `data:audio/wav;base64,` 前缀**。
- **16kHz 单声道 PCM16 WAV 可以**；48kHz 立体声也可以（服务端自己重采样）；24kHz 单声道也实测可识别。
- 延迟实测：11 秒音频 ≈ 4.9s；4 秒音频 ≈ 0.84s；2 秒音频 ≈ 2.0s。
- 响应里 `message.content` 就是识别文本。

### 4.2 回答：`deepseek-flash` ✅ 实测通过

`POST /v1/chat/completions`，`messages` = `[{role:system, content:<提示词>}, {role:user, content:<ASR 原文>}]`，`temperature` 0.7。

- ⚠️ **坑：该模型会先吐 `reasoning_content`**（实测一次 418 tokens）。**必须只取 `content`**。**v1 起直接用非流式**。
- 实测延迟 1.7~5s（最近一次 3.4s），输出纯英文、1~2 句、口语化。

### 4.3 朗读：`mimo-v2.5-tts` ✅ 实测通过（**坑最多，照抄**）

**⚠️ 不是 `/v1/audio/speech`**！该端点实测返回 `model_not_found`（未开通）。
**正确走法**：`POST /v1/chat/completions`

```json
{
  "model": "mimo-v2.5-tts",
  "messages": [{"role":"assistant","content":"要朗读的文本"}],
  "modalities": ["text","audio"],
  "audio": {"voice":"Mia","format":"wav"}
}
```

三条硬性约束（都是实测报错逼出来的）：

1. **要朗读的文本必须放在 `assistant` 角色的 message 里**。
   放 `user` 里会报 `Param Incorrect: messages must contain an assistant role for TTS model`。
2. **必须带 `modalities:["text","audio"]`**，否则不会返回音频。
3. **`voice` 只能是这几个值**：
   `mimo_default`、`冰糖`、`茉莉`、`苏打`、`白桦`、`Mia`、`Chloe`、`Milo`、`Dean`
   （后四个是英文声）。传 `alloy` 会报 `Unknown voice`。

取音频的路径：

```
resp.choices[0].message.audio.data   →  base64 字符串
Base64 解码后 = 标准 WAV 文件（RIFF 头）
```

- 实测返回 **24kHz / 单声道 / 16bit** WAV，解码即可直接喂 `MediaPlayer`。
- `format` 也支持 `mp3`（返回的是 MP3 数据），**v3.0 统一用 `wav`**，省一层解码。
- 延迟实测 **1.5~6.5s**（首次含加载会偏慢）。
- `message.content` 是空字符串，**不要拿 content 当结果**。

**端到端可靠性已验证**：用 TTS 合成 `"Nice to meet you. What do you do in your free time?"`
再把音频喂回 ASR，**一字不差还原出原句**。

**兜底方案**：若 TTS 链路出问题，切安卓框架自带 `android.speech.tts.TextToSpeech`
（零依赖、离线、免费、能读英文）。**架构上要把「朗读」抽成一个接口**，两种实现可切换。

### 4.4 翻译：MyMemory（免费、无 key）✅ 实测通过

```
GET https://api.mymemory.translated.net/get?q=<文本>&langpair=<源>|<目标>
```

结果路径：`responseData.translatedText`

实测数据：

| 输入 | langpair | 输出 | 耗时 |
|---|---|---|---|
| 今天天气真好，我们一起去公园散步吧。 | `zh-CN\|en` | It's a lovely day, let's go for a walk in the park. | 1.5s |
| 我想表达我对这个问题的看法。 | `zh-CN\|en` | I would like to express my opinion on this issue. | 1.7s |
| Nice to meet you. What do you do in your free time? | `en\|zh-CN` | 很高兴认识你。您在业余时间做什么？ | 0.6s |
| 我喜欢 listening to music 和看电影。 | `zh-CN\|en` | I love listening to music and watching movies. | 0.6s |
| 今天天气真好 | `autodetect\|en` | It's a lovely day. | — |
| Nice to meet you. | `autodetect\|zh-CN` | 很高兴认识你。 | 1.2s |

- **支持 `autodetect` 作为源语言**，实测两个方向都对。
- **方向判定策略（v3.0）**：本地先判——文本里**含 CJK 字符**则目标为 `en`，否则目标为 `zh-CN`；
  再拼成 `autodetect|<目标>` 发出去。
- ⚠️ **坑：空输入 / 非法语言对返回 HTTP 200，但正文是错误信息**：
  - 空文本 → `NO QUERY SPECIFIED. EXAMPLE REQUEST: GET?Q=HELLO&LANGPAIR=EN|IT`
  - 非法语言对 → `'XX' IS AN INVALID SOURCE LANGUAGE ...`
  **必须自己校验**，否则会把错误提示当译文显示给用户。
- 匿名额度约 5000 字符/天（**未官方核实，标注"约"**），个人使用足够。

**其他免费接口实测结论（别再浪费时间试）**：

| 接口 | 结果 |
|---|---|
| **Google 翻译**（`translate.googleapis.com` gtx / `clients5.google.com`） | ❌ **国内完全连不上**（12s 超时，`code=000`）。不能当默认 |
| **有道**（`fanyi.youdao.com/translate`） | ❌ 空响应，已封 |
| **LibreTranslate** 公共实例 | ❌ 502 |
| **百度 sug** | ✅ 通，但返回的是**词典释义**不是句子翻译，不适用 |

### 4.5 屏幕取字：端上 OCR（ML Kit，离线免费）✅ 可行性已实测

用 `com.google.mlkit:text-recognition-chinese:16.0.1`（**bundled 版**）。

- ⚠️ **绝对不能用 unbundled 版**（`com.google.android.gms:play-services-mlkit-*` 那套）：
  它需要 Google Play 服务联网下载模型，**国行 ColorOS 没有 GMS，会不可用**。
  bundled 版模型在编译期打进 APK，**运行时不需要 Google Play 服务，完全离线**。
- 打包时的**硬性要求**：`ndk { abiFilters 'arm64-v8a' }`。
  不加的话 4 种架构的 native 库全打进去，APK 从 **21MB 涨到 51MB**（`.so` 就占 40MB）。
  手机是 arm64，只留这一种。
- 体积代价：arm64 约 **+15MB**（现有 release APK 仅 **500KB**）。
- **已实测**（真机 PJX110 / Android 16，日志为证）：
  - `DynamiteModule: Selected local version of com.google.mlkit.dynamite.text.chinese` → 用本地模块，**没联网下载**
  - `nativeloader: Load .../lib/arm64-v8a/libmlkit_google_ocr_pipeline.so ... ok` → native 库从 APK 加载成功
  - `Loading .../Hani_ctc/optical/conv_model.fb` + `Latn_ctc/...` → 汉字模型 + 拉丁模型都加载了
  - 识别结果：`Nice to meet you` / `很高兴认识你` / `AI English Coach OCR probe` → **中英文全部正确**
  - 从初始化到出结果 **< 100ms**
- 静态证据：`text-recognition-chinese-16.0.1.aar` 里自带 `assets/mlkit-google-ocr-models/`（2.37MB）
  和 `jni/arm64-v8a/libmlkit_google_ocr_pipeline.so`（11MB），是自包含的。

API 用法（已核对字节码确认签名）：

```java
TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build())
    .process(InputImage.fromBitmap(bmp, 0))
    .addOnSuccessListener(t -> t.getText())
    .addOnFailureListener(e -> ...);
```

---

## 5. 录音链路（关键平台知识，均已查证）

### 5.1 MediaProjection 授权（方案 A 的依据）

- ✅ **「会话」与「录音」是两件事**（已验证：现有 `CaptureService` 拿到 MediaProjection 后
  **只把它喂给 `AudioPlaybackCaptureConfiguration`，从不调用 `createVirtualDisplay()`**）。
  所以反复开关"听/暂停"**不需要再授权**。
- Android 14+：`startForeground()` **必须在 `getMediaProjection()` 之前**调用，否则抛 `SecurityException`。
- Android 14+：**`registerCallback()` 必须在 `createVirtualDisplay()` 之前调用**，否则抛
  `IllegalStateException: Must register a callback before starting capture, to manage resources in response to MediaProjection states.`
  > 这条是实测踩到的（探针工程漏了它直接崩）。现有 `CaptureService` 做对了，**不要改坏**。
- 会话终止原因：用户点状态栏提示条、**锁屏**、另一个投屏会话启动、进程被杀。

#### ⚠️⚠️ 重大发现（2026-09-21 实证，直接影响取词方案）

**同一个 MediaProjection 实例只能调用一次 `createVirtualDisplay()`。**

AOSP 服务端源码（`MediaProjectionManagerService`，已留存到 `.local/MEDIAPROJECTION_FINDINGS.md`）：

```java
// Set if MediaProjection#createVirtualDisplay has been invoked previously (it
// should only be called once).
private int mVirtualDisplayId = INVALID_DISPLAY;

public boolean isValid() {
    final boolean virtualDisplayCreated = mVirtualDisplayId != INVALID_DISPLAY;
    final boolean isValid = !hasTimedOut && (mCountStarts <= 1) && !virtualDisplayCreated;
    if (isValid) return true;
    if (shouldMediaProjectionPreventReusingConsent(...)) {   // targetSdk 34+ 启用
        mProjectionGrant.stop(StopReason.STOP_ERROR);         // ⚠️ 整个会话被停！
        throw new SecurityException("Don't take multiple captures by invoking "
            + "MediaProjection#createVirtualDisplay multiple times on the same instance.");
    }
    return false;
}
```

三个关键事实：

1. **`mVirtualDisplayId` 永不被重置**（全文件只在 `notifyVirtualDisplayCreated()` 里赋值，
   没有任何地方改回 `INVALID_DISPLAY`）→ **释放 VirtualDisplay 也没用**，第二次仍然失败。
2. **失败时先 `stop()` 再抛异常** → 不只是"第二次截屏失败"，而是
   **整个投屏会话被销毁，正在录的声音一起断掉**。
3. **兼容开关 `MEDIA_PROJECTION_PREVENTS_REUSING_CONSENT` 在 targetSdk 34+ 启用**
   （本项目 targetSdk 35 → **会生效**）。

**影响范围（重要）**：

| 场景 | 是否受影响 |
|---|---|
| **现有听力录音**（只用令牌喂 AudioPlaybackCapture，从不建 VirtualDisplay） | **不受影响**。已验证 `isValid()` 只在 `DisplayManagerService` 建 VirtualDisplay 时被调用，**纯音频路径根本不经过它** |
| **取词截屏**（需要 VirtualDisplay） | **严重受限**。同一实例只能建一次，第二次会抛异常**并连累整个会话** |

**因此取词的唯一可行架构是「只建一次 VirtualDisplay，常驻复用」**，
用 `VirtualDisplay.setSurface()` 接/断 `ImageReader` 的 surface 取帧，`resize()` 应对尺寸变化。
**详见 `PLAN_v3.md` P4.1 与 §12 待验证项。**

> 另注：源码里还有 **5 分钟超时**（`mDefaultTimeoutMillis = Duration.ofMinutes(5)`，
> 从 MediaProjection 对象创建时刻算起，只作用于 `createVirtualDisplay` 路径）。
> 现有纯音频用法**不受它影响**。

### 5.2 AudioPlaybackCapture

- 需要 `RECORD_AUDIO` 权限 + MediaProjection。
- targetSdk ≥29 默认**允许**被录。通话类音频（`USAGE_VOICE_COMMUNICATION`）在设计上不可录。
- 采样率优先 16000 单声道 PCM16（代码里有 16000→44100→48000 的降级尝试）。

### 5.3 目标 App：西柚英语（已验证可录）

- 包名 `com.xiyou.english`，targetSdk 32，Flutter 应用。
- `adb shell dumpsys media.audio_policy` 确认 `allowPlaybackCapture=true`。

### 5.4 本机调试环境注意（踩过的坑）

- `adb shell input tap` 在**这台 ColorOS 上不生效**（模拟触摸被系统拦截）。
  自动化测试要用 **`am start` 传 extra** 触发（例：`--ez selftest true`），不要依赖点击。
- Git Bash 下 `adb shell ... /sdcard/...` 的**路径会被 MSYS 转换**成 `C:/Program Files/Git/sdcard/...`。
  需要 `export MSYS_NO_PATHCONV=1`。
- 这台机器上 `python` 是 **Windows Store 的假壳**（直接 exit 49，无输出）。
  写测试脚本用 **Node.js**（`C:/Program Files/nodejs/node`，v24）。

---

## 6. 工程与构建（环境坑很多，照抄）

- 目录：`E:/rd/EnglishCoach`，模块 `:app`，namespace / applicationId = `com.rd.englishcoach`。
- **技术栈**：**纯 Java + Android 框架 API**（不用 Kotlin / Compose / OkHttp）。
  主题用 `@style/AppTheme`（继承框架自带 `@android:style/Theme.Material`，见第 10 节）。
  > ⚠️ v3.0 起**首次引入一个第三方依赖**（ML Kit，见 §6.3），这是用户明确同意打破的约定。
  > 除此之外仍然零依赖，**不要顺手加 OkHttp / Retrofit / AndroidX**。
  > （注意：ML Kit 会**传递引入 AndroidX appcompat 与 firebase/datatransport**，这是被动带进来的，
  > 不是我们主动使用；代码里**仍然不要** import AndroidX。）
- 版本组合（**不要改**）：Gradle **8.10.2** / AGP **8.6.1** / `compileSdk 35` / `targetSdk 35` / `minSdk 29` / Java 17
- **JDK 必须用 `E:/JAVA/20`（JDK 20）**，别用 Android Studio 自带的 jbr（JDK 25 不兼容）。
- 仓库：阿里云镜像优先，`maven.google.com` **不通**（实测 `code=000`）。
  现有 `settings.gradle` 已配好镜像，**ML Kit 及其全部传递依赖都能从阿里云镜像下到**（已逐个实测 200）。
- 构建命令：
  ```bash
  cd E:/rd/EnglishCoach
  JAVA_HOME="E:/JAVA/20" ./gradlew test            # 单元测试（v2.0 时 110 个）
  JAVA_HOME="E:/JAVA/20" ./gradlew assembleRelease # 签名发行版
  ```
- **release 签名**：`E:/huaian.jks`（alias `huaian`），凭证在 `keystore.properties`（已 gitignore）。
- **图标**：源图 `E:/rd/icon.png`，自适应图标背景色 `@color/ic_launcher_background`。
- 装机：`adb install -r app/build/outputs/apk/release/app-release.apk`

### 6.1 探针工程（临时用，不是交付物）

`E:/rd/OcrProbe/` 是为验证「国行 ColorOS 能否跑端上 OCR」临时建的**独立工程**
（`com.rd.ocrprobe`，只依赖 ML Kit，不碰主工程）。
它的结论已写进 §4.5，**主工程不要引用它**。

### 6.2 已修复的文档/仓库不一致（P0 已处理）

AGENTS.md 曾声称「v2.0 已完成、tag `v2.0`」，但**实际 git 里只有 `v1.0` / `v1.1` / `v1.2` 三个 tag**，
v2.0 的 **28 个文件长期未提交**（整个 Aurora Glass 视觉改版）。

**已处理**：v3.0 开发前先补齐提交（2 个 commit）并补上 `v2.0` tag，基线现已可信。
> 教训：**先固化基线再做重构**。如果当时直接改代码，这批视觉改动一旦冲突就只能重做。

### 6.3 v3.0 依赖清单

```gradle
dependencies {
    // v3.0 新增：端上 OCR（bundled，模型打进 APK，不依赖 Google Play 服务）
    implementation 'com.google.mlkit:text-recognition-chinese:16.0.1'

    testImplementation 'junit:junit:4.13.2'
    testImplementation 'org.json:json:20231013'
}

android {
    defaultConfig {
        ndk { abiFilters 'arm64-v8a' }   // 必须：否则 APK 从 21MB 涨到 51MB
    }
}
```

---

## 7. 代码结构

```
app/src/main/
  AndroidManifest.xml          权限、两个 Activity、CaptureService(foregroundServiceType=mediaProjection)
  java/com/rd/englishcoach/
    Prefs.java                 所有配置项 + 默认值
    WavUtil.java               PCM16 → WAV 头
    PcmBuffer.java             可增长 PCM 缓冲
    ApiClient.java             HttpURLConnection 调 ASR 与回答模型
    CaptureService.java        前台服务：MediaProjection + AudioRecord + 网络 + 驱动悬浮窗
    ConversationManager.java   对话轮次 + 发给 AI 的消息历史
    HistoryStore.java          转录历史 JSON 持久化
    ListenToggle.java          听/暂停的唯一状态源 + 文案唯一来源
    ServiceStartArgs.java      ACTION_START 入参校验
    FloatingPanel.java         悬浮窗 UI：拖动、字号/宽度、音量条、轮次卡片、重新授权、呼吸动画
    MaxHeightScrollView.java   ScrollView 限高（框架无 maxHeight，自己写）
    MainActivity.java          权限申请、投屏授权、开始/停止服务
    SettingsActivity.java      设置页（改完即时作用到悬浮窗）
  res/
    layout/
      activity_main.xml        主界面：状态卡片 + 渐变 CTA + 操作列表
      activity_settings.xml    设置页：三组卡片分组
      window_panel.xml         悬浮窗面板（玻璃 + 呼吸状态点 + 平滑音量条）
      item_segment.xml         轮次卡片（原文 + 回答 + 操作按钮）
    drawable/                  v2.0 视觉组件库（见第 10.5 节）
    values/
      colors.xml               19 个 design token
      dimens.xml               间距 / 圆角 / 控件高度 token
      strings.xml              所有 UI 文案
      styles.xml               AppTheme + 设置页样式
    mipmap-*/                  图标
  app/src/test/java/...        单元测试（v2.0 时 110 个）
```

**v3.0 计划新增**（文件清单以 `PLAN_v3.md` 为准）：

```
    SpeechPlayer.java          朗读抽象（mimo TTS 实现 + 系统 TTS 兜底）
    TtsClient.java             mimo-v2.5-tts 请求 + base64 WAV 解析
    Translator.java            免费翻译（MyMemory + 方向判定 + 200 假错误识别）
    ScreenTextCapture.java     截一帧屏幕（复用 MediaProjection）+ 裁剪
    GrabOverlay.java           取词时的全屏框选层（截图 + 可拖选择框）
    GrabManager.java           取词状态机 + 取词结果持久化进对话
    ServiceEvents.java         服务起停广播（修 §2.2 第 1 条 bug）
    res/layout/window_grab.xml 框选层布局
    res/layout/item_grab.xml   取词卡片（原文 + 译文 + 两个喇叭）
    res/drawable/…             Tab / 喇叭 / 框选层所需 drawable（复用现有 token）
```

**行为约定**：

- 采集线程只负责读 `AudioRecord` 写入环形缓冲；**网络在单线程 Executor 里串行**；UI 更新走主线程 `Handler`。
- 暂停时**继续读、只是不写入缓冲**，恢复时清空缓冲。
- 点「这句完了」：取快照 → 静音裁剪 → ASR → 回答 → 显示 → 清空缓冲。
- 悬浮窗：`TYPE_APPLICATION_OVERLAY` + `FLAG_NOT_FOCUSABLE`，拖动只绑 `dragBar`；答案列表最多 20 条。

---

## 8. 进度状态

### v1（已完成）

- 全部功能代码：采集链路、整段上传 + ASR、对话上下文 + 参考回答 / 询问AI、转录历史持久化、设置页、悬浮窗。
- 真机联调完成，历史 bug 均已修复。
- **v1.1（tag `v1.1`）**：修设置页显示不全 + ColorOS 悬浮窗权限引导。
- **v1.2（tag `v1.2`）**：修 Android 16 edge-to-edge + 停止后进程退出 + `ServiceLifecycleTest`。

### v2.0（已完成，tag `v2.0`）

- 视觉改版「深空玻璃 Aurora Glass」：design token（19 色）+ drawable 组件库（16 个）+ 全界面重做。
  `versionCode 4` / `versionName 2.0`。
- **已于 v3.0 开发前补齐提交与 tag**（此前长期未提交，见 §6.2）：
  - `43be7e1` design token + drawable 组件库
  - `b63dc59` 主界面/设置页/悬浮窗/轮次卡片视觉重做 ← **tag `v2.0` 打在这里**
- 在 tag 目标的干净 worktree 上验证过：**110 个测试全绿（0 failures / 0 errors）**、
  `assembleRelease` 出包成功（release APK **511KB**）、正式证书签名。

### v3.0（本次开发，进行中）

- ✅ 需求逐条确认完毕（§2.2 / §2.3）。
- ✅ 全部服务端能力实测通过（ASR / 回答 / TTS / 翻译 / 端上 OCR 可行性）。
- ✅ **P0 固化基线**：提交 v2.0（2 个 commit）+ tag `v2.0`，基线已验证可信。
- ✅ **P1**：修状态 bug（服务广播 + 实时刷新）+ 通知栏「停止」真停止。
- ✅ **P2**：朗读基础设施（SpeechPlayer + TtsClient + AudioPlayer）。
- ✅ **P3**：悬浮窗双 Tab 重构（听力 / 取词）。
- ✅ **P4**：取词链路（ScreenTextCapture + GrabOverlay + OcrEngine + Translator + GrabManager）。
- ✅ **P5**：朗读接入 UI（item_segment / item_grab 各两个喇叭）。
- ✅ **P6**：设置页扩展（TTS 配置）+ 默认 Key 留空 + 640dp 一屏守住。
- ✅ **P7**：取词结果接入对话上下文（TurnType.GRAB）。
- ⬜ **P8 真机联调**：等手机连接后验证。
- **158 个测试全绿，APK 21.6MB（arm64 only + ML Kit），正式签名**。

### 后续可做（按优先级）

1. 无障碍服务自动点投屏授权弹窗（方案 B）。
2. 取词的行选 / 精确选词（等框选真机验证后评估）。
3. 应用内检查更新 / 版本号管理。
4. 发音打分、翻译讲解、导出历史等。

---

## 9. 必须一直遵守的用户可见限制（不要"优化掉"）

- 锁屏/息屏 → 系统自动停止投屏，必须重新授权一次。
- 状态栏常驻"正在投屏/录制"提示条，用户点它会停。
- 每句要等约 2~5 秒，且必须联网。
- ASR 听错 → AI 答案跟着偏。
- 只对允许被录的 App 有效（西柚英语已确认允许；通话类永远录不到）。
- **取词时悬浮窗会短暂消失**（技术必需，见 §2.3），翻译完自动恢复。
- **免费翻译质量一般**，且匿名有日额度上限（约 5000 字符/天）。
- **未填 API Key 时**：听力区的「这句完了」/「询问AI」/ 朗读不可用，但**取词翻译仍可用**。
- **APK 从 0.5MB 涨到约 21MB**（ML Kit 端上 OCR 的代价）。
- **取词时不能新建投屏会话**：同一 MediaProjection 只能建一次 VirtualDisplay，
  所以取词是通过一个**常驻的虚拟显示**取帧（§5.1）。若该方案不可行，取词需另寻出路。
- **ColorOS / 一加等 ROM 可能禁止侧载应用开启「显示在其他应用上层」**，应用侧绕不过，只能靠用户侧
  `adb shell appops set com.rd.englishcoach SYSTEM_ALERT_WINDOW allow`。

---

## 10. 视觉设计系统「深空玻璃 Aurora Glass」（v2.0，已确认，勿再改动方向）

> 方案 A 深空玻璃 / 锁定深色 / 覆盖主界面 + 设置页 + 悬浮窗 + 图标。
> 目标观感：深色玻璃质感、青→靛渐变强调色、胶囊按钮、有呼吸感的实时状态、克制的入场动效。
> **v3.0 新增的 UI（Tab、喇叭按钮、取词卡片、框选层）一律复用本节的 token 和 drawable，不要另起一套。**

### 10.1 硬约束（动外观前先读这一节）

1. **零第三方依赖不变**（v3.0 唯一例外是 ML Kit OCR，见 §6.3）。所有圆角、描边、渐变、涟漪、阴影**必须手写 XML drawable**。
2. **不透明卡片 + 对比度 ≥ 4.5:1**：`SegmentCardLayoutTest` 读 `bg_answer.xml` 算 WCAG，断言底板 alpha == 0xFF。
3. **设置页 ≤ 640dp 一屏**：`SettingsLayoutTest` 按 dp 累加，改间距前**先跑它**。
   **实测当前值 `582.8dp / 640dp`（余量仅约 57dp）**。
   > ⚠️ v3.0 要新增朗读/翻译相关设置项，**会撞上这条约束**——见 `PLAN_v3.md` P6 的处置。
4. **view id 一律不许改名**：
   `tvStatus btnStart btnStop btnSettings btnNewChat btnHistory` /
   `etBaseUrl etApiKey btnToggleKey etAsrModel etChatModel etSysPromptLabel etSysPrompt btnFontMinus tvFontValue btnFontPlus btnWidthMinus tvWidthValue btnWidthPlus btnSave btnReset` /
   `root dragBar tvDot tvStatus levelBar btnPause btnReconsent tvMessage scrollTurns turnList inputRow etQuestion btnSendQuestion` /
   `tvTranscriptText tvAnswerLabel tvAnswerText btnAskAnswer btnAskQuestion tvAnswerStatus` /
   `dotStatus btnClose`。
5. **悬浮窗行为逻辑不许改**：`FLAG_NOT_FOCUSABLE` 加/去、拖动只绑 `dragBar`、`FLAG_LAYOUT_NO_LIMITS` 禁用、键盘收起检测、长按 PopupMenu、20 条上限、`MaxHeightScrollView` 限高。
6. **悬浮窗面板永远深色**，透明度保持 `#E6` 级别（≈90%）。
7. **真模糊做不到**：「玻璃」= 半透明 + 1dp 描边 + 纵向微渐变。

### 10.2 颜色 token（`res/values/colors.xml`）

| token | 值 | 用途 |
|---|---|---|
| `bg_base` | `#0B0F17` | 应用根底色 |
| `bg_base_glow` | `#101826` | 根底色径向渐变最亮端 |
| `surface_1` | `#131926` | 一级卡片（含轮次卡片底板） |
| `surface_2` | `#1A2130` | 二级容器 / 输入框 |
| `surface_3` | `#232C3D` | 按下态 / 悬浮态 |
| `panel_glass` | `#E6161A21` | 悬浮窗面板半透明底（**保持 #E6**） |
| `stroke_soft` | `#1AFFFFFF` | 卡片描边（10% 白） |
| `stroke_strong` | `#26FFFFFF` | 聚焦 / 选中描边（15% 白） |
| `text_primary` | `#F2F5FA` | 主文字（16.08:1） |
| `text_secondary` | `#9BA6B8` | 次级文字（7.15:1） |
| `text_tertiary` | `#7C8798` | 占位符/说明（4.83:1） |
| `accent_start` | `#22D3EE` | 渐变起点（青） |
| `accent_end` | `#6D8CF5` | 渐变终点（靛紫） |
| `accent_solid` | `#3DD9E8` | colorAccent / 光标 / 进度条 tint |
| `accent_on` | `#06121A` | **渐变胶囊文字色（深色）** |
| `success` | `#34D399` | 监听中状态点 |
| `warn` | `#FBBF24` | 提示信息 |
| `danger` | `#F87171` | 错误 / 删除 |
| `ripple_light` | `#33FFFFFF` | 涟漪基色 |

⚠️ **主 CTA 胶囊用「亮渐变 + 深色文字」**：深字 `#06121A` on 渐变 `#22D3EE → #6D8CF5` = **10.48:1 → 6.06:1** ✓。

### 10.3 尺寸 token（`res/values/dimens.xml`）

```
间距：space_xs 4dp · space_sm 8dp · space_md 12dp · space_lg 16dp · space_xl 24dp
圆角：radius_sm 8dp · radius_md 12dp · radius_lg 16dp · radius_pill 999dp
控件：height_row 48dp · height_input 40dp · height_chip 32dp · height_cta 52dp
描边：stroke_w 1dp
```

字号：`display 20sp` / `title 16sp` / `body 14sp` / `label 13sp` / `caption 11sp`。
字体：标题与按钮 `sans-serif-medium`，正文 `sans-serif`。

### 10.4 动效规范

| 场景 | 时长 | 说明 |
|---|---|---|
| 涟漪 / 颜色变化 | 120ms | `RippleDrawable` 默认即可 |
| 卡片入场 | 180ms | `alpha 0→1` + `translationY 8dp→0`，`DecelerateInterpolator` |
| 状态点呼吸 | 1600ms 循环 | `alpha 1.0↔0.45`，`LinearInterpolator` |
| 音量条 | 120ms 插值 | `ObjectAnimator` 平滑到目标值 |

规则：**只做进入动画，不做退出动画**；动画必须在 `hide()` 时 `cancel()`，避免泄漏。

### 10.5 drawable 组件库（`res/drawable/`）

```
bg_app.xml            根背景：layer-list，底层 bg_base + 顶层径向渐变 bg_base_glow
bg_panel.xml          悬浮窗外层：panel_glass + radius_lg + stroke_soft 描边
card_surface.xml      一级卡片：surface_1 + radius_md + stroke_soft 描边
card_row.xml          列表行：透明底 + 涟漪
bg_input.xml          输入框：surface_2 + radius_sm + 聚焦时 stroke_strong
bg_answer.xml         轮次卡片底板：surface_1 实色 + radius_md（**必须不透明**）
bg_chip.xml           chip / 次级按钮：透明 + stroke_soft + radius_sm + 涟漪
pill_primary.xml      主 CTA：accent_start→accent_end 渐变 + radius_pill（⚠️ 文字用 accent_on）
btn_secondary.xml     次按钮：透明 + stroke_soft + radius_pill + 涟漪
btn_text.xml          纯文字按钮：透明 + 涟漪
divider_h.xml         1dp 分隔线，stroke_soft
level_bar.xml         音量条：圆角轨道 + 渐变进度
dot_active.xml        监听中状态点：success 绿 + 外圈发光
dot_idle.xml          停止状态点：text_tertiary 灰
```

### 10.6 主题（`res/values/styles.xml`）

```xml
<style name="AppTheme" parent="@android:style/Theme.Material">
    <item name="android:windowBackground">@drawable/bg_app</item>
    <item name="android:colorPrimary">@color/bg_base</item>
    <item name="android:colorPrimaryDark">@color/bg_base</item>
    <item name="android:colorAccent">@color/accent_solid</item>
    <item name="android:statusBarColor">@color/bg_base</item>
    <item name="android:navigationBarColor">@color/bg_base</item>
    <item name="android:textColorPrimary">@color/text_primary</item>
    <item name="android:textColorSecondary">@color/text_secondary</item>
    <item name="android:textColorHint">@color/text_tertiary</item>
    <item name="android:windowLightStatusBar">false</item>
</style>
```

### 10.7 验证方式

```bash
cd E:/rd/EnglishCoach
JAVA_HOME="E:/JAVA/20" ./gradlew test            # 单元测试必须全绿
JAVA_HOME="E:/JAVA/20" ./gradlew assembleRelease # 必须能出包
```

---

## 11. v3.0 需求详细设计（本次新增，已确认）

### 11.1 修 bug：主界面状态不准

- **根因（已定位，非猜测）**：`MainActivity.updateStatus()` 只读静态字段 `CaptureService.current != null`，
  且**只在 `onResume()` 和少数几个操作后被调用**。
  用户关悬浮窗时人一直停在主界面上（没有切页面 → 不触发 `onResume`），
  服务 `onDestroy` 后 `current = null`，**主界面无从得知** → 状态文字和状态灯停留在「运行中」。
- **修法**：服务起停时**主动广播**（`ServiceEvents`），`MainActivity` 注册接收器实时刷新。
  - 广播要用 **package 限定**（显式 setPackage），API 33+ 注册时带 `RECEIVER_NOT_EXPORTED`。
  - `startService` 成功、`stopSelf`、投屏被系统停止、悬浮窗关闭按钮 —— 四个点都要发。
- **顺带修的第二个 bug**：通知栏「停止」按钮（`ACTION_STOP`）现在只 `stopForeground(REMOVE)`，
  **没有停服务**，后台服务还在跑（按钮名字与行为不符）。要改成真停止并清理悬浮窗。

### 11.2 朗读（`mimo-v2.5-tts`）

- **交互**：ASR 原文 / AI 参考回答 / 取词译文，**各自一个独立小喇叭按钮**。
- 点一下开始读，**再点同一个 = 停止**；**同一时刻只播一路**（播新的先停旧的）。
- 播放中喇叭要有状态反馈（复用 `accent_solid` / `success`，动效遵守 §10.4）。
- **架构**：抽 `SpeechPlayer` 接口，两套实现：
  1. `MimoTtsEngine`（走 §4.3 契约，返回 base64 WAV → `MediaPlayer`）
  2. `SystemTtsEngine`（`android.speech.tts.TextToSpeech` 兜底）
- **网络与缓存**：TTS 走 `networkExec` 串行执行；同一段文本**缓存已生成的音频**，重复点读不重复请求。
- **失败处理**：TTS 报错 → 自动降级系统 TTS；两者都失败 → 面板提示，不崩。
- **设置页新增**：TTS 模型名、朗读声音（下拉，值域见 §4.3）。

### 11.3 悬浮窗双 Tab 重构

- 顶部两个 Tab：`听力` / `取词`，**窗口尺寸不变**（不能因为加 Tab 把字幕挡住）。
- `听力` 页签 = 现有的全部内容（`dragBar` + 音量条 + `btnPause` + `tvMessage` + `scrollTurns` + `inputRow`）。
- `取词` 页签 = 新增：一个「取词」大按钮 + 取词卡片列表 + 取词状态行。
- **`dragBar` 必须留在 Tab 外面**（拖动栏在哪个页签都要能拖）。
- **不能破坏的既有行为**（见 §10.1 第 5 条）：`FLAG_NOT_FOCUSABLE` 加/去、键盘检测、长按菜单、20 条上限。
  - ⚠️ 注意：`inputRow` 只在「听力」页签可见，键盘检测逻辑要跟着页签走，别在「取词」页签误判。

### 11.4 框选取词（核心新流程）

```
① 用户在「取词」页签点「取词」
② FloatingPanel.hide()                      ← 必须，否则自己的文字被截进去
③ 等约 100ms 让窗口真正从屏幕消失
④ ScreenTextCapture 从**常驻的 VirtualDisplay**（在 startCapture 里只建一次）取一帧全屏 Bitmap
   ⚠️ **不能在这里新建 VirtualDisplay**（同一实例只能建一次，第二次会停掉整个会话，见 §5.1）
⑤ 显示 GrabOverlay：全屏铺这张静态截图 + 半透明遮罩 + 可拖动的选择框
⑥ 用户手指拖出一个矩形 → 抬起手指
⑦ 按矩形裁剪 Bitmap → ML Kit OCR 出文字（<100ms）
⑧ 方向判定（含 CJK → en，否则 → zh-CN）→ MyMemory 翻译 → 得到译文
⑨ 移除 GrabOverlay → FloatingPanel.show() → 切到「取词」页签 → 添加取词卡片
⑩ 取词结果**加入对话上下文**，用户切到「听力」页签可对这句话继续「询问AI」
```

**约束与坑**：

- ⚠️ **同一 MediaProjection 只能建一次 VirtualDisplay**（§5.1 已实证）：此 VirtualDisplay 必须在
  `startCapture()` 里提前建好并**常驻整个会话**，取词只是从它的 ImageReader 取帧。
  取词逻辑**不得** `release()` 这个 VirtualDisplay（释放后就再也建不回来了）。
  > 该方案能否跑通**尚未真机验证**，见 `PLAN_v3.md` §12 V1。
- 建虚拟显示前**必须先 `registerCallback`**（§5.1，实测踩过）。
- 悬浮窗隐藏期间**不能丢状态**：隐藏前记住「当前页签 / 拖动位置 / 面板可见性」，恢复时原样还原。
- 框选层必须是 `TYPE_APPLICATION_OVERLAY`，且**只负责取词**，用完立刻移除。
- 框太小 / 没框到东西 → 给明确提示，不要静默失败。
- OCR 出来是空字符串 → 提示「没认到文字，把框拉大一点」。
- 翻译返回 200 但是错误体（§4.4 坑）→ 必须识别并当错误处理。
- 取词期间**投屏被系统停止**（锁屏等）→ 要能优雅退出框选层并提示重新授权。
- **取词不依赖 API Key**（OCR 端上、翻译免费），没填 key 也能用。

### 11.5 默认 API Key 留空

- `Prefs.DEF_API_KEY` 改为 `""`（其余默认值**一律不动**）。
- 注意 `Prefs.nullSafe()` 现有语义是「空则回退默认值」——**默认值变空后会把用户输入的空原样存**，
  这条逻辑要重新检查，别出现"填了 key 又被默认值覆盖"。
- 未填 key 时的行为：点「这句完了」/「询问AI」/ 朗读 → 面板给一句明确提示
  （如「请先到设置页填 API Key」），**不发请求、不报网络错误**。
- 取词翻译**不受影响**。

---

## 附：各版本改动记录

| 版本 | tag | 主要改动 |
|---|---|---|
| v1.0 | `v1.0` | 初版：全部功能代码 |
| v1.1 | `v1.1` | 设置页一屏放得下 + ColorOS 悬浮窗权限引导 + 清理 emoji |
| v1.2 | `v1.2` | Android 16 edge-to-edge 修 + 停止后进程退出 + ServiceLifecycleTest |
| v2.0 | `v2.0` | 视觉改版「深空玻璃 Aurora Glass」：design token + drawable 组件库 + 全界面重做 |
| v3.0 | （待发） | 修状态 bug + 朗读（mimo TTS）+ 悬浮窗双 Tab + 框选取词翻译 + 默认 key 留空 |

> `v2.0` 的提交时间晚于其功能完成时间：功能早已完成，但直到 v3.0 开发前才补齐提交与 tag（见 §6.2）。
> 它包含两个 commit：`43be7e1`（token + drawable）、`b63dc59`（界面重做，tag 指此）。
