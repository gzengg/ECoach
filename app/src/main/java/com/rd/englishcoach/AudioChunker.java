package com.rd.englishcoach;

import java.util.ArrayList;
import java.util.List;

/**
 * 长音频切段：把超长录音切成「每段不超过上限」的片段，**优先在静音处下刀**。
 *
 * <p><b>为什么必须有这个类：</b></p>
 * <ul>
 *   <li><b>Whisper 的 ONNX 输入窗口固定 30 秒</b>——整段丢给识别器时，超出的部分会被
 *       <b>静默截断</b>。真机现象：1 分钟连续朗读只识别出前 20~30 秒，
 *       文字停在一句话中间，不报任何错。</li>
 *   <li>在线 ASR 服务同样有单次音频长度上限，超了也是截断。</li>
 * </ul>
 *
 * <p>切点选在边界附近 <b>能量最低</b>的位置（而不是硬切）：硬切可能落在词中间，
 * 让两侧都识别错；在静音处切几乎无损。</p>
 *
 * <p>纯函数，无 Android 依赖，可直接单测。</p>
 */
final class AudioChunker {

    /** 在边界前多大范围内找静音点：段长的 10%（28 秒段 → 2.8 秒）。 */
    private static final int SEARCH_DIVISOR = 10;
    /** 计算能量时的窗口（样本数）：约 20ms @16k，太短会被单个噪声采样带偏。 */
    private static final int ENERGY_WINDOW = 320;

    private AudioChunker() {}

    /** 每段最大样本数。 */
    static int maxSamples(int sampleRate, int seconds) {
        return sampleRate * seconds;
    }

    /**
     * float 采样切段。每段 ≤ {@code maxLen}；段数 ≤ 1 时原样返回（不复制）。
     * 各段拼起来 == 原数组（**无损**，不丢样本、不重复）。
     */
    static List<float[]> split(float[] samples, int maxLen) {
        List<float[]> out = new ArrayList<>();
        if (samples == null || samples.length == 0) return out;
        if (maxLen <= 0 || samples.length <= maxLen) {
            out.add(samples);
            return out;
        }
        int search = Math.min(maxLen / 4, Math.max(ENERGY_WINDOW, maxLen / SEARCH_DIVISOR));
        int pos = 0;
        while (pos < samples.length) {
            int remaining = samples.length - pos;
            if (remaining <= maxLen) {
                out.add(slice(samples, pos, samples.length));
                break;
            }
            // 理想切点 = pos + maxLen；在 [理想-search, 理想) 里找能量最低处
            int ideal = pos + maxLen;
            int from = Math.max(pos + 1, ideal - search);
            int cut = quietestIndex(samples, from, ideal);
            out.add(slice(samples, pos, cut));
            pos = cut;
        }
        return out;
    }

    /**
     * PCM16 小端单声道切段（在线路径用；一个样本 2 字节）。
     * 段长按样本数算，返回值可直接封 WAV 上传。
     */
    static List<byte[]> splitPcm(byte[] pcm, int maxLenSamples) {
        List<byte[]> out = new ArrayList<>();
        if (pcm == null || pcm.length < 2) return out;
        // 快路径：不超长就原样返回，**避免 PCM→float→PCM 的无谓往返**（极值会掉 1）
        if (maxLenSamples <= 0 || pcm.length / 2 <= maxLenSamples) {
            out.add(pcm);
            return out;
        }
        float[] samples = OfflineAsrEngine.pcm16ToFloat(pcm);
        for (float[] part : split(samples, maxLenSamples)) {
            out.add(floatToPcm16(part));
        }
        return out;
    }

    /**
     * 按「语音区间」切块（VAD 路径专用）：把相邻语音段**合并**到 ≤ {@code maxLen}，
     * 切点落在两段之间的静音中点。
     *
     * <p><b>为什么必须合并（真机性能踩过）：</b>Whisper 无论输入多长都会**补齐到 30 秒**，
     * 所以一个 3 秒的小段和 28 秒的段**代价一样**。真机 60 秒朗读被 VAD 切成 13 段，
     * 逐段识别 = 13 个 30 秒的编码开销 → 又慢又不必要。合并后只需 2~3 次调用。</p>
     *
     * <p>切的是<b>原始音频</b>（不是把语音段首尾相接），所以自然停顿被保留——
     * Whisper 靠这些停顿断句，丢了对准确率不利。</p>
     *
     * @param ranges 每个元素是 {@code {start, end}}（样本下标，升序且不重叠）
     */
    static List<float[]> splitBySpeechRanges(float[] samples, List<int[]> ranges, int maxLen) {
        List<float[]> out = new ArrayList<>();
        if (samples == null || samples.length == 0) return out;
        if (ranges == null || ranges.isEmpty()) return split(samples, maxLen);

        int blockStart = -1;
        int lastEnd = -1;
        for (int[] r : ranges) {
            int start = Math.max(0, r[0]);
            int end = Math.min(samples.length, r[1]);
            if (end <= start) continue;
            if (blockStart < 0) {
                blockStart = start;
                lastEnd = end;
                continue;
            }
            if (end - blockStart <= maxLen) {   // 合并后仍不超限 → 继续合并
                lastEnd = end;
                continue;
            }
            // 超限：在两段之间的静音中点下刀（保留前一块的尾巴、丢掉中间静音）
            int cut = Math.max(lastEnd, Math.min((lastEnd + start) / 2, start));
            if (cut <= blockStart) cut = Math.min(start, blockStart + maxLen);
            out.add(slice(samples, blockStart, cut));
            blockStart = Math.max(cut, start);
            lastEnd = end;
        }
        if (blockStart >= 0 && blockStart < samples.length) {
            out.add(slice(samples, blockStart, samples.length));
        }

        // 第二道保险：单个语音段自身就超长时，合并逻辑拦不住，再过一道切段
        List<float[]> capped = new ArrayList<>();
        for (float[] c : out) capped.addAll(split(c, maxLen));
        return capped;
    }

    /** 在 [from, to) 里找能量最低的位置（返回索引，作为切点）。 */
    private static int quietestIndex(float[] samples, int from, int to) {
        if (from >= to) return to;
        int bestIdx = to;
        double bestEnergy = Double.MAX_VALUE;
        int window = Math.min(ENERGY_WINDOW, Math.max(1, (to - from) / 4));
        for (int i = from; i < to; i += window) {
            int end = Math.min(to, i + window);
            double energy = 0;
            for (int j = i; j < end; j++) energy += (double) samples[j] * samples[j];
            if (energy < bestEnergy) {
                bestEnergy = energy;
                bestIdx = i + (end - i) / 2;
            }
        }
        return Math.max(from, Math.min(bestIdx, to));
    }

    private static float[] slice(float[] src, int from, int to) {
        float[] out = new float[to - from];
        System.arraycopy(src, from, out, 0, out.length);
        return out;
    }

    /** float 采样（[-1,1]）→ PCM16 小端字节。 */
    static byte[] floatToPcm16(float[] samples) {
        byte[] out = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            float v = samples[i];
            if (v > 1f) v = 1f;
            if (v < -1f) v = -1f;
            int s = Math.round(v * 32767f);
            out[i * 2] = (byte) (s & 0xFF);
            out[i * 2 + 1] = (byte) ((s >> 8) & 0xFF);
        }
        return out;
    }
}
