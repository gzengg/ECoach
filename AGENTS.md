# AGENTS.md — 英语陪练（悬浮窗版）

> 本文件是**后续开发的唯一依据**。里面每一条"已确认"都是和用户逐条确认过的结论，不要重新假设、不要擅自扩大范围。
> 与用户沟通一律用中文。

---

## 1. 项目是什么

一个**自用、侧载、不上架**的 Android App（项目名 EnglishCoach，应用显示名 **ECoach**，包名 `com.rd.englishcoach`）。

用户在**西柚英语**（`com.xiyou.english`）里练听力，本 App 用一个**悬浮窗**浮在学习 App 上方：
听到一句想问的内容 → 点悬浮窗里的「这句完了」→ App 把刚才那段**系统内声音**转成英文文字（ASR）→ 再让 AI 给一句**英文参考回答** → 显示在悬浮窗里，用户照着念。

**核心诉求：整个流程都在悬浮窗内完成，不切来切去。**

---

## 2. 需求结论（已逐条确认，勿再追问）

| # | 项 | 确认结果 |
|---|---|---|
| 1 | 声音来源 | 英语学习 App（西柚英语）播放的声音，即**录系统内声音**，不是麦克风 |
| 2 | 实时程度 | **不需要接近实时**，按句/按段出结果即可 |
| 3 | 怎么切句 | **用户手动点「这句完了」**，不做自动停顿检测 |
| 4 | AI 输出 | **只要英文参考回答**：不要中文翻译、不要讲解、不要多种说法 |
| 5 | 手机 | PJX110（OPPO/一加，ColorOS），**Android 16 / API 36** |
| 6 | 授权方式 | **方案 A**：授权一次 → 保持 MediaProjection 会话不释放 → 悬浮窗里随时开关，不再弹窗 |
| 7 | 悬浮窗功能 | 开始听/暂停听开关、AI 英文参考回答、手动「这句完了」按钮、字号可调、窗口大小可调、可拖动不遮挡字幕 |
| 8 | API Key | **写死为默认值，但 App 里能改成别的**（Base URL / Key / 模型名都可改） |

**用户明确没要的**（见第 4 节，不要自作主张加回悬浮窗）：显示识别原文、一键复制、历史记录、最小化成小球、自动按停顿切句。
> 例外：**"显示识别原文"在设置页留了一个开关，默认关闭**——因为它对排查"是 ASR 听错了还是 AI 答偏了"极其有用，属于调试价值，不占悬浮窗版面。

---

## 3. v1 做什么（范围冻结）

1. 打开 App → 点「开始监听」→ 系统弹一次投屏授权 → 点「开始录制」→ 会话一直有效。
2. 悬浮窗（`TYPE_APPLICATION_OVERLAY`）：可拖动、半透明、不挡字幕、字号与窗口宽度可调。
3. 悬浮窗内三件事：**开始听/暂停听**、**「这句完了」**、**英文参考回答列表**（最新在最上，可滚动）。
4. 点「这句完了」后链路：截取最近 N 秒音频（环形缓冲）→ 编 WAV → ASR 出原文 → `deepseek-flash` 出英文参考回答 → 显示。
5. 设置页：Base URL / API Key / ASR 模型名 / 回答模型名 / 最大截取秒数 / 字号 / 窗口宽度 / 显示原文开关 / 系统提示词，全部可改，带默认值。
6. 音量指示条：让用户一眼看出"到底有没有录到声音"（同时也是最重要的自测手段）。
7. 投屏被系统停止（锁屏、用户点状态栏提示条、别的投屏抢占）时，悬浮窗显示提示 + **「重新授权」按钮**，点一下就能重新走授权（App 有悬浮窗权限，可后台拉起 Activity）。

## 4. v1 明确不做（记在待办，别顺手实现）

- 无障碍服务自动点授权弹窗（**方案 B**）：用户已同意先只做方案 A；等 A 跑通再真机验证 B 是否可行（Android 16 上能否稳定点中无确证）。
- 自动按停顿切句、录自己的声音 / 发音打分、中文翻译与讲解、历史记录持久化与导出、一键复制、最小化成悬浮小球、息屏后台继续录、分享给别人用、上架商店。

---

## 5. 服务端 API（已实测通过，含坑）

- **Base URL**：`https://mimo.ezlook.top/v1`
- **API Key**：`sk-REDACTED`
  （⚠️ 这是用户自己的额度凭证。写进 App 时同时保留"可在设置页修改"；不要把本文件外传。）
- `/v1/models` 返回 10 个模型：`deepseek-flash`、`deepseek-v4-pro-0813`、`glm-5.3`、`glm-5.3-flash`、`mimo-v2.5`、`mimo-v2.5-asr`、`mimo-v2.5-pro`、`mimo-v2.5-tts`、`mimo-v2.5-tts-voiceclone`、`mimo-v2.5-tts-voicedesign`。
  `mimo-v2.5-tts` 调用返回 **503 无可用渠道**，别拿它生成测试音频。

### 5.1 ASR：`mimo-v2.5-asr`

`POST /v1/chat/completions`，请求体：

```json
{"model":"mimo-v2.5-asr","messages":[{"role":"user","content":[
  {"type":"input_audio","input_audio":{"data":"<base64>","format":"wav"}}]}]}
```

实测结论：
- `data` 里**直接放纯 base64，不要加 `data:audio/wav;base64,` 前缀**（加了没验证过，别乱改）。
- **16kHz 单声道 PCM16 WAV 可以**；**48kHz 立体声也可以**（服务端自己重采样），所以 App 里**不需要写重采样**。仍优先按 16kHz 单声道采集：上传体积小 4 倍，更快。
- 结果在 `choices[0].message.content`，纯文本。
- 延迟实测：11 秒音频 ≈ 4.9s；4 秒音频 ≈ 0.84s；偶发一次 50s（冷启动/排队），属异常值不是常态。

### 5.2 回答：`deepseek-flash`

`POST /v1/chat/completions`，`messages` = `[{role:system, content:<提示词>}, {role:user, content:<ASR 原文>}]`，`temperature` 0.7。

- ⚠️ **坑：该模型会先吐 `reasoning_content`（思考过程）**。流式时**必须只显示 `content`、过滤掉 `reasoning_content`**，否则悬浮窗会先冒一堆思考文字。**v1 直接用非流式**（一次拿完整答案），更简单更稳；非流式的 `message.content` 是干净的。
- 实测延迟 1.7~5s，输出就是**纯英文、1~2 句、口语化**，可直接照念，无需额外调教。
- 已验证的系统提示词（写在 `Prefs` 默认值里，设置页可改）：

```
You help a Chinese learner practice English listening and speaking.
You receive the English transcript of what the other speaker just said (from speech recognition, so it may contain small errors).
Reply with ONE natural English reference answer that the learner can say out loud.
Rules: output only the English answer; no quotes, no explanation, no Chinese, no bullet points; 1-2 sentences; natural spoken style.
If the transcript is not a question, give a natural thing the learner could say next.
```

### 5.3 网络实现约定

用框架自带的 `HttpURLConnection` + `org.json` + `android.util.Base64`（**不引入 OkHttp/Retrofit**，见第 7 节）。
超时：连接 15s，读 120s（ASR 可能慢）。base64 后请求体较大（25 秒 16k 单声道 ≈ 800KB → base64 ≈ 1.1MB），**要用 `setFixedLengthStreamingMode`**。HTTP ≥400 时读 `getErrorStream()` 并把错误体截断后展示给用户。

---

## 6. 录音链路（关键平台知识，均已查证）

### 6.1 MediaProjection 授权（方案 A 的依据）

- 官方原文：*"Your app must request user consent before each media projection session. A session is a single call to `createVirtualDisplay()`. A `MediaProjection` token must be used only once."*
  → **"会话"= 一次 `createVirtualDisplay()`，不是一次录音**。只要不释放 MediaProjection 会话，之后在悬浮窗里反复开关"听/暂停"**不需要再授权**。这是方案 A 成立的依据。
- Android 14+：`createVirtualDisplay()` 前**必须**注册 `MediaProjection.Callback`；`startForeground()`（带 `mediaProjection` 类型）**必须在 `getMediaProjection()` 之前**调用，否则抛 `SecurityException`。
- 会话终止的原因（要正确处理 `onStop()`）：用户点状态栏提示条、**锁屏**、另一个投屏会话启动、进程被杀。
- Android 15 QPR1+：状态栏会有醒目提示条，用户可点它停止；**锁屏时投屏自动停止**。

### 6.2 AudioPlaybackCapture

- 需要 `RECORD_AUDIO` 权限 + MediaProjection；`AudioPlaybackCaptureConfiguration.Builder(projection)` + `addMatchingUsage(USAGE_MEDIA / USAGE_UNKNOWN / USAGE_GAME)`；`AudioRecord.Builder.setAudioPlaybackCaptureConfig()`。
- 能否录到**对方 App**，取决于对方 App 的 `allowAudioPlaybackCapture`：AOSP 原文 *"Application targeting an SDK < Q are considered opt-out by default. Application targeting an SDK >= Q are considered opt-in by default."* → targetSdk ≥29 默认**允许**被录。
- 通话类音频（`USAGE_VOICE_COMMUNICATION`）在设计上不可录，绕不过去。
- 采样率优先 16000 单声道 PCM16，失败再退 44100 / 48000；用 `AudioRecord.getState() == STATE_INITIALIZED` 判断成功。
- **待实测**：只建 MediaProjection、**不建虚拟显示**时，AudioPlaybackCapture 是否在本机可用（官方示例是音频专用路径，理论上不需要 `createVirtualDisplay`）。若失败，再补一个 1x1 的虚拟显示（`ImageReader` surface）兜底。**这是第一个要在真机上验的点。**

### 6.3 目标 App：西柚英语（已验证可录）

- 包名 `com.xiyou.english`，versionName 4.9.41，**targetSdk 32**，minSdk 24，Flutter 应用。
- 清单里**没有** `allowAudioPlaybackCapture="false"`。
- 静态扫描：播放用 **ijkplayer**（普通媒体播放），**无** Agora/腾讯 TRTC/Zego 等实时语音 SDK，**无** Widevine/DRM。
- **决定性证据**（系统自己的清单，可直接复查）：
  ```
  adb shell dumpsys media.audio_policy | sed -n '/Allow playback capture log/,+120p' | grep xiyou
  → - uid=10409, allowPlaybackCapture=true , packageName=com.xiyou.english
  ```
  结论：**西柚英语允许被录，方案成立。**
- 复查目标 App 的其他手段：
  ```bash
  APK=$(adb shell pm path com.xiyou.english | sed 's/package://' | tr -d '\r')
  adb pull "$APK" base.apk
  "$LOCALAPPDATA/Android/Sdk/cmdline-tools/latest/bin/apkanalyzer.bat" manifest print base.apk > manifest.xml
  grep -i allowAudioPlaybackCapture manifest.xml
  ```

---

## 7. 工程与构建（环境坑很多，照抄）

- 目录：`E:/rd/EnglishCoach`，模块 `:app`，namespace / applicationId = `com.rd.englishcoach`。
- **技术栈刻意选最保守的**：**纯 Java + Android 框架 API + 零第三方依赖**（不用 AndroidX、不用 Kotlin、不用 Compose、不用 OkHttp）。
  原因：本机 `maven.google.com` **超时被墙**，依赖越少编译成功率越高。UI 用 XML + `findViewById`，主题直接用 `@android:style/Theme.DeviceDefault.DayNight`。
- 版本组合（本地已有缓存，**不要改**）：
  - Gradle **8.10.2**（`~/.gradle/wrapper/dists/gradle-8.10.2-bin` 已缓存）
  - AGP **8.6.1**（`~/.gradle/caches/.../com.android.tools.build/gradle/8.6.1` 已缓存）
  - `compileSdk 35` / `targetSdk 35` / `minSdk 29`，Java 17 源码级别
  - build-tools 用 AGP 默认（本机有 34.0.0、36.0.0）；platform 已装 `android-35`
- **JDK 必须用 `E:/JAVA/20`（JDK 20）**：
  ⚠️ Android Studio 自带的 `jbr` 是 **JDK 25**，与 Gradle 8.10.2 不兼容，**别用它**。
- 仓库：阿里云镜像优先（`maven.aliyun.com/repository/{google,gradle-plugin,central}` 均已验证可下载 AGP 8.6.1），后接 `google()/mavenCentral()`。`repo1.maven.org`、`services.gradle.org`、`dl.google.com` 可通；`maven.google.com` 不通。
- 构建命令：
  ```bash
  cd E:/rd/EnglishCoach
  JAVA_HOME="E:/JAVA/20" ./gradlew test            # 单元测试
  JAVA_HOME="E:/JAVA/20" ./gradlew assembleRelease # 签名发行版
  ```
- **release 签名**：`E:/huaian.jks`（JKS，alias `huaian`，storePass `Z20081127z`，keyPass `z20081127`）。
  凭证写在项目根 `keystore.properties`（已 gitignore，不入库）；文件缺失时 release 退回 debug 签名，保证别的机器也能编译。
  产物：`app/build/outputs/apk/release/app-release.apk`。校验：
  ```bash
  "$LOCALAPPDATA/Android/Sdk/build-tools/36.0.0/apksigner.bat" verify --print-certs app/build/outputs/apk/release/app-release.apk
  ```
- **图标**：源图 `E:/rd/icon.png`（2048×2048，圆角方形蓝色徽标）。
  各密度 `mipmap-*/ic_launcher.png`、`ic_launcher_round.png`、`ic_launcher_foreground.png` 由一次性 Java 程序（ImageIO，逐步折半缩放）生成；
  `mipmap-anydpi-v26/ic_launcher.xml` 用自适应图标（前景内容占 108dp 画布的 65%，落在 66dp 安全区内；背景色 `@color/ic_launcher_background`）。
- 装机 / 调试：
  ```bash
  export PATH="$LOCALAPPDATA/Android/Sdk/platform-tools:$PATH"   # 必须先放 platform-tools
  adb devices        # 设备：16728157 (PJX110, Android 16)
  adb install -r app/build/outputs/apk/debug/app-debug.apk
  ```
  ⚠️ PATH 里若有旧版 adb（1.0.32）会干扰，出现 `adb server version doesn't match` / `device unauthorized`，处理：`adb kill-server && adb start-server`。

---

## 8. 代码结构（约定）

```
app/src/main/
  AndroidManifest.xml          权限、两个 Activity、CaptureService(foregroundServiceType=mediaProjection)
  java/com/rd/englishcoach/
    Prefs.java                 所有配置项 + 默认值（Base URL/Key/模型/字号/宽度/提示词）
    WavUtil.java               PCM16 → WAV 头
    PcmBuffer.java             可增长 PCM 缓冲（暂停时上传整段）
    ApiClient.java             HttpURLConnection 调 ASR 与回答模型（含错误体截断展示）
    CaptureService.java        前台服务：MediaProjection 会话 + AudioRecord 采集 + 网络 + 驱动悬浮窗
    ConversationManager.java   对话轮次 + 发给 AI 的消息历史
    HistoryStore.java          转录历史 JSON 持久化
    ListenToggle.java          听/暂停的<b>唯一状态源</b> + 文案唯一来源
    ServiceStartArgs.java      ACTION_START 入参校验（哨兵值避开 RESULT_OK=-1）
    FloatingPanel.java         悬浮窗 UI：拖动、字号/宽度、音量条、轮次卡片、重新授权
    MaxHeightScrollView.java   ScrollView 限高（框架无 maxHeight，自己写）
    MainActivity.java          权限申请（录音/通知/悬浮窗）、发起投屏授权、开始/停止服务、重新授权入口
    SettingsActivity.java      设置页（改完即时作用到悬浮窗）
  res/layout/{activity_main,activity_settings,window_panel,item_segment}.xml
  res/drawable/{bg_panel,bg_chip,bg_answer,ic_stat_mic}.xml   ic_stat_mic 同时用作通知小图标
  res/values/{strings,colors}.xml
  res/mipmap-*/                图标（见第 7 节）
```

主界面（`activity_main.xml`）**刻意只留「状态 + 5 个按钮」**：标题交给 ActionBar（`@string/app_name`），
不放副标题、不放「怎么用 / 已知限制」大段说明——这些内容属于 AGENTS.md 与本节，不属于用户界面。

行为约定：
- 采集线程只负责读 `AudioRecord` 写入环形缓冲；**网络在单线程 Executor 里串行**（避免并发打同一接口）；UI 更新走主线程 `Handler`。
- 暂停时**继续读、只是不写入缓冲**（避免 AudioRecord 缓冲溢出），恢复时清空缓冲。
- 点「这句完了」：取快照 → 轻量静音裁剪（首尾静音，阈值 ~300，留 0.3s 余量）→ 若有效时长 < 0.5s 提示"没听到声音"→ 否则 ASR → 回答 → 显示；然后清空缓冲。
- 悬浮窗：`TYPE_APPLICATION_OVERLAY` + `FLAG_NOT_FOCUSABLE`，拖动只绑在标题栏上，避免误触；答案列表最多保留 20 条，最新在最上。
- 前台服务通知常驻，带「停止」动作；通道 id `capture`。
- 回答列表默认只显示英文答案；"显示原文"开关打开时，在答案上方加一行灰色小字原文。

---

## 9. 进度状态

已完成：
- 需求确认、API 实测、平台可行性验证（西柚英语 `allowPlaybackCapture=true`）、构建环境勘察。
- 全部功能代码：采集链路、整段上传 + ASR、对话上下文 + 参考回答 / 询问AI、转录历史持久化、设置页、悬浮窗（拖动 / 字号 / 宽度 / 音量条 / 长按复制删除 / 重新授权）。
- 真机联调完成，历史 bug（授权后服务不启动、面板状态不一致、白底白字、输入框闪退、键盘收起）均已修复。
- **代码整理**：删掉已被取代的死代码（`PcmRing`、`SegmentStore` 及其测试），删掉死配置（`Prefs.maxSeconds`）、死方法（`ApiClient.answer`、`CaptureService.getHistoryStore`）；文案收敛到 `strings.xml` / `ListenToggle`，不再散落在 UI 代码里。
- **主界面精简**：只保留「状态 + 5 个按钮」，删掉副标题与「怎么用 / 已知限制」大段文字。
- **改名 + 图标**：应用显示名改为 `ECoach`；图标由 `E:/rd/icon.png` 生成（含自适应图标）。
- **签名发行版**：`keystore.properties` + `assembleRelease`，产物 `app-release.apk` 已用 `apksigner` 验证通过。

后续可做（按优先级）：
1. 无障碍服务自动点投屏授权弹窗（方案 B，见第 4 节）。
2. 应用内检查更新 / 版本号管理（当前 `versionCode 1`，每次发版需手动递增）。
3. 第 4 节列出的其余未做项（发音打分、翻译讲解、导出历史等）。

## 10. 必须一直遵守的用户可见限制（不要"优化掉"）

- 锁屏/息屏 → 系统自动停止投屏，必须重新授权一次。
- 状态栏常驻"正在投屏/录制"提示条，用户点它会停。
- 每句要等约 2~5 秒，且必须联网。
- ASR 听错 → AI 答案跟着偏（这是保留"显示原文"开关的理由）。
- 只对允许被录的 App 有效（西柚英语已确认允许；通话类内容永远录不到）。
