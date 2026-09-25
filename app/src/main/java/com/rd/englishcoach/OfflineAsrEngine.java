package com.rd.englishcoach;

import android.content.Context;
import android.util.Log;

import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig;
import com.k2fsa.sherpa.onnx.OfflineStream;
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig;
import com.k2fsa.sherpa.onnx.SileroVadModelConfig;
import com.k2fsa.sherpa.onnx.SpeechSegment;
import com.k2fsa.sherpa.onnx.Vad;
import com.k2fsa.sherpa.onnx.VadModelConfig;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 离线识别（sherpa-onnx）：整段 PCM → VAD 切句 → 逐段识别 → 拼接。
 *
 * <p><b>模型按目录内容自动判类型，不看 id：</b>有 {@code *encoder*.onnx} + {@code *decoder*.onnx}
 * 就按 Whisper 配，否则按 SenseVoice 配（单模型 + tokens）。上游各模型的命名不统一，
 * 按文件判比逼用户改名可靠。</p>
 *
 * <p>⚠️ <b>assetManager 必须传 null</b>：模型在私有目录（绝对路径），传 {@code ctx.getAssets()}
 * 会让 sherpa 把绝对路径当成 assets 路径，读不到文件后<b>直接 native abort（无 Java 异常）</b>，
 * 进程闪退。真机日志特征：{@code Load '.../tokens.txt' failed}。</p>
 *
 * <p>⚠️ <b>必须重采样到 16kHz</b>：模型固定按 16k 提特征，而采集在 16k 不可用时会降级到 44.1k/48k。
 * 不重采样<b>不报错，只是静默掉准确率</b>——这是最难查的一类 bug。</p>
 *
 * <p><b>模型懒加载并缓存</b>：实例由服务持有并复用，别每次识别都新建（重建一次要 1~3 秒）。</p>
 */
final class OfflineAsrEngine implements AsrEngine {

    private static final String TAG = "OfflineAsr";
    /** SenseVoice / Whisper 都按 16kHz 提取特征。 */
    static final int MODEL_SAMPLE_RATE = 16000;
    private static final int NUM_THREADS = 4;

    /**
     * Whisper 的 ONNX 输入窗口固定 30 秒：更长的段会被<b>静默截断</b>。
     * 所以每段最多 28 秒（留 2 秒余量给模型内部的 padding）。
     *
     * <p>真机踩过：1 分钟连续朗读只识别出前 20~30 秒，文字停在一句话中间、不报错——
     * 当时 VAD 没切出内容，代码直接把整段丢给了识别器。</p>
     */
    static final int MAX_SEGMENT_SECONDS = 28;

    private final Context ctx;
    private final ModelManager models;
    private final Prefs prefs;

    private OfflineRecognizer recognizer;
    private String loadedModelId;
    private Vad vad;
    private String vadLoadedId;
    private String lastNote;

    OfflineAsrEngine(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.models = new ModelManager(this.ctx);
        this.prefs = new Prefs(this.ctx);
    }

    @Override
    public boolean isAvailable() {
        return pickSpec() != null;
    }

    /**
     * 用哪个离线模型：优先用户在设置里选的，没选/没装则回落到「推荐档 → 其它已装档」。
     * 都没装返回 null。
     */
    ModelCatalog.Spec pickSpec() {
        ModelCatalog.Spec chosen = ModelCatalog.byId(prefs.offlineAsrModelId());
        if (installed(chosen)) return chosen;
        ModelCatalog.Spec rec = ModelCatalog.byId(ModelCatalog.ASR_TURBO);
        if (installed(rec)) return rec;
        for (ModelCatalog.Spec s : ModelCatalog.all()) {
            if (s.kind == ModelCatalog.Kind.ASR && installed(s)) return s;
        }
        return null;
    }

    private boolean installed(ModelCatalog.Spec spec) {
        return spec != null && models.stateOf(spec) == ModelManager.State.INSTALLED;
    }

    @Override
    public String transcribe(byte[] pcm, int sampleRate) throws Exception {
        ModelCatalog.Spec spec = pickSpec();
        if (spec == null) throw new IOException(ctx.getString(R.string.asr_offline_missing));

        float[] samples = pcm16ToFloat(pcm);
        if (sampleRate != MODEL_SAMPLE_RATE) {
            samples = resample(samples, sampleRate, MODEL_SAMPLE_RATE);
        }
        double audioSeconds = samples.length / (double) MODEL_SAMPLE_RATE;

        OfflineRecognizer r = recognizerFor(spec);
        int maxLen = AudioChunker.maxSamples(MODEL_SAMPLE_RATE, MAX_SEGMENT_SECONDS);

        long t0 = System.currentTimeMillis();
        // ① VAD 切句（只在装了 VAD 时）
        String vadText = null;
        List<int[]> vadRanges = null;
        List<float[]> vadChunks = null;
        int vadSegmentCount = 0;
        Vad v = vadOrNull();
        if (v != null) {
            vadRanges = speechRanges(v, samples);
            vadSegmentCount = vadRanges.size();
            // 关键：合并成「每块 ≤ 28s」再识别（逐段识别 = 每段都付一个 30s 编码开销，慢好几倍）
            vadChunks = AudioChunker.splitBySpeechRanges(samples, vadRanges, maxLen);
        }
        long t1 = System.currentTimeMillis();
        if (v != null) vadText = runSegments(r, vadChunks);
        long t2 = System.currentTimeMillis();

        // ② VAD 结果不可信就重试整段——
        //    真机踩过：VAD 一个语音段都没切出来（flush 后只吐出一段静音），
        //    代码却因为「out 非空」跳过了整段兜底，结果 60 秒音频只识别出一个词（静音幻觉）。
        String text;
        String path;
        if (vadText != null && !implausible(vadText, audioSeconds)) {
            text = vadText;
            path = ctx.getString(R.string.asr_path_vad, vadSegmentCount, vadChunks.size(),
                    String.format(Locale.US, "%.1f", speechSeconds(vadRanges)));
        } else {
            List<float[]> whole = AudioChunker.split(samples, maxLen);
            String wholeText = runSegments(r, whole);
            boolean useVad = vadText != null && vadText.length() > wholeText.length();
            text = useVad ? vadText : wholeText;
            path = useVad
                    ? ctx.getString(R.string.asr_path_vad, vadSegmentCount, vadChunks.size(),
                            String.format(Locale.US, "%.1f", speechSeconds(vadRanges)))
                    : ctx.getString(R.string.asr_path_whole, whole.size());
            if (vadText != null) {
                Log.w(TAG, "VAD 结果不可信（" + vadText.length() + " 字 / " + audioSeconds
                        + "s），已重试整段：整段 " + wholeText.length() + " 字");
            }
        }

        text = text.trim();
        long t3 = System.currentTimeMillis();
        long vadMs = t1 - t0;
        long asrMs = t2 - t1 + (t3 - t2);   // 整段重试的那次也算进去
        lastNote = ctx.getString(R.string.asr_note,
                String.format(Locale.US, "%.1f", audioSeconds), path, text.length(),
                String.format(Locale.US, "%.1f", (t3 - t0) / 1000.0),
                String.format(Locale.US, "%.1f", vadMs / 1000.0),
                String.format(Locale.US, "%.1f", asrMs / 1000.0));
        Log.i(TAG, "offline ASR(" + spec.id + ") " + lastNote + " -> " + text);
        return text;
    }

    @Override
    public String lastNote() {
        return lastNote;
    }

    /**
     * 结果可信度：每秒至少 1 个字。
     *
     * <p>正常英语朗读约 13~15 字/秒，所以这个阈值极宽松——只用来抓「明显不对」的结果
     * （如 60 秒音频只出 1 个词、或整段静音被幻觉成一个词）。</p>
     */
    static boolean implausible(String text, double audioSeconds) {
        int len = text == null ? 0 : text.trim().length();
        return len < Math.max(10, (int) audioSeconds);
    }

    /** VAD 切出的语音段总时长（秒），用于诊断。 */
    private static double speechSeconds(List<int[]> ranges) {
        long total = 0;
        for (int[] r : ranges) total += Math.max(0, r[1] - r[0]);
        return total / (double) MODEL_SAMPLE_RATE;
    }

    /** 逐段识别并拼接。 */
    private String runSegments(OfflineRecognizer r, List<float[]> segments) {
        StringBuilder text = new StringBuilder();
        for (float[] segment : segments) {
            String part = recognize(r, segment);
            if (!part.isEmpty()) {
                if (text.length() > 0) text.append(' ');
                text.append(part);
            }
        }
        return text.toString();
    }

    private String recognize(OfflineRecognizer r, float[] samples) {
        OfflineStream stream = r.createStream();
        try {
            stream.acceptWaveform(samples, MODEL_SAMPLE_RATE);
            r.decode(stream);
            String text = r.getResult(stream).getText();
            return text == null ? "" : text.trim();
        } finally {
            stream.release();
        }
    }

    // ── VAD 切句 ──────────────────────────────────────────

    /**
     * VAD 切句：**分块喂入**（跟上游示例一致，1600 样本 ≈ 100ms），末尾 flush 取出最后一段。
     *
     * <p>⚠️ 必须 `flush()`：不 flush 尾部最后一段不会吐出来，末尾内容静默丢失。</p>
     *
     * <p>注意这里**不做「切不出内容就退回整段」的决定**——那是 {@link #transcribe} 的活，
     * 因为「VAD 切出来了但切得不对」比「什么都没切出来」更隐蔽。</p>
     */
    private List<int[]> speechRanges(Vad v, float[] samples) {
        List<int[]> out = new ArrayList<>();
        final int chunk = 1600;
        for (int i = 0; i < samples.length; i += chunk) {
            int end = Math.min(samples.length, i + chunk);
            float[] part = new float[end - i];
            System.arraycopy(samples, i, part, 0, part.length);
            v.acceptWaveform(part);
            drain(v, out);
        }
        v.flush();
        drain(v, out);
        return out;
    }

    private static void drain(Vad v, List<int[]> out) {
        while (!v.empty()) {
            SpeechSegment seg = v.front();
            v.pop();
            float[] s = seg.getSamples();
            if (s != null && s.length > 0) {
                // 记下位置而不是只留音频：后面要按位置切**原始音频**（保留自然停顿）
                out.add(new int[]{seg.getStart(), seg.getStart() + s.length});
            }
        }
    }

    private synchronized Vad vadOrNull() {
        ModelCatalog.Spec spec = ModelCatalog.byId(ModelCatalog.VAD);
        if (!installed(spec)) return null;
        if (vad != null && spec.id.equals(vadLoadedId)) return vad;

        File onnx = ModelManager.findOnnx(models.modelDir(spec));
        if (onnx == null) return null;
        try {
            SileroVadModelConfig silero = new SileroVadModelConfig();
            silero.setModel(onnx.getAbsolutePath());
            silero.setThreshold(0.5f);
            silero.setMinSilenceDuration(0.25f);
            silero.setMinSpeechDuration(0.25f);
            silero.setWindowSize(512);
            silero.setMaxSpeechDuration(20f);

            VadModelConfig config = new VadModelConfig();
            config.setSileroVadModelConfig(silero);
            config.setSampleRate(MODEL_SAMPLE_RATE);
            config.setNumThreads(1);
            config.setProvider("cpu");

            releaseVad();
            // assetManager 传 null：见类注释（传非 null 会 native abort）
            vad = new Vad(null, config);
            vadLoadedId = spec.id;
            return vad;
        } catch (Exception e) {
            // VAD 挂了不该让整个识别失败：退回整段识别
            Log.w(TAG, "VAD init failed, fallback to whole-segment: " + e.getMessage());
            releaseVad();
            return null;
        }
    }

    private void releaseVad() {
        if (vad != null) {
            try {
                vad.release();
            } catch (Exception ignored) {
            }
            vad = null;
            vadLoadedId = null;
        }
    }

    // ── 识别器懒加载 ──────────────────────────────────────

    private synchronized OfflineRecognizer recognizerFor(ModelCatalog.Spec spec) throws IOException {
        if (recognizer != null && spec.id.equals(loadedModelId)) return recognizer;
        releaseRecognizer();

        File dir = models.modelDir(spec);
        // Whisper 的 tokens 叫 <名字>-tokens.txt，不能写死 tokens.txt（否则 Whisper 全部装不上）
        File tokens = ModelManager.findTokens(dir);
        if (tokens == null) throw new IOException(ctx.getString(R.string.asr_model_broken, "tokens.txt"));

        OfflineModelConfig modelConfig = new OfflineModelConfig();
        modelConfig.setTokens(tokens.getAbsolutePath());
        modelConfig.setNumThreads(NUM_THREADS);
        modelConfig.setDebug(false);
        modelConfig.setProvider("cpu");

        File encoder = ModelManager.findByName(dir, "encoder");
        File decoder = ModelManager.findByName(dir, "decoder");
        if (encoder != null && decoder != null) {
            // Whisper：编码器 + 解码器两个文件，语言留空 = 自动判定（与在线模型行为一致）
            OfflineWhisperModelConfig whisper = new OfflineWhisperModelConfig();
            whisper.setEncoder(encoder.getAbsolutePath());
            whisper.setDecoder(decoder.getAbsolutePath());
            modelConfig.setWhisper(whisper);
        } else {
            File onnx = ModelManager.findOnnx(dir);
            if (onnx == null) throw new IOException(ctx.getString(R.string.asr_model_broken, "*.onnx"));
            OfflineSenseVoiceModelConfig senseVoice = new OfflineSenseVoiceModelConfig();
            senseVoice.setModel(onnx.getAbsolutePath());
            senseVoice.setLanguage("auto"); // 中英混合的听力音频都要能识别
            senseVoice.setUseInverseTextNormalization(true);
            modelConfig.setSenseVoice(senseVoice);
        }

        FeatureConfig feature = new FeatureConfig();
        feature.setSampleRate(MODEL_SAMPLE_RATE);
        feature.setFeatureDim(80);

        OfflineRecognizerConfig config = new OfflineRecognizerConfig();
        config.setFeatConfig(feature);
        config.setModelConfig(modelConfig);

        // assetManager 传 null：见类注释（传非 null 会 native abort）
        recognizer = new OfflineRecognizer(null, config);
        loadedModelId = spec.id;
        Log.i(TAG, "loaded ASR model " + spec.id);
        return recognizer;
    }

    private void releaseRecognizer() {
        if (recognizer != null) {
            try {
                recognizer.release();
            } catch (Exception ignored) {
            }
            recognizer = null;
            loadedModelId = null;
        }
    }

    /** 释放模型（服务销毁时调用，避免 native 内存常驻）。 */
    void release() {
        releaseRecognizer();
        releaseVad();
    }

    // ── 纯函数（可单测） ──────────────────────────────────

    /** PCM16 小端 → float 采样（[-1,1]）。 */
    static float[] pcm16ToFloat(byte[] pcm) {
        int n = pcm.length / 2;
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            int lo = pcm[i * 2] & 0xFF;
            int hi = pcm[i * 2 + 1];
            out[i] = (short) ((hi << 8) | lo) / 32768f;
        }
        return out;
    }

    /**
     * 线性插值重采样。采集可能降级到 44.1k/48k，而模型只认 16k——
     * 不重采样会静默掉准确率（不报错），所以宁可多这一步。
     */
    static float[] resample(float[] in, int fromRate, int toRate) {
        if (fromRate == toRate || in.length == 0 || fromRate <= 0 || toRate <= 0) return in;
        int outLen = (int) ((long) in.length * toRate / fromRate);
        if (outLen <= 0) return new float[0];
        float[] out = new float[outLen];
        double step = (double) fromRate / toRate;
        for (int i = 0; i < outLen; i++) {
            double pos = i * step;
            int i0 = (int) pos;
            int i1 = Math.min(i0 + 1, in.length - 1);
            float frac = (float) (pos - i0);
            out[i] = in[i0] * (1f - frac) + in[i1] * frac;
        }
        return out;
    }
}
