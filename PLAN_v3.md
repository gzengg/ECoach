# PLAN_v3.md — ECoach v3.0 开发计划

> 依据：`AGENTS.md`（唯一需求依据）。**本计划不含 OCR 真机测试**（用户要求先忽略）。
> 沟通一律用中文。每个阶段结束都要**跑测试 + 出包**，不得留下红测试。

---

## 0. 总览

| 阶段 | 内容 | 交付物 | 预估 |
|---|---|---|---|
| **P0** | 固化基线：提交 v2.0 未提交改动 + 补 `v2.0` tag | 干净的 git 基线 | ✅ 完成 |
| **P1** | 修状态 bug（服务广播）+ 通知「停止」真停止 | `ServiceEvents` + 主界面实时刷新 | 小 |
| **P2** | 朗读基础设施：`SpeechPlayer` 抽象 + `TtsClient` + 系统 TTS 兜底 | 可单测的朗读层 | 中 |
| **P3** | 悬浮窗双 Tab 重构（听力 / 取词） | 双页签面板，窗口尺寸不变 | 中 |
| **P4** | 取词链路：截屏（常驻 VirtualDisplay）+ 框选层 + OCR + 翻译 | 完整框选取词 | **大（含§12 V1 前置验证）** |
| **P5** | 朗读接入 UI：三段文字各自的喇叭 | 每段文字可点读 | 中 |
| **P6** | 设置页扩展 + 默认 key 留空 | 新设置项，一屏约束不破 | 中 |
| **P7** | 取词结果接入对话上下文 | 取词后可追问 | 小 |
| **P8** | 测试补齐 + 真机联调 + v3.0 发版 | tag `v3.0` | 中 |

**依赖关系**：P0 → P1；P2 → P5；P3 → P4 → P7；P6 与 P3/P4 解耦可并行；P8 收尾。

> ⚠️ **P4 有一个前置阻塞项（§12 V1）**：AOSP 源码已实证「同一 MediaProjection 只能建一次
> VirtualDisplay」，取词方案必须改为常驻复用，但复用是否可行**尚未真机验证**。
> 因此 P4 开工前先做 §12 V1 的四项验证。

---

## P0 — 固化基线（✅ 已完成）

**为什么**：AGENTS.md 曾声称 v2.0 完成，实际 git 只有 `v1.0/v1.1/v1.2` 三个 tag，
**v2.0 的 28 个文件长期未提交**（整个 Aurora Glass 视觉改版）。基线不可信就没法安全回滚。

**已执行**：

1. ✅ 确认工作区就是「v2.0 完成态」：`./gradlew test` 全绿、`assembleRelease` 出包成功。
2. ✅ 分两个 commit 提交（便于回溯，且**未混入 v3.0 代码与文档**）：
   - `43be7e1` `feat(v2.0): 深空玻璃 Aurora Glass — design token + drawable 组件库`（19 个文件）
   - `b63dc59` `feat(v2.0): 主界面/设置页/悬浮窗/轮次卡片视觉重做`（8 个文件）
3. ✅ 打 `v2.0` tag（annotated，指向 `b63dc59`）。
4. ✅ **在 tag 目标的干净 worktree 上复验**：110 个测试全绿（0 failures / 0 errors）、
   release APK **511KB**、正式证书签名（`CN=zengderui`）。

**验收结果**：`git status` 干净（待提交仅 v3.0 文档）；`git tag -l` 含 `v2.0`；测试全绿。

> 教训：**先固化基线再做重构**。否则这 28 个文件的视觉改动一旦与新功能冲突就只能重做。

---

## P1 — 修状态 bug

### P1.1 新增 `ServiceEvents.java`

```java
public final class ServiceEvents {
    public static final String ACTION_STATE_CHANGED = "com.rd.englishcoach.STATE_CHANGED";
    public static final String EXTRA_RUNNING = "running";
    public static final String EXTRA_REASON  = "reason"; // started/stopped/projection_killed/panel_closed
}
```

- 发广播用 `intent.setPackage(getPackageName())`（**限定包名**，避免外泄）。
- `MainActivity` 用 `registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)`（API 33+），
  低版本走 `registerReceiver(receiver, filter)`。

### P1.2 四个发广播的点

| 时机 | 位置 | 语义 |
|---|---|---|
| 服务启动成功 | `CaptureService.startCapture()` 末尾 | running=true |
| 服务销毁 | `CaptureService.onDestroy()` | running=false, reason=stopped |
| 投屏被系统停止 | `ProjectionCallback.onStop()` | running=false, reason=projection_killed |
| 悬浮窗关闭按钮 | `PanelCallback.onClose()` | running=false, reason=panel_closed |

### P1.3 `MainActivity` 实时刷新

- `onResume()` 里注册接收器，`onPause()` 里注销（**不要用 onDestroy，避免泄漏**）。
- 收到广播 → `updateStatus(running)` 更新 `tvStatus` + `dotStatus`。
- 保留 `onResume()` 的兜底查询（防止广播丢失）。

#### ⚠️ 必须同时修的第二个 bug

通知栏「停止」按钮（`ACTION_STOP`）**行为与文案不符**：

```java
if (intent == null || ACTION_STOP.equals(intent.getAction())) {
    // 注释写明：不直接 stopSelf()，只移除前台通知，
    // 由 MainActivity.onDestroy(isFinishing) 或悬浮窗关闭按钮决定何时真正结束服务
    stopForeground(STOP_FOREGROUND_REMOVE);
    return START_NOT_STICKY;
}
```

这段**当初是故意这么写的**（为了不在 Activity 销毁时误删悬浮窗），不是意外 bug。
但按钮偏写着「停止」：用户点下去**通知消失、服务还在跑、悬浮窗还在**，语义混乱。

改法：让 `ACTION_STOP` 成为真正的「停止」——隐藏悬浮窗 → `stopSelf()` → 发 running=false 广播。

> ⚠️ **改这里要特别小心**：现有注释说明了既有约定——
> `MainActivity.onDestroy(isFinishing)` 也会 `stopService`，那时**不该**再动悬浮窗。
> 所以要区分两种入口：
> - **来自通知栏的 ACTION_STOP** → 真停止 + 收悬浮窗
> - **来自 Activity 销毁的 stopService** → 只结束服务，悬浮窗清理交给既有逻辑
> 别把「面板关闭」和「Activity 关闭」搞混（这是历史回归过的地方）。

**验收**：
- 关悬浮窗 → 主界面（停留不动）状态立刻变「未启动」，状态灯变灰。
- 通知栏点「停止」→ 服务真的停、悬浮窗消失、主界面状态正确。
- 新增单测 `ServiceEventsTest`。

---

## P2 — 朗读基础设施（先做纯逻辑，不碰 UI）

### P2.1 `SpeechPlayer.java`（接口 + 状态）

```java
public interface SpeechPlayer {
    interface Listener { void onState(String key, State s); }  // key = 哪段文字
    enum State { IDLE, LOADING, PLAYING, ERROR }
    void speak(String key, String text, String langHint);
    void stop();
    boolean isSpeaking();
    void release();
}
```

**必须遵守交互约定**（用户明确要求）：
- 同一时刻**只播一路**；播新的先 `stop()` 旧的。
- 再点同一个 key = 停止。

### P2.2 `TtsClient.java`

按 `AGENTS.md` §4.3 契约实现：

- `POST {baseUrl}/chat/completions`
- body：`{model, messages:[{role:"assistant", content:text}], modalities:["text","audio"], audio:{voice, format:"wav"}}`
- ⚠️ **文本必须放 assistant 角色**（放 user 报 `must contain an assistant role for TTS model`）。
- 解析 `choices[0].message.audio.data` → base64 解码 = **WAV 字节**
- ⚠️ **不要读 `message.content`**（它是空的）。
- 复用 `ApiClient` 的 POST 封装风格（`HttpURLConnection` + `setFixedLengthStreamingMode` + 15s/120s 超时）。
- 拆出纯函数便于单测：
  - `static String buildTtsBody(String text, String model, String voice, String format)`
  - `static byte[] extractAudioBytes(String json)`（返回解码后字节，失败抛明确异常）

### P2.3 `SystemTtsEngine`（兜底）

- `android.speech.tts.TextToSpeech`，语言按 `langHint`（en / zh-CN）。
- 初始化失败 / `isLanguageAvailable` 为假 → 上报不可用。

### P2.4 播放与缓存

- WAV 字节 → 写临时文件（`getCacheDir()`）→ `MediaPlayer.setDataSource(path)` → 播放。
  > 用文件而不是 `MediaPlayer` 直接吃字节：框架的 `MediaDataSource` 在 API 23+ 有，但写文件最省事、最好调试。
- **同文本缓存**：`Map<String(key), byte[]>`，重复点读不再发请求（省额度、省 2~6 秒）。
- 播放结束 / 出错回调 → 通知 `FloatingPanel` 复位喇叭状态。
- `release()` 时必须 `MediaPlayer.release()` + 删临时文件（防泄漏，参考 `FloatingPanel.hide()` 的动画 cancel 约定）。

**验收**：新增 `TtsClientTest`（请求体正确 / base64 解析 / 空音频抛异常 / 只认 `audio.data`）。

---

## P3 — 悬浮窗双 Tab 重构

### P3.1 布局改造 `window_panel.xml`

结构（**窗口尺寸不变**，`dragBar` 留在 Tab 外）：

```
root
├── dragBar                       ← 两页签共用，始终可拖
├── tabBar                        ← 新增：两个 Tab 按钮
├── 听力页 (tabListening)          ← 现有内容整体包一层
│   ├── levelBar
│   ├── btnPause / btnReconsent
│   ├── tvMessage
│   ├── scrollTurns(+turnList)
│   └── inputRow(etQuestion/btnSendQuestion)
└── 取词页 (tabGrab)               ← 新增
    ├── btnGrabStart（「取词」大按钮）
    ├── tvGrabStatus
    └── scrollGrabs(+grabList)
```

**新增 view id**（命名沿用现有风格，不要动老 id）：

```
tabBar tabListening tabGrab
btnGrabStart tvGrabStatus scrollGrabs grabList
```

### P3.2 `FloatingPanel` 状态

- 记住 `currentTab`，`hide()` 前保存、`show()` 后恢复（P4 取词要靠它还原）。
- 切 Tab 只改 `Visibility`，**不重建视图**（保住 20 条上限、滚动位置、入场动画状态）。

### P3.3 必须保住的既有行为（回归风险最高的地方）

| 行为 | 风险 |
|---|---|
| `FLAG_NOT_FOCUSABLE` 加/去 | `inputRow` 只在听力页；**在取词页不要误判键盘收起** |
| 键盘收起检测（`OnGlobalLayoutListener`） | 现在监听 root 高度；包了一层后**高度语义变了**，要改成监听听力页高度 |
| 点击空白收起输入框 | 同上，`inputRow` 的坐标判断要跟着页签走 |
| 长按转录文字 PopupMenu | 不动 |
| 每次操作后 `MaxHeightScrollView.setMaxHeight(dpToPx(260))` | 两个页签各自的滚动容器都要设 |
| `MainLayoutTest.floatingPanel_noLayoutNoLimits` | 源码里不能出现 `FLAG_LAYOUT_NO_LIMITS` |

**验收**：新增 `FloatingPanelTabTest`（布局契约：两个 tab 存在、听力页初始可见、取词页初始 gone、
`dragBar` 在 tabBar 之外、`inputRow` 在听力页内）。

---

## P4 — 框选取词（最大的一块）

### P4.1 `ScreenTextCapture.java`（❗设计因新发现而重写）

#### 🚨 先读这条：同一 MediaProjection 只能建一次 VirtualDisplay

**原计划（用同一个 MediaProjection 每次取词新建 VirtualDisplay）不可行，已废弃。**
AOSP 服务端源码实证（详见 `AGENTS.md` §5.1 与 `.local/MEDIAPROJECTION_FINDINGS.md`）：

- `mVirtualDisplayId` 一旦被赋值就**永不重置**（释放 VirtualDisplay 也没用）；
- 第二次 `createVirtualDisplay` 会**先 `stop()` 把整个会话干掉**，再抛 `SecurityException`
  → **正在录的声音也会一起断掉**；
- targetSdk 35 会触发这个兼容开关（`MEDIA_PROJECTION_PREVENTS_REUSING_CONSENT`）。

#### ✅ 改为「一次建好、常驻复用」

在 `CaptureService.startCapture()` 里建**唯一一个** `VirtualDisplay`，绑到 `ImageReader` 的 surface：

```
MediaProjection
   └─ VirtualDisplay（只建这一次，活到会话结束）
        └─ ImageReader.surface   ← 取词时从这里 acquireLatestImage()
```

取词时**不重建 display**，只从常驻的 ImageReader 取一帧。

但这样又要解决新问题：

1. **常驻 VirtualDisplay 的耗电/开销**。若不能接受，用 `VirtualDisplay.setSurface(null)` 平时断开、
   取词时再 `setSurface(reader.getSurface())` 接回。
   ⚠️ **`setSurface(null)` 是否合法未验证**（见 §12 待验证项）。
2. **分辨率/横竖屏变化** → 用 `VirtualDisplay.resize(w, h, dpi)`。
3. **ImageReader 常驻会持有缓冲** → 控制 `maxImages`（取 1~2）并及时 `close()` 每帧。
4. `VirtualDisplay` / `ImageReader` 的生命周期归 `CaptureService`，**不归取词逻辑**；
   ⚠️ **绝不能因为取词失败而 `release()` 掉它**——那就再也建不回来了。

#### 其他细节（仍然适用）

- 处理 `rowStride` padding（copyPixelsFromBuffer 后按 `img.getWidth()` 裁剪）。
- 屏幕尺寸用 `WindowManager.getDefaultDisplay().getRealSize()`（含横屏）。
- ⚠️ **建 VirtualDisplay 前必须先 `registerCallback`**（实测踩过
  `IllegalStateException: Must register a callback before starting capture, to manage resources in response to MediaProjection states.`）。
  > 现在要**提前到 `startCapture()` 里建**，而那里已经 `registerCallback` 了，天然满足这条。

### P4.2 `GrabOverlay.java` + `window_grab.xml`

- 全屏 `TYPE_APPLICATION_OVERLAY` 窗口，内容 = 定格截图 `ImageView` + 半透明遮罩 + 选择框。
- 交互：`ACTION_DOWN` 记起点 → `MOVE` 画实线框 → `UP` 结束。
- 最小尺寸校验（比如 < 32dp 视为误触，提示重选）。
- 提供「取消」出口（点框外 / 一个取消按钮），**不能把用户困在框选层**。
- 遮罩用「四块暗色区域围绕选择框」实现（比 `PorterDuff` 更简单稳定）。

### P4.3 `OcrEngine.java`（ML Kit 封装）

```java
TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build())
    .process(InputImage.fromBitmap(cropped, 0))
    .addOnSuccessListener(t -> t.getText())
```

- 只留 arm64（`ndk { abiFilters 'arm64-v8a' }`，见 AGENTS.md §4.5）。
- 识别结果做**去换行整理**（框选区域内的多行合并成一句，便于翻译）。
- 空结果 → 明确错误（"没认到文字，把框拉大一点"）。

### P4.4 `Translator.java`

- 方向判定（纯函数，可单测）：
  ```
  含 CJK 字符（\u4E00-\u9FFF）→ 目标 en
  否则                      → 目标 zh-CN
  langpair = "autodetect|" + 目标
  ```
- URL：`https://api.mymemory.translated.net/get?q=<urlencoded>&langpair=<...>`
- 结果取 `responseData.translatedText`。
- ⚠️ **必须识别 200 假错误**（实测踩到）：
  - 空文本 → 正文含 `NO QUERY SPECIFIED`
  - 非法语言对 → 正文含 `INVALID SOURCE LANGUAGE`
  - 判据：结果里含这些特征串，或 `responseStatus != 200` → 当错误抛出。
- 超时要短（15s 连接 / 20s 读），失败给可读提示。

### P4.5 `GrabManager.java`（状态机）

```
IDLE → HIDING_PANEL → CAPTURING → SELECTING → OCR → TRANSLATING → RESTORING → IDLE
```

每一步都要有**失败出口**（统一 `abort(reason)`：移除框选层 → 恢复面板 → 提示错误）。

**必须处理的异常路径**：

| 情况 | 处理 |
|---|---|
| 截图失败 | 恢复面板 + 提示「截屏失败，请重新授权」+ 显示 `btnReconsent` |
| 用户取消框选 | 恢复面板，**不报错**（静默返回） |
| 框太小 | 提示重选，**不退出框选层** |
| OCR 空结果 | 恢复面板 + 「没认到文字」 |
| 翻译失败 | 恢复面板 + 显示**原文**（原文有价值，不能一起丢） |
| 取词期间投屏被停 | 退出框选层 + 恢复面板 + 提示重新授权 |
| 面板恢复时服务已死 | 不要再 addView（`root.getParent()` 判空），静默结束 |

### P4.6 时序要点

- `panel.hide()` 之后要**等约 100~150ms 再截图**，否则窗口可能还在合成帧里（会被截进去）。
- 全程在 `bgHandler` / 主线程之间切换要清晰：**截屏在 bg、UI 操作在主线程**。
- 取词期间用户可能**切 App / 锁屏**，`GrabOverlay` 要能感知并收敛。

**验收**：
- 新增 `TranslatorTest`（方向判定 / 200 假错误识别 / 结果解析）。
- 新增 `GrabStateTest`（状态机：正常流转 + 每个失败出口都能回到 IDLE 且面板已恢复）。
- 真机验证（**本计划内不做，用户要求先忽略 OCR 测试**）：框选字幕 / 题干各一次。

---

## P5 — 朗读接入 UI

### P5.1 `item_segment.xml` 加喇叭

- 原文 `tvTranscriptText` 旁 → 喇叭（key = `turn:<id>:transcript`）
- 回答 `tvAnswerText` 旁 → 喇叭（key = `turn:<id>:answer`）
- 新增 id：`btnSpeakTranscript`、`btnSpeakAnswer`
- ⚠️ **底板对比度不能破**（`SegmentCardLayoutTest` 会算 WCAG，且断言 alpha==0xFF）。
- ⚠️ 按钮行已经有两个 chip（`btnAskAnswer`/`btnAskQuestion`），再加两个要**防止换行挤爆**——
  建议喇叭做成**小图标**（32dp chip，复用 `bg_chip`），或把喇叭放在标签行右侧。

### P5.2 `item_grab.xml`（取词卡片）

- 原文 + 译文 + 两个喇叭（`btnSpeakSrc` / `btnSpeakDst`）。
- 语言提示：原文可能中可能英（自动判向），喇叭要按**实际语言**选声音（中文 → 冰糖，英文 → Mia）。

### P5.3 交互细节

- 点喇叭 → 立刻切到 `LOADING` 视觉（TTS 要 1.5~6.5s，**必须有"正在加载"反馈**，否则像卡死）。
- 播放中 → `PLAYING` 视觉；再点 → `stop()`。
- 出错 → 复位 + 面板提示（不崩）。

**验收**：`SpeechPlayerTest`（同时只播一路 / 再点即停 / key 隔离）。

---

## P6 — 设置页扩展 + 默认 key 留空

### P6.1 默认 key 留空

- `Prefs.DEF_API_KEY = ""`。
- ⚠️ **重点检查 `nullSafe()`**：现在是「空 → 回退默认值」。
  默认值变空后逻辑仍成立（空 → 空），但要确认**不会把用户填的值误判为空**、也不会把空值存成默认。
  建议改成显式语义：`putApiKey(v)` 直接存 `v == null ? "" : v.trim()`，**不做回退**。
- 未填 key 的判定：`apiKey().isEmpty()` → 面板提示「请先到设置页填 API Key」，**不发请求**。
- 取词翻译**不受影响**（OCR 端上 + 翻译免费）。

### P6.2 新增设置项

| 项 | 类型 | 默认 |
|---|---|---|
| TTS 模型名 | 输入框 | `mimo-v2.5-tts` |
| 朗读声音 | 下拉 | 英文 `Mia` / 中文 `冰糖`（见 AGENTS.md §4.3 值域） |
| 朗读开关 | 开关 | 开 |

> ⚠️ **`SettingsLayoutTest` 约束：设置页 ≤ 640dp 一屏**。
> **实测当前值：`582.8dp / 预算 640dp` —— 只剩约 57dp 余量。**
> 新增 2~3 个控件（按 `height_row 48dp` 或 `height_input 40dp` 算，约 80~120dp）**必然超标**。
>
> 处置方案（按优先级）：
> 1. **优先复用现有行的空位**（比如把 TTS 模型名和朗读声音做成一行内的两个控件），
>    而不是各起一行；
> 2. 「朗读开关」考虑**不做成独立行**，直接并进朗读声音那一行；
> 3. 每条改动后立刻跑 `./gradlew test` 看 `SettingsLayoutTest` 报出的实际 dp 数字，
>    **不要凭感觉猜**（测试失败信息里会打印 `设置页内容高 XXdp / 预算 640dp`）；
> 4. 实在放不下 → 拆「高级设置」二级页（**这会改交互，必须先问用户**，不擅自做）。
>
> **原则：先跑测试看具体数值，不要凭感觉改间距。**

**验收**：`PrefsTest` 补「默认 key 为空」「空 key 判定」；`SettingsLayoutTest` 保持全绿。

---

## P7 — 取词结果接入对话上下文

- 取词成功 → 用 `ConversationManager` 存一条 `Turn`，类型用新增的 `TurnType.GRAB`（或复用 `TRANSCRIPT`，
  **需在 `buildMessages()` 里把「原文 + 译文」一起给 AI**，让追问有上下文）。
- ⚠️ `ConversationManager` 有单测（`ConversationManagerTest`），改 `buildMessages` 要同步改测试。
- ⚠️ **不要污染听力区的卡片列表**：取词卡片只出现在「取词」页签，但**要能出现在 AI 的消息历史里**。
- `HistoryStore` 是否记录取词 → **v3.0 不做持久化**（AGENTS.md §3 明确不做）；只在内存里保留，供追问用。

**验收**：`ConversationManagerTest` 补「取词条目进入消息历史」。

---

## P8 — 测试、联调、发版

### P8.1 单元测试清单（新增）

| 测试 | 覆盖 |
|---|---|
| `TtsClientTest` | 请求体（assistant 角色 / modalities / voice）、base64 WAV 解析、错误 JSON |
| `SpeechPlayerTest` | 同刻一路、再点即停、key 隔离、缓存命中不再请求 |
| `TranslatorTest` | 方向判定（含 CJK）、`autodetect` 拼装、**200 假错误识别**、正常解析 |
| `GrabStateTest` | 状态机全路径 + 各失败出口回到 IDLE |
| `ServiceEventsTest` | 广播 action/extras 约定 |
| `FloatingPanelTabTest` | 双 Tab 布局契约、`dragBar` 在 Tab 外、`inputRow` 在听力页 |
| `PrefsTest`（扩展） | 默认 key 为空、空 key 判定、不再回退 |
| `SegmentCardLayoutTest`（扩展） | 新增喇叭后对比度仍 ≥4.5:1、底板仍不透明 |

### P8.2 真机联调清单

1. 状态 bug：关悬浮窗 → 主界面立刻变「未启动」（**不切页面**）。
2. 通知栏「停止」→ 服务真停。
3. 朗读：点原文 / 点回答各读一次；连点两次 = 停；两个喇叭快速交替点不会双响。
4. 双 Tab：切换后拖动、字号、宽度、音量条都正常。
5. 取词：框选字幕一句 → 出原文 + 译文；框选中文 → 译成英文。
6. 取词异常：空框、锁屏中断、未填 key。
7. 未填 key 时听力区给提示、取词仍可用。
8. 回归：号码 20 条上限、长按菜单、输入框收起、重新授权按钮。

### P8.3 发版

- `app/build.gradle`：`versionCode 5` / `versionName "3.0"`。
- `./gradlew test` 全绿 + `./gradlew assembleRelease` 出包 + `apksigner verify`。
- `adb install -r` 装机自测。
- 提交 + 打 tag `v3.0` + 更新 AGENTS.md §8 与版本记录表。
- ⚠️ 检查 APK 体积（预期 **~21MB**，含 ML Kit）；确认只有 `arm64-v8a`。

---

## 风险与处置

| 风险 | 影响 | 处置 |
|---|---|---|
| 🚨 **同一 MediaProjection 只能建一次 VirtualDisplay**（AOSP 已实证） | 取词原方案不可行；若建第二次会**停掉整个会话、录音一起断** | 改为常驻复用（P4.1）；**开工前先做 §12 V1 验证** |
| 取词与录音共用 MediaProjection | 不小心 release 掉常驻 VirtualDisplay 就再也建不回来 | 生命周期归 `CaptureService`；取词失败**不得 release**；`GrabStateTest` 覆盖 |
| 面板隐藏/恢复的时序 | 截图把面板自己截进去 | hide 后延时 100~150ms；真机验证 |
| 键盘检测逻辑被 Tab 包层破坏 | 输入框收起异常（历史回归过） | 改为监听听力页高度；`FloatingPanelTabTest` 守 |
| 设置页一屏约束 | `SettingsLayoutTest` 变红（**实测余量仅 57dp**） | P6.2 的处置方案 1/2/3；每步跑测试看数值 |
| TTS 延迟 1.5~6.5s | 用户以为卡死 | 必须有 LOADING 反馈 + 同文本缓存 |
| 系统 TTS 兜底在国行 ROM 不可用 | 朗读没有退路 | §12 V5 先验证；不可用则提示装语音包 |
| 免费翻译质量/额度 | 译文不好或有日限 | 翻译抽一层，设置页可换地址；失败时**保留原文** |
| 未提交的 v2.0 改动被覆盖 | 丢失视觉改版 | ✅ **已消除**（P0 已提交并打 tag） |

---

## 不在本计划内（明确不做）

- 端上 OCR 的真机验证（用户要求先忽略；可行性已在 `AGENTS.md` §4.5 有实测结论）。
- 行选 / 精确选词、云端 OCR、取词历史持久化、中英双语语音细化设置、
  无障碍自动授权（方案 B）、自动切句、发音打分、息屏后台录音、上架、分享。

---

## §12 待验证项（用户要求：先标为待验证，不写代码）

> 这些是**已知存疑、必须真机实测才能定论**的点。在拿到实测结果之前，
> **不要按任何一种假设下手实现**（否则可能白写）。

### V1【阻塞 P4，最高优先】VirtualDisplay 常驻复用是否可行

**背景**：AOSP 源码已确认同一 MediaProjection **只能建一次** VirtualDisplay（详见 `AGENTS.md` §5.1）。
所以取词必须改成「一次建好、常驻复用」。但下面三点**全部未验证**：

| # | 待验证问题 | 为什么重要 | 验证方式 |
|---|---|---|---|
| V1.1 | `VirtualDisplay.setSurface(null)` 是否合法？接回 `setSurface(reader.getSurface())` 后能否正常取帧？ | 决定能否"平时断开省电、取词时接回" | 探针：建一次 display → setSurface(null) → setSurface(reader) → acquireLatestImage |
| V1.2 | VirtualDisplay 常驻（一直挂着 surface）是否明显耗电 / 影响录音质量？ | 若能接受，就不需要 V1.1 的复杂方案 | 真机跑 10 分钟，看耗电与录音是否正常 |
| V1.3 | 反复 `acquireLatestImage()` + `close()` 是否稳定？会不会耗尽缓冲 / 卡住？ | 决定能否连续多次取词 | 探针：循环取帧 20 次，看是否丢帧/报错 |
| V1.4 | 是否真的**第二次建一定失败**？（如果只是抛异常不 stop，影响判定就不同） | 源码显示会 `stop()` 整个会话，但需实测确认严重程度 | 探针：故意建第二次，观察抛什么异常、录音是否真的断 |

**建议做法**：在 `E:/rd/OcrProbe` 探针工程里加一个 `DisplayReuseTest`，
用上述四步一次跑完（约 15 分钟，只需用户点一次授权）。

### V2【影响 P6】设置页一屏约束怎么解

当前实测 **582.8dp / 640dp，余量仅 57dp**，而 P6 要新增 2~3 个控件（约 80~120dp）。

- 需先按 P6.2 的优先方案 1/2 试排，**跑测试看实际 dp**。
- 若仍超标 → 拆「高级设置」二级页属于**改交互**，必须先问用户。

### V3【影响 TTS 体验】TTS 首次延迟与稳定声音

- 首次调用实测 **6.5s**（含模型加载），后续 1.5~2s。
- ⚠️ 需确认：**同一 voice 是否稳定可用**；中文文本 + 英文 voice（或反之）会怎样。
- 待验证：`冰糖` / `Mia` 各试中英文文本，看是否报错或读音异常。

### V4【影响取词翻译】MyMemory 日额度与长文本

- 匿名额度「约 5000 字符/天」**未官方核实**。
- 待验证：超限时的响应形式（是否仍返回 200 + 错误正文）。
- 待验证：长文本（>500 字符）是否被截断。

### V5【影响 P2 兼容性】系统 TTS 在国行 ColorOS 上的可用性

- `android.speech.tts.TextToSpeech` 是 TTS 的兜底方案，但**国行 ROM 可能没预装 TTS 引擎**
  （或未下载英文语音包）。
- 待验证：`TextToSpeech` 初始化是否成功、英文是否可读。
- 若不可用 → TTS 兜底策略需重设（可能需要提示用户安装语音包）。
