package com.rd.englishcoach;

import java.util.List;
import java.util.Locale;

/**
 * 文件转录结果的导出格式生成（纯函数，可 JVM 单测）。
 *
 * <p>字幕是<b>块级</b>时间轴：每块 28 秒（离线）/ 25 秒（在线）一条 cue，
 * 起点终点取该块在源文件里的位置。离线引擎内部还有更细的 token 时间戳，
 * 但 {@link AsrChain} 对外只回文本，这里不为了字幕去改识别链路。</p>
 *
 * <p>⚠️ 在线接口只返回纯文本、拿不到时间轴（{@code srtAvailable=false}），
 * 调用方要在导出前拦下来给用户可读提示，而不是导出一份时间全错的字幕。</p>
 */
final class TranscriptExport {

    private TranscriptExport() {}

    /** 文本形式：分段按换行拼接（与 {@link FileTranscriptStore.Entry#fullText()} 一致）。 */
    static String toTxt(List<FileTranscriptStore.Segment> segments) {
        StringBuilder sb = new StringBuilder();
        for (FileTranscriptStore.Segment s : segments) {
            String t = clean(s.text);
            if (t.isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(t);
        }
        return sb.toString();
    }

    /**
     * SRT 字幕。空文本的分段直接跳过；时间不合法（end ≤ start）时补 1 秒，
     * 否则部分播放器会把这条 cue 当作无效直接丢掉。
     */
    static String toSrt(List<FileTranscriptStore.Segment> segments) {
        StringBuilder sb = new StringBuilder();
        int index = 0;
        for (FileTranscriptStore.Segment s : segments) {
            String t = clean(s.text);
            if (t.isEmpty()) continue;
            long start = Math.max(0, s.startMs);
            long end = s.endMs > start ? s.endMs : start + 1000;
            index++;
            sb.append(index).append('\n');
            sb.append(formatTime(start)).append(" --> ").append(formatTime(end)).append('\n');
            sb.append(t).append("\n\n");
        }
        return sb.toString();
    }

    /** 毫秒 → {@code HH:MM:SS,mmm}（SRT 用逗号作小数分隔符）。 */
    static String formatTime(long ms) {
        if (ms < 0) ms = 0;
        long h = ms / 3600000;
        long m = (ms % 3600000) / 60000;
        long s = (ms % 60000) / 1000;
        long milli = ms % 1000;
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", h, m, s, milli);
    }

    /**
     * 字幕里不能出现空行（会被当成 block 分隔符），尾部空白也一并去掉。
     * 段内换行保留为多行 cue（Whisper 偶发换行）。
     */
    private static String clean(String text) {
        if (text == null) return "";
        String t = text.replace("\r", "").trim();
        if (t.isEmpty()) return "";
        String[] lines = t.split("\n");
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String l = line.trim();
            if (l.isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(l);
        }
        return sb.toString();
    }
}
