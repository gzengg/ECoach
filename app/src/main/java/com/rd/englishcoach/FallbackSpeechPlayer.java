package com.rd.englishcoach;

/**
 * 朗读兜底链：优先走 primary（mimo TTS），
 * primary 报 ERROR 时自动降级 fallback（系统 TTS）重试同一段文本；
 * 两者都失败才对外抛 ERROR。纯 Java，可用假 player 做单测。
 *
 * 同时承担统一交互：同 key 在加载/播放中再点 = 停止；
 * 换 key 先停两边，保证同一时刻只播一路（两个喇叭交替点不会双响）。
 */
public final class FallbackSpeechPlayer implements SpeechPlayer {

    private final SpeechPlayer primary;
    private final SpeechPlayer fallback;
    private Listener listener;
    private String currentKey;
    private String pendingText;
    private String pendingLang;
    /** 当前 key 是否处于 加载/播放 中（isSpeaking 在 LOADING 期间为 false，故单独记）。 */
    private boolean active;
    /** 当前 key 的 primary 是否已失败（防止 primary 再次报错触发无限降级）。 */
    private boolean primaryFailed;
    /** primary 的失败原因：兜底也失败时对外报它（那才是根因）。 */
    private String primaryError;

    public FallbackSpeechPlayer(SpeechPlayer primary, SpeechPlayer fallback) {
        this.primary = primary;
        this.fallback = fallback;
        primary.setListener(this::onPrimaryState);
        fallback.setListener(this::onFallbackState);
    }

    @Override
    public void speak(String key, String text, String langHint) {
        if (text == null || text.isEmpty()) return;

        // 同 key 在 加载/播放 中再点 = 停止
        if (key.equals(currentKey) && active) {
            stop();
            return;
        }
        // 换 key：先摘掉当前 key 再停两边（挡掉引擎 stop 时的迟到 IDLE），保证只播一路
        if (active) {
            String prev = currentKey;
            currentKey = null;
            active = false;
            primary.stop();
            fallback.stop();
            if (prev != null) notify(prev, State.IDLE, null);
        }

        currentKey = key;
        pendingText = text;
        pendingLang = langHint;
        active = true;
        primaryFailed = false;
        primaryError = null;
        primary.speak(key, text, langHint);
    }

    /** primary 状态回调：ERROR 时降级 fallback，其余透传；过期 key 一律丢弃。 */
    private void onPrimaryState(String key, State state, String error) {
        if (key == null || !key.equals(currentKey)) return; // 过期事件
        if (state == State.ERROR) {
            if (primaryFailed) return; // 已在降级流程，忽略 primary 的后续报错
            primaryFailed = true;
            primaryError = error;
            // 降级系统 TTS 重试同一段文本，不对外抛 primary 的错
            fallback.speak(key, pendingText, pendingLang);
            return;
        }
        forward(key, state, error);
    }

    /** fallback 状态回调：透传；这里再 ERROR = 两者都失败，对外抛出。 */
    private void onFallbackState(String key, State state, String error) {
        if (key == null || !key.equals(currentKey)) return; // 过期事件
        if (state == State.ERROR && primaryError != null) {
            // 两者都失败：**先报主引擎的原因**（那才是根因），否则用户看到的永远是
            // 兜底的「系统朗读初始化失败」，完全定位不到在线朗读为什么挂（真机踩过）。
            forward(key, State.ERROR, primaryError + " | " + error);
            return;
        }
        forward(key, state, error);
    }

    private void forward(String key, State state, String error) {
        if (state == State.IDLE || state == State.ERROR) {
            if (!active && state == State.IDLE) return; // stop() 已抛过 IDLE，去重
            active = false;
            currentKey = null;
        }
        notify(key, state, error);
    }

    @Override
    public void stop() {
        boolean wasActive = active;
        String key = currentKey;
        // 先摘掉当前 key：引擎 stop() 抛出的 IDLE 会被当作过期事件丢弃，只由这里统一抛一次
        currentKey = null;
        active = false;
        primary.stop();
        fallback.stop();
        pendingText = null;
        pendingLang = null;
        if (wasActive && key != null) notify(key, State.IDLE, null);
    }

    @Override
    public boolean isSpeaking() {
        return primary.isSpeaking() || fallback.isSpeaking();
    }

    @Override
    public void release() {
        primary.release();
        fallback.release();
    }

    @Override
    public void setListener(Listener l) {
        this.listener = l;
    }

    private void notify(String key, State state, String error) {
        Listener l = listener;
        if (l != null) l.onState(key, state, error);
    }
}
