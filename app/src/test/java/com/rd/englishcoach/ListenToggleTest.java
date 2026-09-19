package com.rd.englishcoach;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * ListenToggle 的回归测试。
 *
 * <p>核心场景（修复前的 bug）：授权成功后服务已经在采集，但面板显示「已暂停 / 开始听」。
 * 用户看到「开始听」就点了一下，反而把采集关掉，且面板文字不变 → 表现为「这句完了用不了」。</p>
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
    public void afterCaptureStarted_buttonLabelIsStartByDefault() {
        ListenToggle t = new ListenToggle();
        t.onCaptureStarted();
        assertEquals("授权成功后默认暂停，按钮应显示「开始听」",
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

    // ── 「这句完了」的判定 ──────────────────────

    @Test
    public void checkSegment_paused() {
        assertEquals(ListenToggle.SegmentCheck.PAUSED,
                ListenToggle.checkSegment(false, 800000));
    }

    @Test
    public void checkSegment_pausedTakesPrecedenceOverEmpty() {
        // 暂停且无数据时，应该提示"先点开始听"，而不是"没听到声音"
        assertEquals(ListenToggle.SegmentCheck.PAUSED,
                ListenToggle.checkSegment(false, 0));
    }

    @Test
    public void checkSegment_empty() {
        assertEquals(ListenToggle.SegmentCheck.EMPTY,
                ListenToggle.checkSegment(true, 0));
    }

    @Test
    public void checkSegment_negativeBytesIsEmpty() {
        assertEquals(ListenToggle.SegmentCheck.EMPTY,
                ListenToggle.checkSegment(true, -1));
    }

    @Test
    public void checkSegment_ok() {
        assertEquals(ListenToggle.SegmentCheck.OK,
                ListenToggle.checkSegment(true, 1));
        assertEquals(ListenToggle.SegmentCheck.OK,
                ListenToggle.checkSegment(true, 800000));
    }

    @Test
    public void checkSegment_instanceMatchesStatic() {
        ListenToggle t = new ListenToggle();
        t.onCaptureStarted();
        // 授权后默认暂停
        assertEquals(ListenToggle.checkSegment(false, 100), t.checkSegment(100));
        t.toggle();
        // toggle 后正在听
        assertEquals(ListenToggle.checkSegment(true, 100), t.checkSegment(100));
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
