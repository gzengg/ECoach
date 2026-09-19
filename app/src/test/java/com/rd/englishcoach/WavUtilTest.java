package com.rd.englishcoach;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * WavUtil 的单元测试：验证 WAV 生成 / 解析的正确性。
 */
public class WavUtilTest {

    // ── 生成 ────────────────────────────────────

    @Test
    public void toWav_basicHeader() {
        byte[] pcm = new byte[32000]; // 1 秒 @ 16kHz mono 16-bit
        byte[] wav = WavUtil.toWav(pcm, 16000);

        // 总长度 = 44 (header) + 32000 (data)
        assertEquals(44 + 32000, wav.length);

        // RIFF/WAVE
        assertEquals('R', (char) wav[0]);
        assertEquals('I', (char) wav[1]);
        assertEquals('F', (char) wav[2]);
        assertEquals('F', (char) wav[3]);
        assertEquals('W', (char) wav[8]);
        assertEquals('A', (char) wav[9]);
        assertEquals('V', (char) wav[10]);
        assertEquals('E', (char) wav[11]);

        // file size - 8
        int fileSize = bytesToIntLE(wav, 4);
        assertEquals(wav.length - 8, fileSize);

        // fmt chunk
        assertEquals('f', (char) wav[12]);
        assertEquals('m', (char) wav[13]);
        assertEquals('t', (char) wav[14]);
        assertEquals(' ', (char) wav[15]);
        int fmtSize = bytesToIntLE(wav, 16);
        assertEquals(16, fmtSize);

        // PCM format = 1
        assertEquals(1, bytesToShortLE(wav, 20));

        // channels = 1
        assertEquals(1, bytesToShortLE(wav, 22));

        // sample rate
        assertEquals(16000, bytesToIntLE(wav, 24));

        // byte rate = 16000 * 1 * 2 = 32000
        assertEquals(32000, bytesToIntLE(wav, 28));

        // block align = 1 * 2 = 2
        assertEquals(2, bytesToShortLE(wav, 32));

        // bits per sample = 16
        assertEquals(16, bytesToShortLE(wav, 34));

        // data chunk
        assertEquals('d', (char) wav[36]);
        assertEquals('a', (char) wav[37]);
        assertEquals('t', (char) wav[38]);
        assertEquals('a', (char) wav[39]);
        int dataSize = bytesToIntLE(wav, 40);
        assertEquals(32000, dataSize);
    }

    @Test
    public void toWav_differentSampleRates() {
        for (int rate : new int[]{8000, 16000, 22050, 44100, 48000}) {
            byte[] pcm = new byte[100]; // 任意大小
            byte[] wav = WavUtil.toWav(pcm, rate);
            assertEquals(44 + 100, wav.length);
            assertEquals(rate, bytesToIntLE(wav, 24)); // sample rate at offset 24
        }
    }

    @Test
    public void toWav_pcmDataPreserved() {
        byte[] pcm = {0x01, 0x02, 0x03, 0x04, (byte) 0xFF, (byte) 0xFE};
        byte[] wav = WavUtil.toWav(pcm, 16000);

        // PCM 数据从 offset 44 开始
        for (int i = 0; i < pcm.length; i++) {
            assertEquals("PCM byte at " + i, pcm[i], wav[44 + i]);
        }
    }

    @Test
    public void toWav_emptyPcm() {
        byte[] pcm = new byte[0];
        byte[] wav = WavUtil.toWav(pcm, 16000);
        assertEquals(44, wav.length);
        // data chunk size = 0
        assertEquals(0, bytesToIntLE(wav, 40));
    }

    @Test
    public void toWav_largePcm() {
        // 模拟 25 秒 @ 16kHz mono = 800000 bytes
        byte[] pcm = new byte[800000];
        byte[] wav = WavUtil.toWav(pcm, 16000);
        assertEquals(44 + 800000, wav.length);
        assertEquals(800000, bytesToIntLE(wav, 40)); // data size
    }

    // ── 解析 ────────────────────────────────────

    @Test
    public void extractPcm_roundtrip() {
        int rate = 16000;
        byte[] original = new byte[3200];
        // 填充非零数据
        for (int i = 0; i < original.length; i++) original[i] = (byte)(i & 0xFF);

        byte[] wav = WavUtil.toWav(original, rate);
        WavUtil.PcmInfo info = WavUtil.extractPcm(wav);

        assertArrayEquals(original, info.pcm);
        assertEquals(rate, info.sampleRate);
        assertEquals(1, info.channels);
        assertEquals(16, info.bitsPerSample);
    }

    @Test
    public void extractPcm_notWav() {
        byte[] junk = new byte[64];
        junk[0] = 'N'; junk[1] = 'O'; junk[2] = 'T'; junk[3] = 'W';
        junk[4] = 'A'; junk[5] = 'V'; junk[6] = 'E'; junk[7] = ' ';
        try {
            WavUtil.extractPcm(junk);
            fail("should throw");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("not a WAV"));
        }
    }

    @Test
    public void extractPcm_tooShort() {
        try {
            WavUtil.extractPcm(new byte[10]);
            fail("should throw");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("too short"));
        }
    }

    // ── 工具方法 ──────────────────────────────

    private static int bytesToIntLE(byte[] b, int off) {
        return (b[off] & 0xFF)
             | ((b[off+1] & 0xFF) << 8)
             | ((b[off+2] & 0xFF) << 16)
             | ((b[off+3] & 0xFF) << 24);
    }

    private static int bytesToShortLE(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off+1] & 0xFF) << 8);
    }
}
