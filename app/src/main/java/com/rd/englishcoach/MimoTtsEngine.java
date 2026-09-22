package com.rd.englishcoach;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 基于 mimo-v2.5-tts 的 {@link SpeechPlayer} 实现。
 * 网络请求在后台线程，播放/回调在主线程。
 */
public final class MimoTtsEngine implements SpeechPlayer {

    private static final String TAG = "MimoTts";

    private final Context ctx;
    private final Prefs prefs;
    private final AudioPlayer audioPlayer;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private Listener listener;
    private String currentKey;
    /** 代数计数：stop()/新 speak 时递增，在途的网络合成结果据此作废。 */
    private int generation;

    public MimoTtsEngine(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.prefs = new Prefs(ctx);
        this.audioPlayer = new AudioPlayer(ctx);
    }

    @Override
    public void speak(String key, String text, String langHint) {
        if (text == null || text.isEmpty()) return;

        // 检查 key 为空时的提示
        if (prefs.apiKey().isEmpty()) {
            notifyState(key, SpeechPlayer.State.ERROR, "请先到设置页填 API Key");
            return;
        }

        // 同 key 再点 = 停止
        if (key.equals(currentKey) && audioPlayer.isPlaying()) {
            stop();
            return;
        }

        generation++; // 作废在途的合成结果
        final int gen = generation;
        currentKey = key;

        // 检查缓存
        byte[] cached = audioPlayer.getCached(key);
        if (cached != null) {
            audioPlayer.play(key, cached, new AudioPlayer.Listener() {
                @Override public void onComplete(String k) { notifyState(k, SpeechPlayer.State.IDLE, null); }
                @Override public void onError(String k, String err) { notifyState(k, SpeechPlayer.State.ERROR, err); }
            });
            notifyState(key, SpeechPlayer.State.PLAYING, null);
            return;
        }

        // 网络请求
        notifyState(key, SpeechPlayer.State.LOADING, null);
        exec.execute(() -> {
            try {
                String model = prefs.ttsModel().isEmpty() ? "mimo-v2.5-tts" : prefs.ttsModel();
                String voice = pickVoice(langHint);
                byte[] wav = TtsClient.synthesize(
                        prefs.baseUrl(), prefs.apiKey(), model, voice, text, "wav");

                mainHandler.post(() -> {
                    if (gen != generation || !key.equals(currentKey)) {
                        return; // 已 stop() 或已切到新 key：丢弃过期结果
                    }
                    audioPlayer.play(key, wav, new AudioPlayer.Listener() {
                        @Override public void onComplete(String k) { notifyState(k, SpeechPlayer.State.IDLE, null); }
                        @Override public void onError(String k, String err) { notifyState(k, SpeechPlayer.State.ERROR, err); }
                    });
                    notifyState(key, SpeechPlayer.State.PLAYING, null);
                });
            } catch (Exception e) {
                Log.e(TAG, "synthesize failed: " + e.getMessage());
                mainHandler.post(() -> {
                    if (gen != generation || !key.equals(currentKey)) return; // 过期错误不报
                    notifyState(key, SpeechPlayer.State.ERROR, e.getMessage());
                });
            }
        });
    }

    @Override
    public void stop() {
        generation++; // 取消在途合成
        boolean wasPlaying = audioPlayer.isPlaying();
        String key = currentKey;
        audioPlayer.stop();
        currentKey = null;
        if (wasPlaying && key != null) {
            notifyState(key, SpeechPlayer.State.IDLE, null); // 让按钮颜色复位
        }
    }

    @Override
    public boolean isSpeaking() {
        return audioPlayer.isPlaying();
    }

    @Override
    public void release() {
        audioPlayer.release();
        exec.shutdownNow();
    }

    @Override
    public void setListener(Listener l) {
        this.listener = l;
    }

    /** 根据语言提示选择声音。 */
    private String pickVoice(String langHint) {
        if (langHint != null && langHint.startsWith("zh")) {
            return prefs.ttsVoiceChinese().isEmpty() ? "冰糖" : prefs.ttsVoiceChinese();
        }
        return prefs.ttsVoiceEnglish().isEmpty() ? "Mia" : prefs.ttsVoiceEnglish();
    }



    private void notifyState(String key, State state, String error) {
        if (listener != null) listener.onState(key, state, error);
    }
}
