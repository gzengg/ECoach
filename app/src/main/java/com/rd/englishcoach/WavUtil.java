package com.rd.englishcoach;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * PCM 16-bit 单声道 → WAV 文件（无压缩）。
 * 生成的字节流可直接 base64 后发给 mimo-v2.5-asr。
 */
public final class WavUtil {

    public static final int HEADER_SIZE = 44;

    private WavUtil() {}

    /**
     * 将原始 PCM 16-bit LE 单声道数据包装为完整 WAV 字节数组。
     *
     * @param pcm      原始 PCM 字节（必须是 16-bit LE，单声道，长度为偶数）
     * @param sampleRate 采样率，如 16000、44100、48000
     * @return 完整 WAV 文件内容
     */
    public static byte[] toWav(byte[] pcm, int sampleRate) {
        return toWav(pcm, pcm.length, sampleRate, 1, 16);
    }

    /**
     * 通用版：支持多声道 + 任意位深。
     */
    public static byte[] toWav(byte[] pcm, int pcmLen, int sampleRate, int channels, int bitsPerSample) {
        if (pcmLen < 0 || pcmLen > pcm.length) throw new IllegalArgumentException("pcmLen out of range");
        if (bitsPerSample != 16) throw new IllegalArgumentException("only 16-bit supported");

        int byteRate    = sampleRate * channels * bitsPerSample / 8;
        int blockAlign  = channels * bitsPerSample / 8;
        int dataChunkSize = pcmLen;
        int fileSize    = HEADER_SIZE + dataChunkSize;

        ByteBuffer buf = ByteBuffer.allocate(fileSize).order(ByteOrder.LITTLE_ENDIAN);

        // RIFF chunk
        buf.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        buf.putInt(fileSize - 8);
        buf.put("WAVE".getBytes(StandardCharsets.US_ASCII));

        // fmt sub-chunk
        buf.put("fmt ".getBytes(StandardCharsets.US_ASCII));
        buf.putInt(16);              // sub-chunk 1 size (PCM)
        buf.putShort((short) 1);     // audio format = PCM
        buf.putShort((short) channels);
        buf.putInt(sampleRate);
        buf.putInt(byteRate);
        buf.putShort((short) blockAlign);
        buf.putShort((short) bitsPerSample);

        // data sub-chunk
        buf.put("data".getBytes(StandardCharsets.US_ASCII));
        buf.putInt(dataChunkSize);
        buf.put(pcm, 0, pcmLen);

        return buf.array();
    }

    /**
     * 从 WAV 字节中提取 PCM 数据，返回新数组 + 采样率。
     * 只支持 RIFF/WAVE + PCM (format=1)。
     */
    public static PcmInfo extractPcm(byte[] wav) {
        if (wav.length < HEADER_SIZE) throw new IllegalArgumentException("file too short");
        ByteBuffer buf = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);

        // 校验 RIFF/WAVE
        byte[] riff = new byte[4]; buf.get(riff);
        buf.getInt(); // file size - 8
        byte[] wave = new byte[4]; buf.get(wave);
        if (!"RIFF".equals(new String(riff, StandardCharsets.US_ASCII))
         || !"WAVE".equals(new String(wave, StandardCharsets.US_ASCII))) {
            throw new IllegalArgumentException("not a WAV file");
        }

        int sampleRate = 0, channels = 0, bitsPerSample = 0;
        byte[] pcm = null;

        while (buf.remaining() >= 8) {
            byte[] chunkId = new byte[4]; buf.get(chunkId);
            int chunkSize  = buf.getInt();
            String id = new String(chunkId, StandardCharsets.US_ASCII);

            if ("fmt ".equals(id)) {
                int format    = buf.getShort() & 0xFFFF;
                channels      = buf.getShort() & 0xFFFF;
                sampleRate    = buf.getInt();
                buf.getInt(); // byte rate
                buf.getShort(); // block align
                bitsPerSample = buf.getShort() & 0xFFFF;
                // 跳过 fmt 多余字节
                int extra = chunkSize - 16;
                if (extra > 0) buf.position(buf.position() + extra);
                if (format != 1) throw new IllegalArgumentException("only PCM (format=1), got " + format);
            } else if ("data".equals(id)) {
                pcm = new byte[chunkSize];
                buf.get(pcm);
            } else {
                // 跳过未知 chunk
                int skip = chunkSize;
                while (skip > 0 && buf.hasRemaining()) {
                    int n = Math.min(skip, buf.remaining());
                    buf.position(buf.position() + n);
                    skip -= n;
                }
            }
        }
        if (pcm == null) throw new IllegalArgumentException("no data chunk found");
        return new PcmInfo(pcm, sampleRate, channels, bitsPerSample);
    }

    /** WAV 解析结果 */
    public static final class PcmInfo {
        public final byte[] pcm;
        public final int sampleRate;
        public final int channels;
        public final int bitsPerSample;

        PcmInfo(byte[] pcm, int sampleRate, int channels, int bitsPerSample) {
            this.pcm = pcm;
            this.sampleRate = sampleRate;
            this.channels = channels;
            this.bitsPerSample = bitsPerSample;
        }
    }
}
