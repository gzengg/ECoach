package com.rd.englishcoach;

/**
 * 文件转录音频链路上的纯函数：解码出来的样本统一成「16k 单声道」。
 *
 * <p>为什么不自己写重采样：{@link OfflineAsrEngine#resample} 已经是同一条链路上
 * 验证过的实现，这里只做委托，不再抄一份（两份实现迟早在边界处理上跑偏）。</p>
 */
public final class PcmUtil {

    private PcmUtil() {
    }

    /**
     * 交错多声道 → 单声道（各声道求平均）。
     *
     * <p>⚠️ 不做「只取左声道」：立体声/5.1 里人声可能只在某一路，
     * 取平均能把任一路的人声保留下来（代价是两路都有声时音量略降）。</p>
     *
     * @param interleaved 交错样本
     * @param channels    声道数，&lt;=1 时原样返回
     */
    static float[] toMono(float[] interleaved, int channels) {
        if (interleaved == null || interleaved.length == 0) return new float[0];
        if (channels <= 1) return interleaved;
        int frames = interleaved.length / channels;
        if (frames <= 0) return new float[0];
        float[] out = new float[frames];
        for (int i = 0; i < frames; i++) {
            int base = i * channels;
            float sum = 0f;
            for (int c = 0; c < channels; c++) sum += interleaved[base + c];
            out[i] = sum / channels;
        }
        return out;
    }

    /** PCM16 小端字节 → float 样本（[-1,1]）。 */
    static float[] pcm16ToFloat(byte[] pcm) {
        if (pcm == null || pcm.length < 2) return new float[0];
        int n = pcm.length / 2;
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            int lo = pcm[i * 2] & 0xFF;
            int hi = pcm[i * 2 + 1];
            out[i] = (short) ((hi << 8) | lo) / 32768f;
        }
        return out;
    }

    /** 解码器输出 → 模型输入（16k）。 */
    static float[] toModelRate(float[] samples, int fromRate) {
        return OfflineAsrEngine.resample(samples, fromRate, OfflineAsrEngine.MODEL_SAMPLE_RATE);
    }
}
