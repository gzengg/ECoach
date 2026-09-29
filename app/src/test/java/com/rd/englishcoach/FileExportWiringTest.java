package com.rd.englishcoach;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Scanner;

/**
 * 文件转录「带出去」能力的契约：
 * 复制 / 分享 / 导出 txt / 导出字幕，以及「在线没时间轴时不许导出 SRT」的拦截。
 *
 * <p>这些行为都在 Activity 与 View 构造里，纯 JVM 跑不起来，
 * 沿用项目里既有的源码契约测试方式（读源文件断言关键调用存在）。</p>
 */
public class FileExportWiringTest {

    private static String readFile(String path) throws Exception {
        java.io.File f = new java.io.File(path);
        assertTrue("文件不存在: " + f.getAbsolutePath(), f.exists());
        try (Scanner s = new Scanner(f, StandardCharsets.UTF_8.name())) {
            s.useDelimiter("\\A");
            return s.hasNext() ? s.next() : "";
        }
    }

    private static String historyPage() throws Exception {
        return readFile("src/main/java/com/rd/englishcoach/HistoryPage.java")
                .replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
    }

    // ── 详情页动作 ─────────────────────────

    @Test
    public void fileDetail_hasShareExportCopy() throws Exception {
        String src = historyPage();
        assertTrue("文件记录详情必须有「复制全文」", src.contains("file_action_copy"));
        assertTrue("必须有「分享」（走 act.shareText）",
                src.contains("file_action_share") && src.contains("act.shareText("));
        assertTrue("必须有「导出文本」", src.contains("file_action_export_txt"));
        assertTrue("必须有「导出字幕」", src.contains("file_action_export_srt"));
    }

    @Test
    public void fileDetail_srtBlockedWhenNoTimeline() throws Exception {
        String src = historyPage();
        assertTrue("字幕导出必须先判断 srtAvailable",
                src.contains("if (!e.srtAvailable)"));
        assertTrue("拿不到时间轴时要给出原因（在线只回纯文本）",
                src.contains("file_srt_offline_only"));
        // 关键：不能在 srtAvailable 为 false 时还往下走生成文件
        int guard = src.indexOf("if (!e.srtAvailable)");
        int build = src.indexOf("TranscriptExport.toSrt", guard);
        assertTrue("拦截必须发生在生成字幕之前", guard >= 0 && build > guard);
    }

    @Test
    public void exportTxt_usesPlainTextNotSrt() throws Exception {
        String src = historyPage();
        assertTrue("导出文本必须走 TranscriptExport.toTxt", src.contains("TranscriptExport.toTxt"));
        assertTrue("导出文本不能带时间轴", !src.contains("toTxt(e.segments, "));
    }

    @Test
    public void resumeOnlyWhenNoTaskRunning() throws Exception {
        String src = historyPage();
        assertTrue("「继续」必须同时判断中间态与服务未在跑（RUNNING 可能是进程被杀留下的）",
                src.contains("FileTranscriptStore.isResumable(e.status)")
                        && src.contains("!FileTranscribeService.running"));
    }

    // ── Activity 侧的写入 ───────────────────

    @Test
    public void mainActivity_handlesSafCreateDocument() throws Exception {
        String src = readFile("src/main/java/com/rd/englishcoach/MainActivity.java")
                .replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
        assertTrue("导出必须走 SAF 建文件（ACTION_CREATE_DOCUMENT）",
                src.contains("Intent.ACTION_CREATE_DOCUMENT"));
        assertTrue("必须分别为 txt / srt 区分回调码",
                src.contains("REQ_EXPORT_TXT") && src.contains("REQ_EXPORT_SRT"));
        assertTrue("必须处理导出的返回结果", src.contains("writeExport(data.getData())"));
        assertTrue("写入必须是 UTF-8 覆盖写（\"wt\"）",
                src.contains("openOutputStream(uri, \"wt\")")
                        && src.contains("StandardCharsets.UTF_8"));
    }

    // ── 默认文件名 ─────────────────────────

    @Test
    public void suggestFileName_replacesExtension() {
        assertEquals("lecture.mp4 → lecture.srt",
                "lecture.srt", MainActivity.suggestFileName("lecture.mp4", ".srt"));
        assertEquals("lecture.mp4 → lecture.txt",
                "lecture.txt", MainActivity.suggestFileName("lecture.mp4", ".txt"));
    }

    @Test
    public void suggestFileName_handlesWeirdNames() {
        assertEquals("没有扩展名就直接拼", "recording.srt",
                MainActivity.suggestFileName("recording", ".srt"));
        assertEquals("以点开头不算扩展名（.hidden → .hidden.srt）",
                ".hidden.srt", MainActivity.suggestFileName(".hidden", ".srt"));
        assertEquals("名字为空时用兼底名", "transcript.srt",
                MainActivity.suggestFileName("", ".srt"));
        assertEquals("null 不崩", "transcript.txt",
                MainActivity.suggestFileName(null, ".txt"));
        assertEquals("多个点只去最后一段", "a.b.srt",
                MainActivity.suggestFileName("a.b.mp3", ".srt"));
    }

    @Test
    public void suggestFileName_neverKeepsSourceExtension() {
        String out = MainActivity.suggestFileName("talk.m4a", ".srt");
        assertFalse("源扩展名不能残留（否则导出成了 .m4a.srt 的怪名字）",
                out.contains(".m4a"));
    }
}
