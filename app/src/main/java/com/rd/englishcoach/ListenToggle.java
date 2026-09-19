package com.rd.englishcoach;

/**
 * 「开始听 / 暂停听」的<b>唯一状态源</b>，以及按钮/状态文案的唯一来源。
 *
 * <p><b>为什么需要它（回归背景）：</b>曾经服务里采集状态是 {@code true}，
 * 但面板被写成 {@code setListening(false)}，于是面板显示「已暂停」而实际正在录音。
 * 用户看到「继续」就点了一下，反而把采集关掉，且面板文字不变、看不出任何反应，
 * 表现为「按钮点了没反应」。</p>
 *
 * <p>所以：状态只存一份，面板的文字一律由它派生，不允许各自维护一份布尔值、
 * 也不允许在 UI 里硬编码文案。纯 Java，无 Android 依赖，可直接 JVM 单测。</p>
 */
public final class ListenToggle {

    /** 正在听时按钮显示的动作（点了会暂停）。 */
    public static final String LABEL_LISTENING  = "暂停";
    /** 暂停时按钮显示的动作（点了会继续）。 */
    public static final String LABEL_PAUSED     = "继续";
    public static final String STATUS_LISTENING = "正在听…";
    public static final String STATUS_PAUSED    = "已暂停";

    private volatile boolean listening;

    /** 投屏授权成功、开始采集时调用。默认暂停，用户手动点继续才开始听。 */
    public void onCaptureStarted() {
        listening = false;
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

    /** 开关按钮文字（实例版）。 */
    public String buttonLabel() {
        return labelFor(listening);
    }

    /** 状态文字（实例版）。 */
    public String statusLabel() {
        return statusFor(listening);
    }

    /** 开关按钮文字：正在听 -> "暂停"，已暂停 -> "继续"。 */
    public static String labelFor(boolean listening) {
        return listening ? LABEL_LISTENING : LABEL_PAUSED;
    }

    /** 状态文字：正在听 -> "正在听…"，已暂停 -> "已暂停"。 */
    public static String statusFor(boolean listening) {
        return listening ? STATUS_LISTENING : STATUS_PAUSED;
    }
}
