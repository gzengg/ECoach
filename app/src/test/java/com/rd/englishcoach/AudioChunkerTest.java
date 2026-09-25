package com.rd.englishcoach;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/**
 * 长音频切段测试。
 *
 * <p>回归背景（真机）：1 分钟连续朗读只识别出前 20~30 秒，文字停在一句话中间、不报错。
 * 原因：VAD 没切出内容时代码把整段丢给识别器，而 <b>Whisper 的 ONNX 输入窗口固定 30 秒</b>。</p>
 */
public class AudioChunkerTest {

    private static float[] tone(int len, float amp) {
        float[] out = new float[len];
        for (int i = 0; i < len; i++) out[i] = (float) (amp * Math.sin(i / 20.0));
        return out;
    }

    // ── 上限不变量（防止以后又把整段丢给 Whisper） ──────────

    @Test
    public void segmentLimits_areUnderWhisperWindow() {
        assertTrue("Whisper 窗口是 30 秒，每段必须更短（留 padding 余量），实际 "
                        + OfflineAsrEngine.MAX_SEGMENT_SECONDS,
                OfflineAsrEngine.MAX_SEGMENT_SECONDS < 30);
        assertTrue("在线单次上传也要留余量，实际 " + OnlineAsrEngine.MAX_CHUNK_SECONDS,
                OnlineAsrEngine.MAX_CHUNK_SECONDS < 30);
    }

    // ── split：无损、不超长 ────────────────────────────────

    @Test
    public void split_isLosslessAndRespectsLimit() {
        float[] samples = tone(16000 * 100, 0.3f); // 100 秒 @16k
        int max = AudioChunker.maxSamples(16000, 28);
        List<float[]> parts = AudioChunker.split(samples, max);

        assertTrue("100 秒 / 28 秒上限必须切成多段，实际 " + parts.size(), parts.size() >= 4);
        int total = 0;
        for (float[] p : parts) {
            assertTrue("每段不得超过上限：" + p.length, p.length <= max);
            assertFalse("不得出现空段", p.length == 0);
            total += p.length;
        }
        assertEquals("切段必须无损（丢样本会让识别缺内容）", samples.length, total);

        // 逐样本校验拼回原数组（顺序 + 数值都不能变）
        int idx = 0;
        for (float[] p : parts) {
            for (float v : p) assertEquals("第 " + idx + " 个样本", samples[idx++], v, 0f);
        }
    }

    @Test
    public void split_shortAudioStaysSingleSegment() {
        float[] samples = tone(16000 * 5, 0.2f);
        List<float[]> parts = AudioChunker.split(samples, AudioChunker.maxSamples(16000, 28));
        assertEquals(1, parts.size());
        assertSame("不超长时不应复制数组", samples, parts.get(0));
        assertTrue("空输入返回空列表", AudioChunker.split(new float[0], 100).isEmpty());
        assertTrue("null 返回空列表", AudioChunker.split(null, 100).isEmpty());
    }

    @Test
    public void split_cutsAtQuietPointNotMidWord() {
        // 40 秒音频，在 26.5~27.5 秒处有一段静音（落在 28 秒边界前的搜索窗内）：
        // 切点应落在静音里，而不是硬切在 28 秒
        int rate = 16000;
        float[] samples = new float[rate * 40];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = 0.5f * (float) Math.sin(i / 20.0);
        }
        for (int i = (int) (rate * 26.5); i < rate * 27.5; i++) samples[i] = 0f;

        List<float[]> parts = AudioChunker.split(samples, rate * 28);
        assertEquals(2, parts.size());
        int firstLen = parts.get(0).length;
        assertTrue("切点应落在静音区 26.5~27.5 秒，实际 " + firstLen / (double) rate + " 秒",
                firstLen >= (int) (rate * 26.5) && firstLen <= rate * 27.5);
        assertEquals("静音处切：切点附近应该是安静的", 0f, parts.get(0)[firstLen - 1], 0.0001f);
    }

    // ── splitPcm：在线路径 ─────────────────────────────────

    @Test
    public void splitPcm_fastPathKeepsOriginalBytes() {
        byte[] pcm = new byte[16000 * 2 * 10]; // 10 秒
        for (int i = 0; i < pcm.length; i++) pcm[i] = (byte) (i % 100);
        List<byte[]> parts = AudioChunker.splitPcm(pcm, AudioChunker.maxSamples(16000, 25));
        assertEquals(1, parts.size());
        assertSame("不超长时不能做 PCM→float→PCM 往返（会改动极值）", pcm, parts.get(0));
    }

    @Test
    public void splitPcm_splitsLongAudio() {
        byte[] pcm = new byte[16000 * 2 * 60]; // 60 秒
        for (int i = 0; i < pcm.length; i++) pcm[i] = (byte) (i % 100);
        int max = AudioChunker.maxSamples(16000, 25);
        List<byte[]> parts = AudioChunker.splitPcm(pcm, max);
        assertEquals(3, parts.size());
        int total = 0;
        for (byte[] p : parts) {
            assertTrue("每段不得超过上限：" + p.length, p.length / 2 <= max);
            total += p.length;
        }
        assertEquals("PCM 切段也要无损", pcm.length, total);
    }

    // ── 采样转换往返 ──────────────────────────────────────

    @Test
    public void pcmRoundTrip_isNearLossless() {
        byte[] pcm = new byte[2000];
        for (int i = 0; i < 1000; i++) {
            short v = (short) (Math.sin(i / 10.0) * 20000);
            pcm[i * 2] = (byte) (v & 0xFF);
            pcm[i * 2 + 1] = (byte) ((v >> 8) & 0xFF);
        }
        byte[] back = AudioChunker.floatToPcm16(OfflineAsrEngine.pcm16ToFloat(pcm));
        assertEquals(pcm.length, back.length);
        int maxDiff = 0;
        for (int i = 0; i < 1000; i++) {
            short a = (short) ((pcm[i * 2] & 0xFF) | (pcm[i * 2 + 1] << 8));
            short b = (short) ((back[i * 2] & 0xFF) | (back[i * 2 + 1] << 8));
            maxDiff = Math.max(maxDiff, Math.abs(a - b));
        }
        assertTrue("往返误差应 ≤ 1 LSB，实际 " + maxDiff, maxDiff <= 1);
    }

    @Test
    public void floatToPcm16_clampsOutOfRange() {
        byte[] out = AudioChunker.floatToPcm16(new float[]{2f, -2f, 0f});
        short hi = (short) ((out[0] & 0xFF) | (out[1] << 8));
        short lo = (short) ((out[2] & 0xFF) | (out[3] << 8));
        short zero = (short) ((out[4] & 0xFF) | (out[5] << 8));
        assertEquals(32767, hi);
        assertEquals(-32767, lo);
        assertEquals(0, zero);
    }

    // ── 结果可信度（真机回归：60 秒音频只识别出一个词） ──────

    @Test
    public void implausible_catchesSilenceHallucination() {
        // 真机回归：VAD 只吐出一段静音 → Whisper 对静音幻觉出「now」，
        // 而代码因为「VAD 段非空」跳过了整段兜底 → 必须判为不可信，触发整段重试。
        assertTrue("60 秒音频只出 1 个词必须判为不可信", OfflineAsrEngine.implausible("now", 60));
        assertTrue("空结果必须判为不可信", OfflineAsrEngine.implausible("", 60));
        assertTrue("null 必须判为不可信", OfflineAsrEngine.implausible(null, 60));
        // 正常朗读：60 秒 ≈ 240 字（真机那段就是 240 字左右），绝不能被误判（否则每次跑两遍）
        assertFalse("正常识别结果不得被误判", OfflineAsrEngine.implausible(repeat('a', 240), 60));
        // 阈值就是「每秒 1 个字」：59 字/60 秒 不可信，60 字/60 秒 可信
        assertTrue(OfflineAsrEngine.implausible(repeat('a', 59), 60));
        assertFalse(OfflineAsrEngine.implausible(repeat('a', 60), 60));
    }

    @Test
    public void implausible_shortAudioUsesFloor() {
        // 短录音有 10 字下限，避免「Yes.」这种正常短答被误判成不可信
        assertFalse(OfflineAsrEngine.implausible("Yes, I agree.", 2));
        assertFalse(OfflineAsrEngine.implausible(repeat('a', 10), 1));
        assertTrue("1 秒只出 1 个字仍不可信", OfflineAsrEngine.implausible("a", 1));
    }

    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(c);
        return sb.toString();
    }


    // ── VAD 区间合并（回归：13 段逐段识别太慢） ──────────────

    /** 造一个「朗读 App」式的音频：13 段语音 + 段间静音，共 60.2 秒。 */
    private static float[] readingAudio(java.util.List<int[]> ranges) {
        int rate = 16000;
        float[] samples = new float[(int) (rate * 60.2)];
        for (int i = 0; i < samples.length; i++) samples[i] = 0.4f * (float) Math.sin(i / 20.0);
        // 13 段语音：每段 3.37 秒，间隔 1.26 秒静音
        int seg = (int) (rate * 3.37);
        int gap = (int) (rate * 1.26);
        int pos = (int) (rate * 0.5);
        for (int i = 0; i < 13; i++) {
            ranges.add(new int[]{pos, Math.min(samples.length, pos + seg)});
            for (int j = pos; j < Math.min(samples.length, pos + seg); j++) samples[j] = 0.4f;
            for (int j = pos + seg; j < Math.min(samples.length, pos + seg + gap); j++) samples[j] = 0f;
            pos += seg + gap;
        }
        return samples;
    }

    @Test
    public void splitBySpeechRanges_mergesSegmentsToCutCallCount() {
        // 真机回归：60.2 秒音频被 VAD 切成 13 段，逐段识别 = 13 个「补齐到 30 秒」的编码开销，太慢。
        // Whisper 无论输入多长都补齐到 30 秒，所以必须把相邻段合并到 ≤28 秒再识别。
        java.util.List<int[]> ranges = new java.util.ArrayList<>();
        float[] samples = readingAudio(ranges);
        assertEquals(13, ranges.size());

        int max = AudioChunker.maxSamples(16000, 28);
        java.util.List<float[]> chunks = AudioChunker.splitBySpeechRanges(samples, ranges, max);

        assertTrue("13 段必须合并成少数几块，实际 " + chunks.size(), chunks.size() <= 3);
        assertFalse("不能退化成逐段识别（那就是慢的原因）", chunks.size() == ranges.size());
        for (float[] c : chunks) {
            assertTrue("每块不得超过上限：" + c.length, c.length <= max);
            assertFalse("不得出现空块", c.length == 0);
        }
        int total = 0;
        for (float[] c : chunks) total += c.length;
        assertTrue("合并不该丢掉大段音频（只丢静音），实际 " + total + " / " + samples.length,
                total > samples.length * 0.8);
    }

    @Test
    public void splitBySpeechRanges_cutsInsideSilenceNotSpeech() {
        java.util.List<int[]> ranges = new java.util.ArrayList<>();
        float[] samples = readingAudio(ranges);
        int max = AudioChunker.maxSamples(16000, 28);
        java.util.List<float[]> chunks = AudioChunker.splitBySpeechRanges(samples, ranges, max);

        // 不变量：每块**结尾**必须落在静音里（切在词中间会让两侧都识别错）
        for (int i = 0; i < chunks.size() - 1; i++) {
            float[] c = chunks.get(i);
            assertEquals("第 " + i + " 块结尾不在静音里（切到词中间了）",
                    0f, c[c.length - 1], 0.001f);
        }
    }

    @Test
    public void splitBySpeechRanges_fallsBackWhenNoRanges() {
        float[] samples = tone(16000 * 40, 0.3f);
        int max = AudioChunker.maxSamples(16000, 28);
        // 没有语音区间（VAD 什么都没切出来）→ 退回普通切段，绝不能返回空
        java.util.List<float[]> chunks = AudioChunker.splitBySpeechRanges(
                samples, new java.util.ArrayList<>(), max);
        assertEquals(2, chunks.size());
        int total = 0;
        for (float[] c : chunks) total += c.length;
        assertEquals("退回路径也必须无损", samples.length, total);
    }

}
