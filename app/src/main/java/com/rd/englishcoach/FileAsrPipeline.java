package com.rd.englishcoach;

import android.util.Log;

import java.util.List;

/**
 * 文件转录的识别流水线：解码 → 切块 → 逐块走 {@link AsrChain} → 带文件时间轴上报。
 *
 * <p><b>为什么切块在识别之前（而不是交给引擎）：</b>Whisper 的 ONNX 窗口固定 30 秒，
 * 超出的部分被静默截断；在线协议也有单次长度上限。切块沿用 {@link AudioChunker}
 * 的「边界前能量最低处下刀」语义，避免把词切两半。块长 ≤ 28 秒同时满足两侧上限。</p>
 *
 * <p><b>为什么流式读：</b>一小时 16k 单声道 float 是 230MB，几小时的文件直接 OOM。
 * 每次都只持有「一块数据 + 一块待定尾巴」（≤35 秒 ≈ 2.8MB）。</p>
 *
 * <p>时间轴是「块级」：每块一条记录，起止时间由该块在文件中的位置推出。
 * 在线协议只返回纯文本，拿不到更细的时间戳，所以字幕只在离线模式下提供
 * （由调用方根据 {@link Sink#onSegment} 的 offline 标志决定）。</p>
 */
final class FileAsrPipeline {

    private static final String TAG = "FileAsrPipeline";

    /** 单块上限：28 秒（Whisper 窗口 30 秒，留 2 秒余量）。 */
    static final int MAX_BLOCK_SAMPLES = 28 * OfflineAsrEngine.MODEL_SAMPLE_RATE;
    /** 每次解码多读 25%，给「静音切点」留余量：35 秒进、最多 28 秒出，其余留作尾巴。 */
    private static final int READ_SAMPLES = MAX_BLOCK_SAMPLES + MAX_BLOCK_SAMPLES / 4;

    private FileAsrPipeline() {}

    interface Sink {
        /** 第一块音频到手后调用一次，给出样本下标 0 对应的文件时间（毫秒，已含 seek 偏移）。 */
        void onStart(long originMs);

        /** 识别出一块文本；起止时间已是文件时间轴。 */
        void onSegment(long startMs, long endMs, String text, boolean offline);

        /** 解码进度（文件时间轴，毫秒）。 */
        void onProgress(long positionMs, long durationMs);

        boolean isCanceled();
    }

    static final class Result {
        /** 已识别到的文件位置（毫秒）：断点续转从这里开始。 */
        long recognizedMs;
        /** 识别失败的块数（0 表示全好）。 */
        int failedBlocks;
    }

    /**
     * 跑完整个文件（或直到取消）。
     *
     * <p>单块失败不终止整条流水线（长文件里一块失败不该废掉全部结果），
     * 但<b>每块都失败</b>时会抛出最后一条可读错误。</p>
     *
     * @throws Exception 所有块都识别失败，或解码器报错（{@link MediaAudioReader.MediaException}）
     */
    static Result run(MediaAudioReader reader, AsrChain chain, Sink sink) throws Exception {
        Result result = new Result();
        float[] carry = new float[0];
        // buf 第一个样本在「读取流」中的绝对下标：切块后靠累加各段长度保持精确
        long bufStart = 0;
        long totalRead = 0;
        long originMs = 0;
        boolean started = false;
        int okBlocks = 0;
        String lastError = null;

        while (true) {
            if (sink.isCanceled()) break;

            float[] chunk = reader.read(READ_SAMPLES);
            totalRead += chunk == null ? 0 : chunk.length;
            if (!started && totalRead > 0) {
                // positionMs() = 首样本时间 + 已返回数据的时长，所以首样本时间 = 相减
                originMs = reader.positionMs() - ms(totalRead);
                started = true;
                sink.onStart(originMs);
            }
            sink.onProgress(reader.positionMs(), reader.durationMs());

            float[] buf = concat(carry, chunk);
            if (buf.length == 0) break;

            boolean eof = chunk == null;
            List<float[]> parts = AudioChunker.split(buf, MAX_BLOCK_SAMPLES);
            // 没到末尾就把最后一片留作尾巴：它还会吸收后续音频，现在切下去可能切在词中间
            int limit = eof ? parts.size() : parts.size() - 1;

            long pos = bufStart;
            for (int i = 0; i < limit; i++) {
                if (sink.isCanceled()) break;
                float[] block = parts.get(i);
                long startSamples = pos;
                pos += block.length;
                try {
                    String text = chain.transcribe(AudioChunker.floatToPcm16(block),
                            OfflineAsrEngine.MODEL_SAMPLE_RATE);
                    okBlocks++;
                    if (text != null && !text.trim().isEmpty()) {
                        sink.onSegment(originMs + ms(startSamples), originMs + ms(pos),
                                text.trim(), chain.lastWasOffline());
                    }
                    result.recognizedMs = originMs + ms(pos);
                } catch (Exception e) {
                    result.failedBlocks++;
                    lastError = e.getMessage() != null ? e.getMessage() : e.toString();
                    Log.w(TAG, "第 " + (i + 1) + " 块识别失败（跳过，继续下一块）", e);
                }
            }

            // parts 是 buf 的连续切片，所以已识别的长度就是下次该跳过的前缀长度
            int usedInBuf = (int) (pos - bufStart);
            bufStart = pos;
            carry = usedInBuf <= 0 ? buf
                    : usedInBuf >= buf.length ? new float[0]
                    : java.util.Arrays.copyOfRange(buf, usedInBuf, buf.length);
            if (eof) break;
        }

        if (okBlocks == 0 && lastError != null) throw new Exception(lastError);
        return result;
    }

    private static long ms(long samples) {
        return samples * 1000L / OfflineAsrEngine.MODEL_SAMPLE_RATE;
    }

    private static float[] concat(float[] a, float[] b) {
        int bn = b == null ? 0 : b.length;
        if (a.length == 0 && bn == 0) return new float[0];
        float[] out = new float[a.length + bn];
        System.arraycopy(a, 0, out, 0, a.length);
        if (bn > 0) System.arraycopy(b, 0, out, a.length, bn);
        return out;
    }
}
