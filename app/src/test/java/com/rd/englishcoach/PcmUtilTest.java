package com.rd.englishcoach;

import org.junit.Test;

import static org.junit.Assert.*;

/** PcmUtil 的单元测试：声道降混与 PCM16 解析。 */
public class PcmUtilTest {

    private static final float EPS = 1e-6f;

    // ── toMono ──────────────────────────────

    @Test
    public void toMono_singleChannel_returnsSameArray() {
        float[] in = {0.1f, 0.2f, 0.3f};
        assertSame("单声道不该拷贝", in, PcmUtil.toMono(in, 1));
    }

    @Test
    public void toMono_stereo_averagesChannels() {
        // 帧：(1.0, 0.0) (0.5, 0.5)
        float[] out = PcmUtil.toMono(new float[]{1f, 0f, 0.5f, 0.5f}, 2);
        assertEquals(2, out.length);
        assertEquals(0.5f, out[0], EPS);
        assertEquals(0.5f, out[1], EPS);
    }

    @Test
    public void toMono_sixChannels_averagesAll() {
        // 一帧：1,2,3,4,5,6 → 3.5
        float[] out = PcmUtil.toMono(new float[]{1f, 2f, 3f, 4f, 5f, 6f}, 6);
        assertEquals(1, out.length);
        assertEquals(3.5f, out[0], EPS);
    }

    @Test
    public void toMono_keepsVoicePresentInOnlyOneChannel() {
        // 人声只在右声道：降混后不能被丢掉
        float[] out = PcmUtil.toMono(new float[]{0f, 0.8f}, 2);
        assertEquals(1, out.length);
        assertEquals(0.4f, out[0], EPS);
    }

    @Test
    public void toMono_emptyOrNull_isEmpty() {
        assertEquals(0, PcmUtil.toMono(new float[0], 2).length);
        assertEquals(0, PcmUtil.toMono(null, 2).length);
    }

    @Test
    public void toMono_zeroChannels_meansMono() {
        float[] in = {0.1f, 0.2f};
        assertSame(in, PcmUtil.toMono(in, 0));
    }

    // ── pcm16ToFloat ────────────────────────

    @Test
    public void pcm16ToFloat_decodesLittleEndian() {
        // 0x4000 = 16384 → 0.5
        byte[] pcm = {0x00, 0x40};
        float[] out = PcmUtil.pcm16ToFloat(pcm);
        assertEquals(1, out.length);
        assertEquals(0.5f, out[0], EPS);
    }

    @Test
    public void pcm16ToFloat_negativeValue() {
        // 0xC000 = -16384 → -0.5
        byte[] pcm = {(byte) 0x00, (byte) 0xC0};
        assertEquals(-0.5f, PcmUtil.pcm16ToFloat(pcm)[0], EPS);
    }

    @Test
    public void pcm16ToFloat_fullScaleAndSilence() {
        // 与 OfflineAsrEngine.pcm16ToFloat 一致：除以 32768，所以 0x7FFF 只能到 1 - 1/32768
        assertEquals(32767f / 32768f, PcmUtil.pcm16ToFloat(new byte[]{(byte) 0xFF, 0x7F})[0], EPS);
        assertEquals(-1f, PcmUtil.pcm16ToFloat(new byte[]{0x00, (byte) 0x80})[0], EPS);
        assertEquals(0f, PcmUtil.pcm16ToFloat(new byte[]{0x00, 0x00})[0], EPS);
    }

    @Test
    public void pcm16ToFloat_emptyOrTooShort_isEmpty() {
        assertEquals(0, PcmUtil.pcm16ToFloat(new byte[0]).length);
        assertEquals(0, PcmUtil.pcm16ToFloat(new byte[]{0x01}).length);
        assertEquals(0, PcmUtil.pcm16ToFloat(null).length);
    }

    // ── toModelRate ─────────────────────────

    @Test
    public void toModelRate_48k_downSamplesToThird() {
        float[] in = new float[4800];
        for (int i = 0; i < in.length; i++) in[i] = (float) Math.sin(i * 0.01);
        assertEquals(1600, PcmUtil.toModelRate(in, 48000).length);
    }

    @Test
    public void toModelRate_16k_isPassthrough() {
        float[] in = {0.1f, 0.2f};
        assertSame(in, PcmUtil.toModelRate(in, 16000));
    }

    @Test
    public void toModelRate_44k1_lengthMatchesRatio() {
        float[] in = new float[44100];
        assertEquals(16000, PcmUtil.toModelRate(in, 44100).length);
    }

    @Test
    public void toModelRate_empty_staysEmpty() {
        assertEquals(0, PcmUtil.toModelRate(new float[0], 44100).length);
    }
}
