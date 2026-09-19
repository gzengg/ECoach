package com.rd.englishcoach;

/**
 * 「开始听 / 暂停听」的<b>唯一状态源</b>，以及「这句完了」能否执行的判定。
 *
 * <p><b>为什么需要它（回归背景）：</b>曾经服务里采集状态是 {@code true}，
 * 但面板被写成 {@code setListening(false)}，于是面板显示「已暂停 / 开始听」而实际正在录音。
 * 用户看到「开始听」就点了一下，反而把采集关掉，且面板文字不变、看不出任何反应，
 * 表现为「这句完了用不了」。</p>
 *
 * <p>所以：状态只存一份，面板的文字一律由它派生，不允许各自维护一份布尔值。
 * 纯 Java，无 Android 依赖，可直接 JVM 单测。</p>
 */
public final class ListenToggle {

    public static final String LABEL_LISTENING  = "暂停";
    public static final String LABEL_PAUSED     = "开始听";
    public static final String STATUS_LISTENING = "正在听…";
    public static final String STATUS_PAUSED    = "已暂停";

    /** 点击「这句完了」时的判定结果。 */
    public enum SegmentCheck {
        /** 可以识别。 */
        OK,
        /** 当前是暂停状态，需要先点「开始听」。 */
        PAUSED,
        /** 在听，但缓冲里没有数据。 */
        EMPTY
    }

    private volatile boolean listening;

    /** 投屏授权成功、开始采集时调用。 */
    public void onCaptureStarted() {
        listening = true;
    }

    /** 投屏被系统停止 / 服务销毁 / 释放采集时调用。 */
    public void onCaptureStopped() {
        listening = false;
    }

    /** 用户点击开关，返回点击后的状态。 */
    public boolean toggle() {
        listening = !listening;
        return listening;
    }

    public boolean isListening() {
        return listening;
    }

    public String buttonLabel() {
        return labelFor(listening);
    }

    public String statusLabel() {
        return statusFor(listening);
    }

    /** 开关按钮文字：正在听 -> "暂停"，已暂停 -> "开始听"。 */
    public static String labelFor(boolean listening) {
        return listening ? LABEL_LISTENING : LABEL_PAUSED;
    }

    /** 状态文字。 */
    public static String statusFor(boolean listening) {
        return listening ? STATUS_LISTENING : STATUS_PAUSED;
    }

    /** 判断点击「这句完了」时该做什么。 */
    public SegmentCheck checkSegment(int bufferedBytes) {
        return checkSegment(listening, bufferedBytes);
    }

    /**
     * 判断点击「这句完了」时该做什么。
     *
     * @param listening     是否正在听
     * @param bufferedBytes 缓冲里的有效字节数
     */
    public static SegmentCheck checkSegment(boolean listening, int bufferedBytes) {
        if (!listening) return SegmentCheck.PAUSED;
        if (bufferedBytes <= 0) return SegmentCheck.EMPTY;
        return SegmentCheck.OK;
    }
}
