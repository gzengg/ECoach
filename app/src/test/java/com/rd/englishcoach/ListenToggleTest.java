package com.rd.englishcoach;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * ListenToggle 的回归测试。
 *
 * <p>核心场景（修复前的 bug）：授权成功后服务已经在采集，但面板显示「已暂停」。
 * 用户看到「继续」就点了一下，反而把采集关掉，且面板文字不变 → 表现为「按钮点了没反应」。</p>
 */
public class ListenToggleTest {

    // ── 回归：授权后默认暂停，用户手动点继续才开始听 ──

    @Test
    public void afterCaptureStarted_isPausedByDefault() {
        ListenToggle t = new ListenToggle();
        t.onCaptureStarted();
        assertFalse("授权成功后默认暂停，用户手动点继续才开始听", t.isListening());
    }

    @Test
    public void afterCaptureStarted_buttonLabelIsContinueByDefault() {
        ListenToggle t = new ListenToggle();
        t.onCaptureStarted();
        assertEquals("授权成功后默认暂停，按钮应显示「继续」",
                ListenToggle.LABEL_PAUSED, t.buttonLabel());
    }

    @Test
    public void afterCaptureStarted_statusIsPausedByDefault() {
        ListenToggle t = new ListenToggle();
        t.onCaptureStarted();
        assertEquals(ListenToggle.STATUS_PAUSED, t.statusLabel());
    }

    // ── 点击语义 ──────────────────────────────

    @Test
    public void firstToggleAfterStarted_startsListening() {
        ListenToggle t = new ListenToggle();
        t.onCaptureStarted();
        boolean now = t.toggle();
        assertTrue("授权后的第一次点击语义必须是「开始听」", now);
        assertEquals(ListenToggle.LABEL_LISTENING, t.buttonLabel());
        assertEquals(ListenToggle.STATUS_LISTENING, t.statusLabel());
    }

    @Test
    public void toggleRoundTrip() {
        ListenToggle t = new ListenToggle();
        assertFalse(t.isListening());

        assertTrue(t.toggle());
        assertFalse(t.toggle());
        assertTrue(t.toggle());
        assertTrue(t.isListening());
    }

    // ── 停止 ────────────────────────────────

    @Test
    public void onCaptureStopped_resets() {
        ListenToggle t = new ListenToggle();
        t.onCaptureStarted();
        t.onCaptureStopped();
        assertFalse(t.isListening());
        assertEquals(ListenToggle.LABEL_PAUSED, t.buttonLabel());
    }

    @Test
    public void onCaptureStopped_isIdempotent() {
        ListenToggle t = new ListenToggle();
        t.onCaptureStopped();
        t.onCaptureStopped();
        assertFalse(t.isListening());
    }

    // ── 静态文案派生（面板只允许用这两个静态方法取文案） ──

    @Test
    public void staticLabels_matchInstanceState() {
        ListenToggle t = new ListenToggle();
        assertEquals(ListenToggle.labelFor(false), t.buttonLabel());
        assertEquals(ListenToggle.statusFor(false), t.statusLabel());
        t.toggle();
        assertEquals(ListenToggle.labelFor(true), t.buttonLabel());
        assertEquals(ListenToggle.statusFor(true), t.statusLabel());
    }

    // ── 文案契约 ──────────────────────────────

    @Test
    public void labelsAreDistinctAndNonEmpty() {
        assertNotEquals(ListenToggle.LABEL_LISTENING, ListenToggle.LABEL_PAUSED);
        assertNotEquals(ListenToggle.STATUS_LISTENING, ListenToggle.STATUS_PAUSED);
        assertFalse(ListenToggle.LABEL_LISTENING.isEmpty());
        assertFalse(ListenToggle.LABEL_PAUSED.isEmpty());
        assertFalse(ListenToggle.STATUS_LISTENING.isEmpty());
        assertFalse(ListenToggle.STATUS_PAUSED.isEmpty());
    }
}
