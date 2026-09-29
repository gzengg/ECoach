package com.rd.englishcoach;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * MediaAudioReader 的纯逻辑部分：跨缓冲重采样器的相位连续性 + 错误码映射。
 * 真正的解码需要真机（MediaCodec），只能人工验收。
 */
public class MediaAudioReaderTest {

    private static final int FROM = 44100;
    private static final int TO = 16000;

    /** 模拟解码器的输出缓冲大小（44.1k 下典型 1024 样本）。 */
    private static final int BUF = 1024;

    // ── 相位连续性 ────────────────────────────

    /**
     * 用一个 100Hz 正弦检验「跨缓冲保持相位」。
     * 若每个缓冲各自重采样，102 秒会累积约 275ms 相位漂移，100Hz 早已反相，
     * 这种实现必然测不过。
     */
    @Test
    public void streamResampler_keepsPhaseAcrossBuffers() {
        MediaAudioReader.StreamResampler rs = new MediaAudioReader.StreamResampler(FROM, TO);
        double freq = 100.0;
        long totalIn = 0;
        long outIndex = 0;
        double maxErr = 0;
        int buffers = 4410; // 4410 * 1024 / 44100 ≈ 102 秒
        float[] in = new float[BUF];

        for (int b = 0; b < buffers; b++) {
            for (int i = 0; i < BUF; i++) {
                long abs = totalIn + i;
                in[i] = (float) Math.sin(2 * Math.PI * freq * abs / FROM);
            }
            float[] out = rs.process(in, BUF);
            for (float v : out) {
                double ideal = Math.sin(2 * Math.PI * freq * outIndex / TO);
                maxErr = Math.max(maxErr, Math.abs(v - ideal));
                outIndex++;
            }
            totalIn += BUF;
        }
        assertTrue("总输出样本数不该随时间漂移", outIndex > 0);
        assertTrue("相位漂移过大：maxErr=" + maxErr, maxErr < 0.02);
    }

    /**
     * 样本总数必须与「输入时长 × 目标采样率」一致（允许首尾各差 1~2 个）。
     * 无状态实现每缓冲丢一个输出样本，102 秒会少约 1500 个。
     */
    @Test
    public void streamResampler_outputCountDoesNotDrift() {
        MediaAudioReader.StreamResampler rs = new MediaAudioReader.StreamResampler(FROM, TO);
        long totalIn = 0;
        long totalOut = 0;
        int buffers = 4410;
        float[] in = new float[BUF];
        for (int b = 0; b < buffers; b++) {
            totalOut += rs.process(in, BUF).length;
            totalIn += BUF;
        }
        double expected = totalIn * (double) TO / FROM;
        assertTrue("输出样本数漂移过大：expected≈" + expected + " actual=" + totalOut,
                Math.abs(totalOut - expected) <= 4);
    }

    @Test
    public void streamResampler_identityRate_returnsSameSamples() {
        MediaAudioReader.StreamResampler rs = new MediaAudioReader.StreamResampler(TO, TO);
        float[] in = {0.1f, -0.2f, 0.3f};
        float[] out = rs.process(in, in.length);
        assertArrayEquals(in, out, 1e-6f);
        assertEquals(1.0, rs.step(), 1e-9);
    }

    @Test
    public void streamResampler_emptyInput_producesNothing() {
        MediaAudioReader.StreamResampler rs = new MediaAudioReader.StreamResampler(FROM, TO);
        assertEquals(0, rs.process(new float[0], 0).length);
        assertEquals(0, rs.process(null, 0).length);
    }

    @Test
    public void streamResampler_singleSampleBuffer_doesNotLoseData() {
        // 极短缓冲：第一个样本要留到下一块才能插值出来
        MediaAudioReader.StreamResampler rs = new MediaAudioReader.StreamResampler(FROM, TO);
        int out = 0;
        for (int i = 0; i < 100; i++) out += rs.process(new float[]{i}, 1).length;
        assertTrue("100 个输入样本至少该产出几十个输出样本，实际 " + out, out >= 30);
    }

    @Test
    public void streamResampler_invalidRates_fallsBackToIdentity() {
        MediaAudioReader.StreamResampler rs = new MediaAudioReader.StreamResampler(0, TO);
        assertEquals(1.0, rs.step(), 1e-9);
        assertArrayEquals(new float[]{0.5f}, rs.process(new float[]{0.5f}, 1), 1e-6f);
    }

    // ── 错误码映射 ────────────────────────────

    @Test
    public void errorRes_mapsEachCodeToItsOwnString() {
        int noTrack = MediaAudioReader.errorRes(MediaAudioReader.ERR_NO_AUDIO_TRACK);
        int unsupported = MediaAudioReader.errorRes(MediaAudioReader.ERR_UNSUPPORTED_CODEC);
        int decode = MediaAudioReader.errorRes(MediaAudioReader.ERR_DECODE_FAILED);
        int open = MediaAudioReader.errorRes(MediaAudioReader.ERR_OPEN_FAILED);

        assertNotEquals(noTrack, unsupported);
        assertNotEquals(noTrack, decode);
        assertNotEquals(noTrack, open);
        assertNotEquals(unsupported, decode);
        assertNotEquals(unsupported, open);
        assertNotEquals(decode, open);
    }

    @Test
    public void errorRes_unknownCode_fallsBackToOpenFailed() {
        assertEquals(MediaAudioReader.errorRes(MediaAudioReader.ERR_OPEN_FAILED),
                MediaAudioReader.errorRes("something_else"));
    }
}
