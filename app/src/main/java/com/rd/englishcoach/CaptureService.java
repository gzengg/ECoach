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
    private AudioRecord audioRecord;
    private PcmBuffer buffer;
    private ConversationManager conversation;
    FloatingPanel panel;
    private HandlerThread bgThread;
    private Handler bgHandler;
    private ExecutorService networkExec;
    private final ListenToggle listen = new ListenToggle();
    private volatile boolean stopped = false;
    private int sampleRate = 16000;
    private HistoryStore history;

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
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIF_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);

        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            // 不直接 stopSelf()：只移除前台通知；
            // 由 MainActivity.onDestroy(isFinishing) 或悬浮窗关闭按钮决定何时真正结束服务。
            stopForeground(STOP_FOREGROUND_REMOVE);
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
        // 不调 panel.hide()：
        //   - MainActivity.onDestroy 会调 stopService 触发这里，此时不应删窗口
        //   - 悬浮窗关闭按钮触发 onClose → stopSelf，会在这里清理
        //   - 真正要删窗口时由 PanelCallback.onClose 统一处理
        if (networkExec != null) networkExec.shutdownNow();
        if (bgThread != null) bgThread.quitSafely();
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
        panel = new FloatingPanel(this, new PanelCallback());
        panel.show();
        panel.setListening(listen.isListening());

        bgHandler.post(this::readLoop);
        Log.i(TAG, "Capture started");
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
        // 暂停采集
        listen.toggle();
        if (panel != null) panel.setListening(listen.isListening());

        if (buffer == null || buffer.size() == 0) {
            if (panel != null) panel.showMessage(getString(R.string.msg_no_audio));
            return;
        }

        byte[] pcm = buffer.snapshotAndClear();
        if (panel != null) panel.setStatus(getString(R.string.msg_recognizing));

        networkExec.execute(() -> {
            try {
                byte[] trimmed = trimSilence(pcm);
                if (trimmed.length < 3200) { // < 0.5s
                    postMessage(getString(R.string.msg_too_short));
                    return;
                }
                Prefs prefs = new Prefs(CaptureService.this);
                byte[] wav = WavUtil.toWav(trimmed, sampleRate);
                String transcript = ApiClient.transcribe(wav,
                        prefs.baseUrl(), prefs.apiKey(), prefs.asrModel());
                Log.i(TAG, "ASR: " + transcript);

                // 转录入对话历史 + 显示卡片
                ConversationManager.Turn turn = conversation.addTranscript(transcript);
                // 持久化到转录历史文件
                if (history != null) history.appendTranscript(transcript);
                if (panel != null) {
                    panel.addTurn(turn);
                    panel.setStatus(listen.statusLabel());
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
        ConversationManager.Turn turn = conversation.findById(turnId);
        if (turn == null) return;
        if (!conversation.beginAnswerRequest(turnId)) return;
        if (panel != null) panel.updateTurn(turn);

        networkExec.execute(() -> {
            try {
                Prefs p = new Prefs(CaptureService.this);
                String[][] msgs = conversation.buildMessages(p.sysPrompt());
                String answer = ApiClient.answerWithHistory(msgs,
                        p.baseUrl(), p.apiKey(), p.chatModel());
                Log.i(TAG, "Answer #" + turnId + ": " + answer);
                conversation.completeAnswer(turnId, answer);
                if (history != null) history.updateLastAnswer(answer);
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
        ConversationManager.Turn qt = conversation.addQuestion(question.trim());
        if (panel != null) panel.addTurn(qt);

        if (!conversation.beginAnswerRequest(qt.id)) return;
        if (panel != null) panel.updateTurn(qt);

        networkExec.execute(() -> {
            try {
                Prefs p = new Prefs(CaptureService.this);
                String[][] msgs = conversation.buildMessages(p.sysPrompt());
                String answer = ApiClient.answerWithHistory(msgs,
                        p.baseUrl(), p.apiKey(), p.chatModel());
                conversation.completeAnswer(qt.id, answer);
                if (history != null) history.updateLastAnswer(answer);
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
        if (panel != null) panel.clearTurns();
        // 注意：不清空持久化的转录历史文件，让用户随时可查看历史
        Log.i(TAG, "Conversation cleared");
    }

    // ── 工具 ──────────────────────────────

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
    }
}
