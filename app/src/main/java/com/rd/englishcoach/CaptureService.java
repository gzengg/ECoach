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
import android.os.Looper;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 前台服务：MediaProjection 投屏会话 + AudioRecord 采集 + 环形缓冲 + ASR→AI 链路。
 *
 * <p>生命周期：MainActivity 发 ACTION_START（携带投屏授权数据）→ 服务常驻前台，
 * 悬浮窗控制开始/暂停/切段 → 用户点停止或投屏被系统掐断时结束。</p>
 */
public class CaptureService extends Service {

    private static final String TAG = "CaptureSvc";
    private static final String CH_ID = "capture";
    private static final int NOTIF_ID = 1;

    public static final String ACTION_START  = "com.rd.englishcoach.START";
    public static final String ACTION_STOP   = "com.rd.englishcoach.STOP";
    public static final String EXTRA_RESULT_CODE = "rc";
    public static final String EXTRA_RESULT_DATA  = "rd";

    /** 静态引用，供 FloatingPanel 回调。Activity/Service 同进程，不会序列化。 */
    public static CaptureService current;

    private MediaProjection projection;
    private AudioRecord audioRecord;
    private PcmRing ring;
    FloatingPanel panel;
    private HandlerThread bgThread;
    private Handler bgHandler;
    private ExecutorService networkExec;
    private volatile boolean listening = false;
    private volatile boolean stopped = false;

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
        // ── 关键：必须先调 startForeground()，否则 Android 12+ 会崩 ──
        startForeground(NOTIF_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);

        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_START.equals(intent.getAction())) {
            int rc = intent.getIntExtra(EXTRA_RESULT_CODE, -1);
            Intent data = intent.getParcelableExtra(EXTRA_RESULT_DATA);
            if (rc == -1 || data == null) {
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
        listening = false;
        releaseCapture();
        if (panel != null) panel.hide();
        if (networkExec != null) networkExec.shutdownNow();
        if (bgThread != null) bgThread.quitSafely();
        current = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ── 启动采集 ────────────────────────────

    private void startCapture(int resultCode, Intent data) {
        Prefs prefs = new Prefs(this);
        ring = new PcmRing(prefs.maxSeconds() * 16000 * 2); // 16kHz mono 16-bit

        // 2) 获取 MediaProjection
        MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
        projection = mpm.getMediaProjection(resultCode, data);
        if (projection == null) {
            notifyReconsentNeeded("投屏授权失败");
            return;
        }
        projection.registerCallback(new ProjectionCallback(), bgHandler);

        // 3) 创建 AudioRecord
        audioRecord = createAudioRecord(projection);
        if (audioRecord == null) {
            notifyReconsentNeeded("无法创建录音（可能该 App 禁止被录）");
            return;
        }

        // 4) 创建悬浮窗
        panel = new FloatingPanel(this, new PanelCallback());
        panel.show();
        panel.setListening(false);

        // 5) 开始读取
        listening = true;
        bgHandler.post(this::readLoop);

        Log.i(TAG, "Capture started");
    }

    private void releaseCapture() {
        listening = false;
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

    // ── AudioRecord 创建 ──────────────────

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
                int bufSize = Math.max(minBuf, rate * 2); // 至少 1 秒缓冲

                AudioRecord rec = new AudioRecord.Builder()
                        .setAudioPlaybackCaptureConfig(capCfg)
                        .setAudioFormat(format)
                        .setBufferSizeInBytes(bufSize)
                        .build();

                if (rec.getState() == AudioRecord.STATE_INITIALIZED) {
                    Log.i(TAG, "AudioRecord created: " + rate + "Hz, buf=" + bufSize);
                    return rec;
                }
                rec.release();
            } catch (Exception e) {
                Log.w(TAG, "AudioRecord init failed at " + rate + "Hz: " + e.getMessage());
            }
        }
        return null;
    }

    // ── 读取循环 ────────────────────────────

    private void readLoop() {
        if (audioRecord == null) return;
        try {
            audioRecord.startRecording();
        } catch (Exception e) {
            Log.e(TAG, "startRecording failed: " + e);
            notifyReconsentNeeded("录音启动失败: " + e.getMessage());
            return;
        }

        short[] buf = new short[4096];
        while (!stopped && audioRecord != null) {
            int n = audioRecord.read(buf, 0, buf.length);
            if (n <= 0) break;

            if (listening) {
                // short[] → byte[] (LE)
                byte[] bytes = new byte[n * 2];
                for (int i = 0; i < n; i++) {
                    bytes[i * 2]     = (byte) (buf[i] & 0xFF);
                    bytes[i * 2 + 1] = (byte) ((buf[i] >> 8) & 0xFF);
                }
                ring.write(bytes, 0, bytes.length);

                // 更新音量条（RMS → 0-100）
                if (panel != null) {
                    long sum = 0;
                    for (int i = 0; i < n; i++) sum += (long) buf[i] * buf[i];
                    int rms = (int) Math.sqrt(sum / n);
                    int level = Math.min(100, rms / 100);
                    panel.setLevel(level);
                }
            }
        }
    }

    // ── 切段 → ASR → AI ─────────────────────

    private void doSegment() {
        if (ring == null || ring.size() == 0) {
            if (panel != null) panel.showMessage("没录到声音（可能暂停了或音频为空）");
            return;
        }

        byte[] pcm = ring.snapshotAndClear();
        if (panel != null) panel.setStatus("识别中…");

        networkExec.execute(() -> {
            try {
                // 静音裁剪
                byte[] trimmed = trimSilence(pcm);
                if (trimmed.length < 3200) { // < 0.5s @ 16kHz mono 16-bit
                    postMessage("声音太短（<0.5秒），请多录一点");
                    return;
                }

                // PCM → WAV
                Prefs prefs = new Prefs(CaptureService.this);
                byte[] wav = WavUtil.toWav(trimmed, 16000);

                // ASR
                String transcript = ApiClient.transcribe(wav,
                        prefs.baseUrl(), prefs.apiKey(), prefs.asrModel());
                Log.i(TAG, "ASR: " + transcript);

                // Chat
                String answer = ApiClient.answer(transcript,
                        prefs.baseUrl(), prefs.apiKey(), prefs.chatModel(), prefs.sysPrompt());
                Log.i(TAG, "Answer: " + answer);

                // 显示
                if (panel != null) {
                    panel.addAnswer(answer, transcript, prefs.showTranscript());
                    panel.setStatus("正在听…");
                }

            } catch (Exception e) {
                Log.e(TAG, "Segment failed", e);
                postMessage("出错: " + e.getMessage());
            }
        });
    }

    private void postMessage(String msg) {
        if (panel != null) panel.showMessage(msg);
    }

    // ── 静音裁剪 ────────────────────────────

    private byte[] trimSilence(byte[] pcm) {
        int threshold = 300;
        int sampleCount = pcm.length / 2;
        short[] samples = new short[sampleCount];
        for (int i = 0; i < sampleCount; i++) {
            samples[i] = (short) ((pcm[i * 2] & 0xFF) | (pcm[i * 2 + 1] << 8));
        }

        // 找第一个超过阈值的位置
        int start = 0;
        for (int i = 0; i < sampleCount; i++) {
            if (Math.abs(samples[i]) > threshold) { start = i; break; }
        }
        // 找最后一个超过阈值的位置
        int end = sampleCount - 1;
        for (int i = sampleCount - 1; i >= 0; i--) {
            if (Math.abs(samples[i]) > threshold) { end = i; break; }
        }

        // 加 0.3s 余量（@16kHz = 4800 samples）
        int pad = 4800;
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
        NotificationChannel ch = new NotificationChannel(CH_ID, "音频采集",
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("系统声音录制服务");
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    private Notification buildNotification() {
        Intent stopIntent = new Intent(this, CaptureService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 0, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = new Notification.Builder(this, CH_ID)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("英语陪练")
                .setContentText("正在监听系统声音")
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(
                        null, "停止", stopPi).build());
        return b.build();
    }

    // ── 投屏被系统停止 ──────────────────────

    private void notifyReconsentNeeded(String reason) {
        if (panel != null) {
            panel.showMessage(reason + "，请重新授权");
            panel.setReconsentVisible(true);
            panel.setStatus("投屏已停止");
        }
    }

    private class ProjectionCallback extends MediaProjection.Callback {
        @Override
        public void onStop() {
            Log.i(TAG, "Projection stopped by system");
            listening = false;
            releaseCapture();
            notifyReconsentNeeded("投屏被系统停止");
        }
    }

    // ── 悬浮窗回调 ────────────────────────

    private class PanelCallback implements FloatingPanel.Callback {
        @Override
        public void onToggleListen() {
            listening = !listening;
            if (panel != null) panel.setListening(listening);
            if (listening && ring != null) ring.clear(); // 恢复时清空缓冲
        }

        @Override
        public void onSegment() {
            doSegment();
        }

        @Override
        public void onReconsent() {
            // 拉起 MainActivity 走重新授权
            Intent intent = new Intent(CaptureService.this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra("reconsent", true);
            startActivity(intent);
        }

        @Override
        public void onClose() {
            stopSelf();
        }
    }
}
