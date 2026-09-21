package com.rd.englishcoach;

/**
 * 朗读抽象接口。两种实现：{@code MimoTtsEngine}（API）和 {@code SystemTtsEngine}（兜底）。
 *
 * <p>交互约定（用户明确要求）：</p>
 * <ul>
 *   <li>同一时刻<strong>只播一路</strong>；播新的先 {@link #stop()} 旧的。</li>
 *   <li>再点同一个 key = 停止。</li>
 * </ul>
 */
public interface SpeechPlayer {

    enum State { IDLE, LOADING, PLAYING, ERROR }

    interface Listener {
        /** 状态变化回调。key = 朗读内容的唯一标识（通常是 turnId + 字段名）。 */
        void onState(String key, State state, String errorMessage);
    }

    /**
     * 朗读一段文字。
     *
     * @param key      唯一标识（用于判断「再点同一个 = 停止」和缓存）
     * @param text     要朗读的文字
     * @param langHint 语言提示（"en" 或 "zh-CN"）
     */
    void speak(String key, String text, String langHint);

    /** 停止当前朗读。 */
    void stop();

    /** 是否正在播放。 */
    boolean isSpeaking();

    /** 释放资源（服务销毁时调用）。 */
    void release();

    /** 设置状态监听。 */
    void setListener(Listener listener);
}
