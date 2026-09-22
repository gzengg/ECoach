package com.rd.englishcoach;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.Locale;

/**
 * 系统 TTS 兜底实现（AGENTS §4.3/§11.2）：
 * mimo TTS 链路出问题时降级到安卓框架自带的 {@link TextToSpeech}。
 * 零依赖、离线、免费，能读英文。所有状态回调统一抛到主线程。
 */
public final class SystemTtsEngine implements SpeechPlayer {

    private final Context ctx;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private TextToSpeech tts;
    private Listener listener;
    private String currentKey;
    private State state = State.IDLE;
    // 初始化未完成时先记住最后一次 speak，onInit 成功后补发
    private String pendingText;
    private String pendingLang;

    public SystemTtsEngine(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    @Override
    public void speak(String key, String text, String langHint) {
        if (text == null || text.isEmpty()) return;

        // 同 key 再点 = 停止
        if (key.equals(currentKey) && (state == State.LOADING || state == State.PLAYING)) {
            stop();
            return;
        }
        // 换 key：先停旧的，保证同一时刻只播一路
        if (state == State.LOADING || state == State.PLAYING) stopInternal(false);

        currentKey = key;
        state = State.LOADING;
        notifyState(key, State.LOADING, null);

        if (tts == null) {
            pendingText = text;
            pendingLang = langHint;
            tts = new TextToSpeech(ctx, status -> mainHandler.post(() -> {
                if (status != TextToSpeech.SUCCESS) {
                    state = State.ERROR;
                    String k = currentKey;
                    currentKey = null;
                    notifyState(k, State.ERROR, "系统朗读初始化失败");
                    return;
                }
                String t = pendingText;
                String l = pendingLang;
                pendingText = null;
                pendingLang = null;
                if (t != null && currentKey != null) doSpeak(currentKey, t, l);
            }));
            return;
        }
        doSpeak(key, text, langHint);
    }

    private void doSpeak(String key, String text, String langHint) {
        if (tts == null || !key.equals(currentKey)) return;

        Locale locale = Locale.ENGLISH;
        if (langHint != null && !langHint.isEmpty()) {
            locale = Locale.forLanguageTag(langHint);
        }
        int langRet = tts.setLanguage(locale);
        if (langRet < 0) { // LANG_MISSING_DATA / LANG_NOT_SUPPORTED
            state = State.ERROR;
            currentKey = null;
            notifyState(key, State.ERROR, "系统朗读语言包不可用，请安装语音包");
            return;
        }

        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onDone(String utteranceId) {
                mainHandler.post(() -> finishUtterance(utteranceId, State.IDLE, null));
            }
            @Override public void onError(String utteranceId) {
                mainHandler.post(() -> finishUtterance(utteranceId, State.ERROR, "系统朗读失败"));
            }
            @Override public void onStart(String utteranceId) {
                mainHandler.post(() -> {
                    if (!utteranceId.equals(currentKey)) return; // 过期回调
                    if (state == State.LOADING) {
                        state = State.PLAYING;
                        notifyState(utteranceId, State.PLAYING, null);
                    }
                });
            }
        });

        int ret = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, key);
        if (ret != TextToSpeech.SUCCESS) {
            state = State.ERROR;
            currentKey = null;
            notifyState(key, State.ERROR, "系统朗读失败");
        }
    }

    /** 播放结束/失败的统一收尾：过滤过期 utterance 回调。 */
    private void finishUtterance(String utteranceId, State endState, String err) {
        if (utteranceId == null || !utteranceId.equals(currentKey)) return;
        state = endState;
        currentKey = null;
        notifyState(utteranceId, endState, err);
    }

    @Override
    public void stop() {
        stopInternal(true);
    }

    /** @param notifyIdle 是否对外抛 IDLE（对外 stop 需要，内部换 key 不需要） */
    private void stopInternal(boolean notifyIdle) {
        if (tts != null) tts.stop();
        pendingText = null;
        pendingLang = null;
        State old = state;
        String key = currentKey;
        currentKey = null;
        state = State.IDLE;
        if (notifyIdle && key != null && (old == State.LOADING || old == State.PLAYING)) {
            notifyState(key, State.IDLE, null);
        }
    }

    @Override
    public boolean isSpeaking() {
        return tts != null && tts.isSpeaking();
    }

    @Override
    public void release() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
        currentKey = null;
        state = State.IDLE;
    }

    @Override
    public void setListener(Listener l) {
        this.listener = l;
    }

    private void notifyState(String key, State state, String error) {
        if (key == null) return;
        Listener l = listener;
        if (l != null) l.onState(key, state, error);
    }
}
