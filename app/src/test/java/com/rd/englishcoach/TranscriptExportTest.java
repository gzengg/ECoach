package com.rd.englishcoach;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

/**
 * 导出格式的纯函数契约。
 *
 * <p>重点是 SRT 的两个「会被播放器静默丢掉」的坑：时间不合法（end ≤ start）、
 * 以及正文里出现空行（会被当成 cue 分隔）。断言消息写清期望与理由。</p>
 */
public class TranscriptExportTest {

    private static FileTranscriptStore.Segment seg(long s, long e, String text) {
        return new FileTranscriptStore.Segment(s, e, text);
    }

    // ── 时间格式化 ──────────────────────────

    @Test
    public void formatTime_padsAllFields() {
        assertEquals("零值必须是 00:00:00,000", "00:00:00,000", TranscriptExport.formatTime(0));
        assertEquals("毫秒补三位", "00:00:01,005", TranscriptExport.formatTime(1005));
        assertEquals("分秒补两位", "00:09:07,000", TranscriptExport.formatTime(9 * 60_000 + 7000));
        assertEquals("小时补两位", "01:02:03,004",
                TranscriptExport.formatTime(3723_004L));
    }

    @Test
    public void formatTime_supportsLongFiles() {
        // 10 小时以上仍要正确（长文件场景），小时位不截断
        assertEquals("小时超过 99 也要正确", "100:00:00,000",
                TranscriptExport.formatTime(100L * 3600_000));
    }

    @Test
    public void formatTime_negativeClampsToZero() {
        assertEquals("负时间当 0，避免出现 -1 字段", "00:00:00,000",
                TranscriptExport.formatTime(-5));
    }

    // ── SRT ────────────────────────────────

    @Test
    public void toSrt_numbersCuesFromOne() {
        String srt = TranscriptExport.toSrt(Arrays.asList(
                seg(0, 2000, "第一句"), seg(2000, 4000, "第二句")));
        assertTrue("序号从 1 开始", srt.startsWith("1\n00:00:00,000 --> 00:00:02,000\n第一句\n"));
        assertTrue("第二条序号为 2", srt.contains("\n2\n00:00:02,000 --> 00:00:04,000\n第二句\n"));
        assertTrue("cue 之间必须空行分隔", srt.contains("第一句\n\n2\n"));
        assertTrue("文件以空行结束（SRT 约定）", srt.endsWith("\n\n"));
    }

    @Test
    public void toSrt_skipsEmptyTextAndKeepsNumberingTight() {
        String srt = TranscriptExport.toSrt(Arrays.asList(
                seg(0, 1000, "  "), seg(1000, 2000, "有效"), seg(2000, 3000, "")));
        assertTrue("空白分段不产生 cue", !srt.contains("00:00:00,000"));
        assertTrue("序号不能因为跳过而出现空洞", srt.startsWith("1\n00:00:01,000 --> 00:00:02,000\n有效\n"));
    }

    @Test
    public void toSrt_fixesInvalidDuration() {
        // 上游偶尔给出 end == start（识别块时长为 0）；这种 cue 会被播放器直接丢掉
        String srt = TranscriptExport.toSrt(Collections.singletonList(seg(3000, 3000, "词")));
        assertTrue("end ≤ start 时补 1 秒，否则播放器丢弃这条字幕",
                srt.contains("00:00:03,000 --> 00:00:04,000"));
        String reversed = TranscriptExport.toSrt(Collections.singletonList(seg(3000, 100, "词")));
        assertTrue("end 早于 start 同样要修正",
                reversed.contains("00:00:03,000 --> 00:00:04,000"));
    }

    @Test
    public void toSrt_negativeStartClamped() {
        String srt = TranscriptExport.toSrt(Collections.singletonList(seg(-500, 1500, "词")));
        assertTrue("负起点当 0（seek 精度可能给出极小的负值）",
                srt.contains("00:00:00,000 --> 00:00:01,500"));
    }

    @Test
    public void toSrt_noBlankLineInsideCue() {
        String srt = TranscriptExport.toSrt(Collections.singletonList(
                seg(0, 1000, "上行\n\n  下行  ")));
        assertFalse("正文里的空行会被当成 cue 分隔符，必须去掉",
                srt.contains("上行\n\n\n"));
        assertTrue("段内换行保留为多行 cue", srt.contains("上行\n下行"));
        assertFalse("行首行尾空白要清掉", srt.contains("  下行"));
    }

    @Test
    public void toSrt_stripsCarriageReturn() {
        String srt = TranscriptExport.toSrt(Collections.singletonList(seg(0, 1000, "a\r\nb")));
        assertFalse("CRLF 不能残留 \\r（部分播放器会显示成方块）", srt.contains("\r"));
    }

    @Test
    public void toSrt_emptyInputIsEmptyString() {
        assertEquals("没有分段时导出空串（调用方据此提示「还没有文字」）",
                "", TranscriptExport.toSrt(Collections.emptyList()));
    }

    // ── TXT ────────────────────────────────

    @Test
    public void toTxt_oneLinePerSegment() {
        String txt = TranscriptExport.toTxt(Arrays.asList(
                seg(0, 1000, "第一句"), seg(1000, 2000, " 第二句 ")));
        assertEquals("每段一行、去掉首尾空白", "第一句\n第二句", txt);
    }

    @Test
    public void toTxt_skipsEmptySegments() {
        assertEquals("空分段不留空行", "只有这句",
                TranscriptExport.toTxt(Arrays.asList(
                        seg(0, 1000, ""), seg(1000, 2000, "只有这句"), seg(2000, 3000, " "))));
    }

    @Test
    public void toTxt_emptyInputIsEmptyString() {
        assertEquals("", TranscriptExport.toTxt(Collections.emptyList()));
    }
}
