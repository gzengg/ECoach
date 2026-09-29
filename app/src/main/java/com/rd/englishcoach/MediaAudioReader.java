package com.rd.englishcoach;

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.util.Log;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Arrays;

/**
 * 把本地音频/视频文件解码成「16k 单声道 float」的流式读取器。
 *
 * <p>为什么用 MediaExtractor + MediaCodec（而非引入 ffmpeg）：两者是框架自带，
 * 满足「零新依赖」的硬约束；视频文件在这里只是「挑一条 audio/* 轨道」，
 * 画面数据完全不参与解码。</p>
 *
 * <p>为什么是流式（{@link #read(int)} 而不是一次性读满）：一小时 16k 单声道 float
 * 就是 230MB，几小时的文件直接 OOM。调用方每次只拿一块（≤28 秒 ≈ 1.8MB）。</p>
 *
 * <p>⚠️⚠️ 重采样必须跨解码缓冲保持相位（见 {@link StreamResampler}）：
 * 对每个输出缓冲各调一次 {@code OfflineAsrEngine.resample()} 会累计时间轴漂移，
 * 一小时文件能漂近 10 秒，SRT 全错。</p>
 */
public final class MediaAudioReader implements Closeable {

    private static final String TAG = "MediaAudioReader";
    private static final long TIMEOUT_US = 10_000L;
    private static final int MODEL_RATE = OfflineAsrEngine.MODEL_SAMPLE_RATE;
    /** 解码器连续给不出输出且已送 EOS 时的耐心上限（避免死等）。 */
    private static final int MAX_STALLS = 500;

    // ── 错误码（面向用户的文案在 strings.xml，用 errorRes 映射）──

    /** 打不开文件（已删除 / 权限失效 / 格式完全无法解析）。 */
    public static final String ERR_OPEN_FAILED = "open_failed";
    /** 文件里没有音轨（纯视频流 / 图片伪装成 mp4）。 */
    public static final String ERR_NO_AUDIO_TRACK = "no_audio_track";
    /** 设备没有该编码的解码器（冷门编码 / 加密 DRM）。 */
    public static final String ERR_UNSUPPORTED_CODEC = "unsupported_codec";
    /** 解码中途失败（文件损坏 / 截断）。 */
    public static final String ERR_DECODE_FAILED = "decode_failed";

    public static int errorRes(String code) {
        if (ERR_NO_AUDIO_TRACK.equals(code)) return R.string.file_err_no_audio;
        if (ERR_UNSUPPORTED_CODEC.equals(code)) return R.string.file_err_unsupported;
        if (ERR_DECODE_FAILED.equals(code)) return R.string.file_err_decode;
        return R.string.file_err_open;
    }

    /** 带错误码的 IOException，便于上层映射成中文文案。 */
    public static final class MediaException extends IOException {
        public final String code;

        MediaException(String code, Throwable cause) {
            super(code, cause);
            this.code = code;
        }
    }

    private MediaExtractor extractor;
    private MediaCodec codec;
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

    private int channels = 1;
    private int fromRate = MODEL_RATE;
    private boolean pcmFloat;
    private StreamResampler resampler;

    private boolean inputDone;
    private boolean outputDone;
    /** 首个输出缓冲的 PTS：seek 后重置，用来把内容时长换算回文件时间轴。 */
    private long baseMs = -1;
    /** 已返回给调用方的输出样本数（配合 resampler.step 换算内容时长）。 */
    private long returnedOut;
    private long durationMs;

    private float[] pending = new float[0];
    private int pendingLen;
    private int pendingRead;

    public MediaAudioReader(Context ctx, Uri uri) throws IOException {
        extractor = new MediaExtractor();
        try {
            extractor.setDataSource(ctx, uri, null);
        } catch (Exception e) {
            close();
            throw new MediaException(ERR_OPEN_FAILED, e);
        }

        int track = findAudioTrack();
        if (track < 0) {
            close();
            throw new MediaException(ERR_NO_AUDIO_TRACK, null);
        }
        extractor.selectTrack(track);

        MediaFormat format = extractor.getTrackFormat(track);
        String mime = format.getString(MediaFormat.KEY_MIME);
        durationMs = format.containsKey(MediaFormat.KEY_DURATION)
                ? format.getLong(MediaFormat.KEY_DURATION) / 1000L : 0L;
        applyFormat(format);

        try {
            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(format, null, null, 0);
            codec.start();
        } catch (Exception e) {
            close();
            throw new MediaException(ERR_UNSUPPORTED_CODEC, e);
        }
        resampler = new StreamResampler(fromRate, MODEL_RATE);
    }

    private int findAudioTrack() {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) return i;
        }
        return -1;
    }

    private void applyFormat(MediaFormat format) {
        if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
            fromRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        }
        if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
            channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
        }
        pcmFloat = format.containsKey(MediaFormat.KEY_PCM_ENCODING)
                && format.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT;
    }

    /** 源文件音频时长（毫秒）；拿不到时为 0。 */
    public long durationMs() {
        return durationMs;
    }

    /**
     * 已解码内容的文件时间位置（毫秒），即上一次 {@link #read} 返回数据的结束时刻。
     * 断点续转与 SRT 时间轴都用它。
     */
    public long positionMs() {
        if (baseMs < 0) return 0;
        double contentMs = resampler == null ? 0 : returnedOut * resampler.step() * 1000.0 / fromRate;
        return baseMs + (long) contentMs;
    }

    /**
     * 跳到指定位置（用于「继续」未完成的转录）。
     *
     * <p>实际的落点由容器决定（一般会回到前一个关键帧/分组），
     * 所以位置以解码后的 PTS 为准，调用方不要假设一定等于 {@code ms}。</p>
     *
     * @return 是否成功（失败时本次读取会从当前位置继续）
     */
    public boolean seekTo(long ms) {
        if (extractor == null || codec == null) return false;
        try {
            extractor.seekTo(Math.max(0, ms) * 1000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            codec.flush();
        } catch (Exception e) {
            Log.w(TAG, "seekTo failed", e);
            return false;
        }
        inputDone = false;
        outputDone = false;
        baseMs = -1;
        returnedOut = 0;
        pendingLen = 0;
        pendingRead = 0;
        resampler = new StreamResampler(fromRate, MODEL_RATE);
        return true;
    }

    /**
     * 读取最多 {@code maxSamples} 个 16k 单声道样本。
     *
     * @return null 表示已到文件末尾（或解码结束）
     */
    public float[] read(int maxSamples) throws IOException {
        if (maxSamples <= 0) throw new IllegalArgumentException("maxSamples must be > 0");
        if (outputDone && pendingRead >= pendingLen) return null;

        float[] out = new float[maxSamples];
        int filled = 0;
        int stalls = 0;
        while (filled < maxSamples) {
            filled += drain(out, filled, maxSamples - filled);
            if (filled >= maxSamples) break;
            if (outputDone) break;
            if (!pump()) {
                if (++stalls > MAX_STALLS) {
                    Log.w(TAG, "decoder stalled, treat as end of stream");
                    outputDone = true;
                    break;
                }
            } else {
                stalls = 0;
            }
        }
        if (filled == 0) return null;
        returnedOut += filled;
        return filled == maxSamples ? out : Arrays.copyOf(out, filled);
    }

    private int drain(float[] out, int offset, int want) {
        int avail = pendingLen - pendingRead;
        if (avail <= 0) return 0;
        int n = Math.min(avail, want);
        System.arraycopy(pending, pendingRead, out, offset, n);
        pendingRead += n;
        if (pendingRead >= pendingLen) {
            pendingLen = 0;
            pendingRead = 0;
        }
        return n;
    }

    /**
     * 推进一次解码：取输入喂给解码器、取输出转成 16k 单声道。
     *
     * @return true = 本次有进展；false = 解码器暂时给不出数据
     */
    private boolean pump() throws IOException {
        boolean progressed = false;

        if (!inputDone) {
            int inIdx = codec.dequeueInputBuffer(TIMEOUT_US);
            if (inIdx >= 0) {
                progressed = true;
                ByteBuffer buf = codec.getInputBuffer(inIdx);
                int size = extractor.readSampleData(buf, 0);
                if (size < 0) {
                    codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    inputDone = true;
                } else {
                    codec.queueInputBuffer(inIdx, 0, size, extractor.getSampleTime(), 0);
                    extractor.advance();
                }
            }
        }

        int outIdx = codec.dequeueOutputBuffer(info, TIMEOUT_US);
        if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            // HE-AAC / Opus 等编码的实际输出采样率与轨道声明可能不同，必须按新格式重建重采样器
            applyFormat(codec.getOutputFormat());
            resampler = new StreamResampler(fromRate, MODEL_RATE);
            return true;
        }
        if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER || outIdx == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
            return progressed;
        }
        if (outIdx < 0) return progressed;

        progressed = true;
        if (info.size > 0) {
            ByteBuffer buf = codec.getOutputBuffer(outIdx);
            if (buf != null) appendOutput(buf);
        }
        codec.releaseOutputBuffer(outIdx, false);
        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true;
        return progressed;
    }

    private void appendOutput(ByteBuffer buf) throws IOException {
        buf.position(info.offset);
        buf.limit(info.offset + info.size);
        float[] mono;
        try {
            if (pcmFloat) {
                FloatBuffer fb = buf.order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
                float[] raw = new float[fb.remaining()];
                fb.get(raw);
                mono = PcmUtil.toMono(raw, channels);
            } else {
                byte[] raw = new byte[info.size];
                buf.get(raw);
                mono = PcmUtil.toMono(PcmUtil.pcm16ToFloat(raw), channels);
            }
        } catch (Exception e) {
            throw new MediaException(ERR_DECODE_FAILED, e);
        }

        if (baseMs < 0) {
            long ptsMs = info.presentationTimeUs / 1000L;
            baseMs = Math.max(0, ptsMs);
        }
        float[] at16k = resampler.process(mono, mono.length);
        appendPending(at16k);
    }

    private void appendPending(float[] a) {
        if (a.length == 0) return;
        if (pendingRead > 0) {
            int keep = pendingLen - pendingRead;
            if (keep > 0) System.arraycopy(pending, pendingRead, pending, 0, keep);
            pendingLen = keep;
            pendingRead = 0;
        }
        if (pendingLen + a.length > pending.length) {
            pending = Arrays.copyOf(pending, Math.max(1024, (pendingLen + a.length) * 2));
        }
        System.arraycopy(a, 0, pending, pendingLen, a.length);
        pendingLen += a.length;
    }

    @Override
    public void close() {
        if (codec != null) {
            try {
                codec.stop();
            } catch (Exception ignored) {
                // 已停止 / 未启动，忽略
            }
            try {
                codec.release();
            } catch (Exception ignored) {
                // 重复释放，忽略
            }
            codec = null;
        }
        if (extractor != null) {
            try {
                extractor.release();
            } catch (Exception ignored) {
                // 忽略
            }
            extractor = null;
        }
    }

    /**
     * 有状态的线性插值重采样：跨解码缓冲保持相位连续。
     *
     * <p>为什么必须有状态：解码器一次给 1024 个（44.1k）样本，独立重采样时每个缓冲
     * 都会丢掉不足一个输出样本的余数 —— 每秒约 43 个样本，一小时约 9.7 秒的漂移。
     * 漂移的表现是「字幕越来越早」，而且不报任何错。</p>
     */
    static final class StreamResampler {

        private final int fromRate;
        private final int toRate;
        /** 每个输出样本前进的输入样本数。 */
        private final double step;
        /** 下一个输出样本的绝对位置（输入样本单位）。 */
        private double pos;
        /** 已喂入的输入样本总数。 */
        private long consumed;
        /** 上一个输入样本，用于插值窗口跨缓冲的那一个点。 */
        private float prev;

        StreamResampler(int fromRate, int toRate) {
            this.fromRate = fromRate;
            this.toRate = toRate;
            this.step = (fromRate > 0 && toRate > 0) ? (double) fromRate / toRate : 1.0;
        }

        double step() {
            return step;
        }

        /** 处理一块输入样本，返回重采样后的输出样本（至少 0 个）。 */
        float[] process(float[] in, int n) {
            if (in == null || n <= 0) return new float[0];
            if (step == 1.0) {
                consumed += n;
                prev = in[n - 1];
                return Arrays.copyOf(in, n);
            }
            long base = consumed;
            float[] out = new float[(int) (n / step) + 2];
            int k = 0;
            // 插值窗口 (i, i+1) 必须都落在已有样本内才输出，否则留给下一块
            while (pos < base + n - 1) {
                long i = (long) Math.floor(pos);
                float a = (i < base) ? prev : in[(int) (i - base)];
                float b = (i + 1 < base) ? prev : in[(int) (i + 1 - base)];
                float f = (float) (pos - i);
                out[k++] = a + (b - a) * f;
                pos += step;
            }
            consumed += n;
            prev = in[n - 1];
            return k == out.length ? out : Arrays.copyOf(out, k);
        }
    }
}
