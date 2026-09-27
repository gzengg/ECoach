package com.rd.englishcoach;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.os.IBinder;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 前台服务：MediaProjection + AudioRecord + 可增长缓冲区 + 对话历史 + ASR→AI。
 *
 * <p>暂停 = 上传整段录音 → ASR → 显示原文。用户可选「看参考回答」或「询问AI」。</p>
 */
public class CaptureService extends Service {

    private static final String TAG = "CaptureSvc";
    private static final String CH_ID = "capture";
    private static final int NOTIF_ID = 1;

    public static final String ACTION_START  = "com.rd.englishcoach.START";
    public static final String ACTION_STOP   = "com.rd.englishcoach.STOP";
    public static final String EXTRA_RESULT_CODE = "rc";
    public static final String EXTRA_RESULT_DATA  = "rd";

    public static CaptureService current;

    private MediaProjection projection;
    private boolean stoppedFromNotification = false;
    private AudioRecord audioRecord;
    private PcmBuffer buffer;
    private ConversationManager conversation;
    FloatingPanel panel;
    private HandlerThread bgThread;
    private Handler bgHandler;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ExecutorService networkExec;
    private final ListenToggle listen = new ListenToggle();
    private volatile boolean stopped = false;
    private int sampleRate = 16000;
    private HistoryStore history;
    // v3.0 取词
    private ScreenTextCapture screenCapture;
    private GrabManager grabManager;
    private GrabOverlay grabOverlay;
    // 朗读链（v4）：离线优先 → 在线 → 系统 TTS（最后应急）
    private SpeechPlayer speechPlayer;
    // 识别链（v4）：离线优先 → 在线；实例长期持有，模型才能缓存住
    private AsrChain asrChain;

    // ── 生命周期 ────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        current = this;
        createChannel();
        bgThread = new HandlerThread("capture-bg");
        bgThread.start();
        bgHandler = new Handler(bgThread.getLooper());
        networkExec = Executors.newSingleThreadExecutor();

        // 朗读链（v4）：按运行模式组装——离线 Piper 优先（装了才用），在线 mimo 兼底，
        // 系统 TTS 只作最后应急。没装离线模型时行为与旧版完全一致。
        speechPlayer = buildSpeechChain();
        speechPlayer.setListener(this::onSpeechState);
        asrChain = new AsrChain(this);

        // 预热离线识别模型（借 BaiYunGe 的 warmup 思路）：开始监听即后台加载，
        // 用户说完第一句时模型已就绪，不用再干等 1~3 秒。
        final AsrChain chain = asrChain;
        networkExec.execute(() -> {
            // 服务被销毁/重建后这个任务就不该再跑（免得白加载一次模型）
            if (asrChain == chain) chain.warmUp();
        });
    }

    /**
     * 组装朗读降级链。
     *
     * <p>自动（默认）= 离线 → 在线 → 系统 TTS；只选在线/离线时就不装另一边，避免无谓的失败重试。
     * 离线 Piper 未安装时它自己会报「不可用」并让链回落在线。</p>
     */
    private SpeechPlayer buildSpeechChain() {
        SpeechPlayer offline = new SherpaTtsEngine(this);
        SpeechPlayer online = new MimoTtsEngine(this);
        SpeechPlayer system = new SystemTtsEngine(this);
        switch (new Prefs(this).engineMode()) {
            case Prefs.MODE_ONLINE:
                return new FallbackSpeechPlayer(online, system);
            case Prefs.MODE_OFFLINE:
                return new FallbackSpeechPlayer(offline, system);
            default:
                return new FallbackSpeechPlayer(offline, new FallbackSpeechPlayer(online, system));
        }
    }

    /** 朗读状态反馈：改喇叭按钮颜色；ERROR = 两者都失败，面板提示，不崩（§11.2）。 */
    /** 历史页朗读用：朗读链由服务长期持有，Activity 只读。 */
    SpeechPlayer speech() { return speechPlayer; }

    /** 历史有新记录 → 通知主界面（历史 Tab 角标）。 */
    private void notifyHistoryChanged() {
        sendBroadcast(ServiceEvents.buildHistoryBroadcast(this));
    }

    private void onSpeechState(String key, SpeechPlayer.State state, String error) {
        mainHandler.post(() -> {
            if (panel != null) {
                panel.setSpeakState(key, state);
                if (state == SpeechPlayer.State.ERROR) {
                    panel.showMessage("朗读失败: " + (error != null ? error : "未知错误"));
                }
            }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIF_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);

        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            // 通知栏「停止」= 真停止：藏窗口 → 停服务。与「Activity 关闭时 stopService」区分开。
            stoppedFromNotification = true;
            if (panel != null) panel.hide();
            broadcastState(false, ServiceEvents.REASON_NOTIFICATION_STOP);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_START.equals(intent.getAction())) {
            boolean hasRc = intent.hasExtra(EXTRA_RESULT_CODE);
            int rc = intent.getIntExtra(EXTRA_RESULT_CODE, ServiceStartArgs.NO_RESULT_CODE);
            Intent data = readProjectionData(intent);
            Log.i(TAG, "ACTION_START hasRc=" + hasRc + " rc=" + rc + " data=" + (data != null));
            if (!ServiceStartArgs.canStart(hasRc, rc, data)) {
                Log.e(TAG, "Missing result code or data");
                stopSelf();
                return START_NOT_STICKY;
            }
            startCapture(rc, data);
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        stopped = true;
        stopForeground(STOP_FOREGROUND_REMOVE);
        releaseCapture(); // 内部已调 listen.onCaptureStopped()
        // 通知栏停止：panel.hide() 已在 ACTION_STOP 里处理，此处不重复。
        // Activity 关闭时 stopService：Activity 本身管自己的生命周期，此处也不动 panel。
        if (networkExec != null) networkExec.shutdownNow();
        if (bgThread != null) bgThread.quitSafely();
        if (speechPlayer != null) {
            speechPlayer.stop();
            speechPlayer.release();
            speechPlayer = null;
        }
        if (asrChain != null) {
            // 释放离线识别模型占的 native 内存（一次加载可能上百 MB）
            asrChain.release();
            asrChain = null;
        }
        broadcastState(false, ServiceEvents.REASON_STOPPED);
        current = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private static Intent readProjectionData(Intent intent) {
        if (Build.VERSION.SDK_INT >= 33) {
            return intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class);
        }
        @SuppressWarnings("deprecation")
        Intent legacy = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        return legacy;
    }

    // ── 启动采集 ────────────────────────────

    private void startCapture(int resultCode, Intent data) {
        // 重新授权会二次进入这里：先释放上一次的采集资源（含旧 VirtualDisplay），
        // 否则旧 VirtualDisplay 泄漏。面板暂不撤（万一新授权失败，还要用它提示）。
        releaseCapture();

        buffer = new PcmBuffer();
        conversation = new ConversationManager(50);
        history = new HistoryStore(CaptureService.this);

        MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
        projection = mpm.getMediaProjection(resultCode, data);
        if (projection == null) { notifyReconsentNeeded(getString(R.string.msg_projection_failed)); return; }
        projection.registerCallback(new ProjectionCallback(), bgHandler);

        audioRecord = createAudioRecord(projection);
        if (audioRecord == null) { notifyReconsentNeeded(getString(R.string.msg_audio_init_failed)); return; }

        listen.onCaptureStarted();
        // 撤掉旧面板再建新的：否则会叠出两个悬浮窗，
        // 点「取词」时藏的是新窗口、旧窗口留在屏上 → 看起来「点了没反应」。
        if (panel != null) panel.hide();
        panel = new FloatingPanel(this, new PanelCallback());
        panel.show();
        panel.setListening(listen.isListening());

        // 初始化取词：常驻 VirtualDisplay + ImageReader
        screenCapture = new ScreenTextCapture(getSystemService(WindowManager.class));
        if (!screenCapture.init(projection)) {
            // 不再静默：取词会不可用，用户点「取词」时会看到原因 + 重新授权入口
            Log.e(TAG, "ScreenTextCapture init failed: " + screenCapture.lastError());
        }
        grabManager = new GrabManager();
        grabOverlay = new GrabOverlay(this, new GrabOverlay.Callback() {
            @Override public void onRegionSelected(android.graphics.Bitmap region) {
                grabManager.onRegionSelected(region);
            }
            @Override public void onCancelled() {
                grabManager.onCancelled();
                mainHandler.post(() -> panel.showRestore());
            }
        });

        bgHandler.post(this::readLoop);
        Log.i(TAG, "Capture started");
        broadcastState(true, ServiceEvents.REASON_STARTED);
    }

    private void releaseCapture() {
        listen.onCaptureStopped();
        if (audioRecord != null) {
            try { audioRecord.stop(); } catch (Exception ignored) {}
            try { audioRecord.release(); } catch (Exception ignored) {}
            audioRecord = null;
        }
        if (projection != null) {
            try { projection.stop(); } catch (Exception ignored) {}
            projection = null;
        }
        // 释放常驻 VirtualDisplay（会话结束）
        if (screenCapture != null) {
            screenCapture.release();
            screenCapture = null;
        }
        if (grabOverlay != null) {
            grabOverlay.dismiss();
            grabOverlay = null;
        }
    }

    // ── AudioRecord ────────────────────────

    private AudioRecord createAudioRecord(MediaProjection proj) {
        int[] rates = {16000, 44100, 48000};
        for (int rate : rates) {
            try {
                AudioPlaybackCaptureConfiguration capCfg =
                        new AudioPlaybackCaptureConfiguration.Builder(proj)
                                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                                .build();
                AudioFormat format = new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build();
                int minBuf = AudioRecord.getMinBufferSize(rate,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                AudioRecord rec = new AudioRecord.Builder()
                        .setAudioPlaybackCaptureConfig(capCfg)
                        .setAudioFormat(format)
                        .setBufferSizeInBytes(Math.max(minBuf, rate * 2))
                        .build();
                if (rec.getState() == AudioRecord.STATE_INITIALIZED) {
                    sampleRate = rate;
                    Log.i(TAG, "AudioRecord: " + rate + "Hz");
                    return rec;
                }
                rec.release();
            } catch (Exception e) {
                Log.w(TAG, "AudioRecord failed at " + rate + "Hz: " + e.getMessage());
            }
        }
        return null;
    }

    // ── 读取循环 ────────────────────────────

    private void readLoop() {
        if (audioRecord == null) return;
        try { audioRecord.startRecording(); }
        catch (Exception e) {
            Log.e(TAG, "startRecording failed: " + e);
            notifyReconsentNeeded(getString(R.string.msg_record_start_failed, e.getMessage()));
            return;
        }
        short[] buf = new short[4096];
        while (!stopped && audioRecord != null) {
            int n = audioRecord.read(buf, 0, buf.length);
            if (n <= 0) break;
            if (listen.isListening() && buffer != null) {
                byte[] bytes = new byte[n * 2];
                for (int i = 0; i < n; i++) {
                    bytes[i * 2]     = (byte) (buf[i] & 0xFF);
                    bytes[i * 2 + 1] = (byte) ((buf[i] >> 8) & 0xFF);
                }
                buffer.write(bytes, 0, bytes.length);
                if (panel != null) {
                    long sum = 0;
                    for (int i = 0; i < n; i++) sum += (long) buf[i] * buf[i];
                    panel.setLevel(Math.min(100, (int) Math.sqrt(sum / n) / 100));
                }
            }
        }
    }

    // ── 暂停 → 上传整段 → ASR → 显示原文 ────

    private void doPause() {
        // 不拦 API Key：离线识别不需要 key（守卫在 OnlineAsrEngine / ApiClient 里）。
        // 两者都不可用时由 AsrChain 抛出可读提示，不阻止用户暂停。
        // 暂停采集
        listen.toggle();
        if (panel != null) panel.setListening(listen.isListening());

        if (buffer == null || buffer.size() == 0) {
            if (panel != null) panel.showMessage(getString(R.string.msg_no_audio));
            return;
        }

        byte[] pcm = buffer.snapshotAndClear();
        if (panel != null) {
            // 把「实际录到多长」显示出来：识别不完整时第一眼就能分清是
            // 「没录到」还是「录到了但识别截断」（真机查过这个，见 OfflineAsrEngine 类注释）
            panel.setStatus(getString(R.string.msg_recognizing_audio,
                    String.format(java.util.Locale.US, "%.1f 秒",
                            pcm.length / 2.0 / sampleRate)));
        }

        networkExec.execute(() -> {
            try {
                byte[] trimmed = trimSilence(pcm);
                if (trimmed.length < 3200) { // < 0.5s
                    postMessage(getString(R.string.msg_too_short));
                    return;
                }
                // v4：整段 PCM 交给识别链（离线优先 → 在线），WAV 封装在引擎内完成
                String transcript = asrChain.transcribe(trimmed, sampleRate);
                if (transcript.isEmpty()) {
                    postMessage(getString(R.string.msg_asr_empty));
                    return;
                }
                Log.i(TAG, "ASR: " + transcript);

                // 转录入对话历史 + 显示卡片
                ConversationManager.Turn turn = conversation.addTranscript(transcript);
                // 持久化到转录历史文件
                if (history != null) {
                    history.appendTranscript(transcript);
                    notifyHistoryChanged();
                }
                if (panel != null) {
                    panel.addTurn(turn);
                    // 状态栏显示识别诊断（音频多长 / 切了几段 / 多少字）：
                    // 真机出现过「1 分钟只识别出一个词」，这行能直接分清是哪一环丢的
                    String note = asrChain.lastNote();
                    panel.setStatus(note != null ? note : listen.statusLabel());
                }
            } catch (Exception e) {
                Log.e(TAG, "Pause/ASR failed", e);
                postMessage(getString(R.string.msg_asr_failed, e.getMessage()));
            }
        });
    }

    // ── 「看参考回答」：调 AI ─────────────────

    private void doAnswer(long turnId) {
        if (conversation == null) return;
        if (!requireApiKey()) return;
        ConversationManager.Turn turn = conversation.findById(turnId);
        if (turn == null) return;
        if (!conversation.beginAnswerRequest(turnId)) return;
        if (panel != null) panel.updateTurn(turn);

        networkExec.execute(() -> {
            try {
                Prefs p = new Prefs(CaptureService.this);
                String[][] msgs = conversation.buildMessages(p.sysPrompt());
                String answer = ApiClient.answerWithHistory(msgs,
                        p.chatBaseUrl(), p.apiKey(), p.chatModel());
                Log.i(TAG, "Answer #" + turnId + ": " + answer);
                // 清空上下文后迟到的回答不能再写进持久化历史（会挂到别的记录上）
                boolean applied = conversation.completeAnswer(turnId, answer);
                if (applied && history != null) {
                    history.updateLastAnswer(answer);
                    notifyHistoryChanged();
                }
                if (panel != null) panel.updateTurn(conversation.findById(turnId));
            } catch (Exception e) {
                Log.e(TAG, "Answer failed #" + turnId, e);
                conversation.failAnswer(turnId, e.getMessage());
                if (panel != null) panel.updateTurn(conversation.findById(turnId));
            }
        });
    }

    // ── 「询问AI」：用户自定义提问 ─────────────

    private void doAskQuestion(long turnId, String question) {
        if (conversation == null || question == null || question.trim().isEmpty()) return;
        if (!requireApiKey()) return;
        ConversationManager.Turn qt = conversation.addQuestion(question.trim());
        if (panel != null) panel.addTurn(qt);

        if (!conversation.beginAnswerRequest(qt.id)) return;
        if (panel != null) panel.updateTurn(qt);

        networkExec.execute(() -> {
            try {
                Prefs p = new Prefs(CaptureService.this);
                String[][] msgs = conversation.buildMessages(p.sysPrompt());
                String answer = ApiClient.answerWithHistory(msgs,
                        p.chatBaseUrl(), p.apiKey(), p.chatModel());
                conversation.completeAnswer(qt.id, answer);
                // 同上：清空上下文后迟到的回答不写持久化历史
                if (conversation.findById(qt.id) != null && history != null) {
                    history.updateLastAnswer(answer);
                    notifyHistoryChanged();
                }
                if (panel != null) panel.updateTurn(conversation.findById(qt.id));
            } catch (Exception e) {
                Log.e(TAG, "AskQuestion failed", e);
                conversation.failAnswer(qt.id, e.getMessage());
                if (panel != null) panel.updateTurn(conversation.findById(qt.id));
            }
        });
    }

    // ── 清空对话上下文 ──────────────────────

    public void clearHistory() {
        if (conversation != null) conversation.clear();
        if (panel != null) {
            panel.clearTurns();
            // 取词卡片也是上下文的一部分（buildMessages 里会作为 [屏幕取词] 发给 AI），
            // 必须一起清，否则面板还显示旧卡片、AI 已经不知道了（UI 与上下文不一致）
            panel.clearGrabCards();
        }
        // 注意：不清空持久化的转录历史文件，让用户随时可查看历史
        Log.i(TAG, "Conversation cleared");
    }

    // ── 取词流程 ─────────────────────────

    private void startGrab() {
        if (screenCapture == null || !screenCapture.isReady()) {
            // 取词不可用必须让用户在「取词」页也看得到，并给出恢复路径（重新授权）
            String why = screenCapture == null ? "截屏通道未初始化" : screenCapture.lastError();
            if (panel != null) {
                panel.switchToTabExternal(1);
                panel.setGrabStatus("取词不可用：" + (why == null ? "请重新授权投屏" : why));
                panel.showMessage(getString(R.string.msg_grab_not_ready));
                panel.setReconsentVisible(true);
            }
            Log.e(TAG, "startGrab aborted, ready=false, why=" + why);
            return;
        }
        // v3.1：不再 hide 面板——只把它变透明，用户不会以为 App 退出了；
        // 截图里也不会带上面板文字（取词前必须先藏自己的文字）
        if (panel != null) panel.setCaptureInvisible(true);
        grabManager.setCallback(new GrabManager.Callback() {
            @Override public void onStateChanged(GrabManager.State s) {
                if (s == GrabManager.State.OCR) {
                    // B：框选完成就立即恢复面板 + 显示「取词识别中…」，
                    // 消掉 OCR/翻译期间 1~3 秒的完全空白期（用户会以为退出的那段）
                    mainHandler.post(() -> {
                        panel.setGrabStatus(getString(R.string.msg_grab_recognizing));
                        panel.switchToTabExternal(1);
                        panel.showRestore(); // 内部会恢复透明度
                    });
                } else if (s == GrabManager.State.IDLE) {
                    mainHandler.post(() -> panel.showRestore());
                }
            }
            @Override public void onCaptureFailed(String r) {
                // 截帧失败也要把面板恢复回来，否则用户会以为悬浮窗消失了
                mainHandler.post(() -> {
                    panel.setGrabStatus("取词失败：" + r);
                    panel.showMessage(r);
                    panel.showRestore();
                });
            }
            @Override public void onOcrResult(String t) {
                mainHandler.post(() -> panel.setGrabStatus("识别到 " + t.length() + " 字符，翻译中…"));
            }
            @Override public void onTranslationResult(String src, String dst) {
                mainHandler.post(() -> {
                    panel.setGrabStatus(null);
                    panel.showRestore();
                    panel.switchToTabExternal(1); // 切到取词页
                    addGrabCard(src, dst);
                });
            }
            @Override public void onOcrFailed(String r) {
                mainHandler.post(() -> { panel.showMessage(r); panel.showRestore(); });
            }
            @Override public void onTranslationFailed(String src, String r) {
                // 面向用户的失败信息必须是可读中文（不许把 API 英文原文抛给用户）
                int res = Translator.errorRes(r);
                final String msg = res == R.string.grab_translate_other
                        ? getString(R.string.grab_translate_other, r) : getString(res);
                mainHandler.post(() -> {
                    panel.showMessage(msg);
                    panel.showRestore();
                    panel.switchToTabExternal(1);
                    addGrabCard(src, "(翻译失败)");
                });
            }
            @Override public void onAborted(String r) {
                mainHandler.post(() -> { panel.showMessage(r); panel.showRestore(); });
            }
        });
        grabManager.startGrab(screenCapture, grabOverlay);
    }

    private void addGrabCard(String source, String translated) {
        // P7: 接入对话上下文
        long grabTurnId = -1;
        if (conversation != null) {
            ConversationManager.Turn turn = conversation.addGrab(source, translated);
            grabTurnId = turn.id;
            if (history != null) {
                history.appendGrab(source, translated);
                notifyHistoryChanged();
            }
        }
        // 在取词页签显示卡片
        View item = LayoutInflater.from(this)
                .inflate(R.layout.item_grab, null);
        ((TextView) item.findViewById(R.id.tvGrabSource)).setText(source);
        ((TextView) item.findViewById(R.id.tvGrabTranslated)).setText(translated);
        panel.addGrabCard(item);

        // 读原文 / 读译文（各自独立小喇叭，key 参与状态反馈）
        TextView btnSrc = item.findViewById(R.id.btnSpeakSrc);
        TextView btnDst = item.findViewById(R.id.btnSpeakDst);
        final String srcKey = FloatingPanel.speakKey(grabTurnId, "grabSrc");
        final String dstKey = FloatingPanel.speakKey(grabTurnId, "grabDst");
        panel.registerSpeakButton(srcKey, btnSrc);
        panel.registerSpeakButton(dstKey, btnDst);
        btnSrc.setOnClickListener(v -> speak(srcKey, source,
                Translator.speakLang(source)));
        btnDst.setOnClickListener(v -> speak(dstKey, translated,
                Translator.speakLang(translated)));
    }

    /**
     * 听力/AI 链路统一守卫：未填 API Key → 面板一句明确提示并中止。
     * 取词翻译不经过这里（用免费接口，不依赖 key）。
     */
    private boolean requireApiKey() {
        if (new Prefs(this).apiKey().trim().isEmpty()) {
            postMessage(getString(R.string.msg_no_api_key));
            return false;
        }
        return true;
    }

    /** 朗读入口：未填 API Key → 明确提示，不发请求。 */
    private void speak(String key, String text, String langHint) {
        if (text == null || text.trim().isEmpty()) return;
        if (new Prefs(this).ttsApiKey().trim().isEmpty()) {
            postMessage(getString(R.string.msg_no_api_key));
            return;
        }
        if (speechPlayer != null) speechPlayer.speak(key, text, langHint);
    }

    // ── 工具 ──────────────────────────────

    private void broadcastState(boolean running, String reason) {
        sendBroadcast(ServiceEvents.buildStateBroadcast(this, running, reason));
    }

    private void postMessage(String msg) { if (panel != null) panel.showMessage(msg); }

    private byte[] trimSilence(byte[] pcm) {
        int threshold = 300;
        int sampleCount = pcm.length / 2;
        short[] samples = new short[sampleCount];
        for (int i = 0; i < sampleCount; i++)
            samples[i] = (short) ((pcm[i * 2] & 0xFF) | (pcm[i * 2 + 1] << 8));
        int start = 0;
        for (int i = 0; i < sampleCount; i++)
            if (Math.abs(samples[i]) > threshold) { start = i; break; }
        int end = sampleCount - 1;
        for (int i = sampleCount - 1; i >= 0; i--)
            if (Math.abs(samples[i]) > threshold) { end = i; break; }
        int pad = sampleRate / 3; // 0.3s
        start = Math.max(0, start - pad);
        end = Math.min(sampleCount - 1, end + pad);
        if (end <= start) return new byte[0];
        int bytes = (end - start + 1) * 2;
        byte[] out = new byte[bytes];
        System.arraycopy(pcm, start * 2, out, 0, bytes);
        return out;
    }

    // ── 通知 ──────────────────────────────

    private void createChannel() {
        NotificationChannel ch = new NotificationChannel(CH_ID, getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription(getString(R.string.notif_channel_desc));
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    private Notification buildNotification() {
        Intent stopIntent = new Intent(this, CaptureService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 0, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CH_ID)
                .setSmallIcon(R.drawable.ic_stat_mic)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.notif_title))
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, getString(R.string.notif_action_stop), stopPi).build())
                .build();
    }

    // ── 投屏被系统停止 ──────────────────────

    private void notifyReconsentNeeded(String reason) {
        if (panel != null) {
            panel.showMessage(getString(R.string.msg_reconsent_suffix, reason));
            panel.setReconsentVisible(true);
            panel.setStatus(getString(R.string.msg_projection_stopped));
        }
    }

    private class ProjectionCallback extends MediaProjection.Callback {
        @Override
        public void onStop() {
            Log.i(TAG, "Projection stopped");
            releaseCapture();
            broadcastState(false, ServiceEvents.REASON_PROJECTION_KILLED);
            if (panel != null) panel.setListening(listen.isListening());
            notifyReconsentNeeded(getString(R.string.msg_projection_killed));
        }
    }

    // ── 悬浮窗回调 ────────────────────────

    private class PanelCallback implements FloatingPanel.Callback {
        @Override
        public void onTogglePause() {
            doPause();
        }

        @Override
        public void onAskAnswer(long turnId) {
            doAnswer(turnId);
        }

        @Override
        public void onAskQuestion(long turnId, String question) {
            doAskQuestion(turnId, question);
        }

        @Override
        public void onDeleteTurn(long turnId) {
            if (conversation != null) conversation.deleteById(turnId);
        }

        @Override
        public void onReconsent() {
            Intent intent = new Intent(CaptureService.this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra("reconsent", true);
            startActivity(intent);
        }

        @Override
        public void onClose() {
            clearHistory();
            // 先藏窗口，再停服务：保证悬浮窗不会在服务销毁后还残留在屏幕上
            if (panel != null) panel.hide();
            stopSelf();
        }

        @Override
        public void onSpeak(long turnId, String field, String text, String langHint) {
            // P5 朗读接入：走 FallbackSpeechPlayer（mimo TTS → 系统 TTS）
            speak(FloatingPanel.speakKey(turnId, field), text, langHint);
        }

        @Override
        public void onGrab() {
            // 「取词」按钮入口（此前缺失导致点击无反应）
            startGrab();
        }
    }
}
