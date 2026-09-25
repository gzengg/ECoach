package com.rd.englishcoach;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 离线朗读（sherpa-onnx + Piper/VITS）的 {@link SpeechPlayer} 实现。
 *
 * <p><b>为什么复用 SpeechPlayer 而不另造接口：</b>现有
 * {@code SpeechPlayer + FallbackSpeechPlayer} 已经就是「接口 + 降级链」，
 * 再加一层平行抽象只会让调用方和测试都维护两套。这里只补一个「离线实现」，
 * 链路由 {@code CaptureService} 组装。</p>
 *
 * <p><b>模型按语言选</b>：Piper 一个模型只含一种语言的 voice，所以中英各一条。
 * 对应语言没装就返回「不可用」，由降级链回落到在线 TTS（不是崩，也不是悄悄用系统 TTS）。</p>
 *
 * <p>⚠️ <b>assetManager 必须传 null</b>：模型在私有目录（绝对路径），传 {@code ctx.getAssets()}
 * 会让 sherpa 把绝对路径当成 assets 路径，读不到文件后<b>直接 native abort</b>（无 Java 异常）。</p>
 *
 * <p><b>模型懒加载</b>：首次朗读才加载（1~3 秒），加载结果缓存复用；换模型会重建。</p>
 */
final class SherpaTtsEngine implements SpeechPlayer {

    private static final String TAG = "SherpaTts";
    /** 别抢 UI 的核：2 线程够用。 */
    private static final int NUM_THREADS = 2;

    private final Context ctx;
    private final ModelManager models;
    private final AudioPlayer audioPlayer;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService exec = Executors.newSingleThreadExecutor();

    private Listener listener;
    private String currentKey;
    /** 代数计数：stop()/新 speak 时递增，在途的合成结果据此作废。 */
    private int generation;

    private OfflineTts tts;
    private String loadedModelId;

    SherpaTtsEngine(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.models = new ModelManager(this.ctx);
        this.audioPlayer = new AudioPlayer(this.ctx);
    }

    @Override
    public void speak(String key, String text, String langHint) {
        if (text == null || text.isEmpty()) return;

        // 同 key 再点 = 停止
        if (key.equals(currentKey) && audioPlayer.isPlaying()) {
            stop();
            return;
        }

        generation++;
        final int gen = generation;
        currentKey = key;

        byte[] cached = audioPlayer.getCached(key);
        if (cached != null) {
            play(key, cached);
            return;
        }

        notifyState(key, State.LOADING, null);
        final String lang = langHint;
        exec.execute(() -> {
            try {
                ModelCatalog.Spec spec = pickSpec(lang);
                if (spec == null) {
                    fail(gen, key, ctx.getString(R.string.tts_offline_missing));
                    return;
                }
                OfflineTts engine = engineFor(spec);
                GeneratedAudio audio = engine.generate(text, 0, 1.0f);
                if (audio == null || audio.getSamples() == null || audio.getSamples().length == 0) {
                    throw new IOException("合成结果为空");
                }
                byte[] wav = WavUtil.toWav(toPcm16(audio.getSamples()), audio.getSampleRate());
                main.post(() -> {
                    if (gen != generation || !key.equals(currentKey)) return; // 过期结果丢弃
                    play(key, wav);
                });
            } catch (Exception e) {
                Log.e(TAG, "synthesize failed: " + e.getMessage());
                fail(gen, key, e.getMessage() != null ? e.getMessage() : e.toString());
            }
        });
    }

    @Override
    public void stop() {
        generation++;
        boolean wasPlaying = audioPlayer.isPlaying();
        String key = currentKey;
        audioPlayer.stop();
        currentKey = null;
        if (wasPlaying && key != null) notifyState(key, State.IDLE, null);
    }

    @Override
    public boolean isSpeaking() {
        return audioPlayer.isPlaying();
    }

    @Override
    public void release() {
        generation++;
        releaseEngine();
        audioPlayer.release();
        exec.shutdownNow();
    }

    @Override
    public void setListener(Listener l) {
        this.listener = l;
    }

    // ── 模型选择与加载 ────────────────────────────────────

    /** 按语言挑模型：中文 → Piper 中文，其余 → Piper 英文；对应语言没装返回 null。 */
    ModelCatalog.Spec pickSpec(String langHint) {
        String id = isChinese(langHint) ? ModelCatalog.TTS_PIPER_ZH : ModelCatalog.TTS_PIPER_EN;
        ModelCatalog.Spec spec = ModelCatalog.byId(id);
        return installed(spec) ? spec : null;
    }

    private boolean installed(ModelCatalog.Spec spec) {
        return spec != null && models.stateOf(spec) == ModelManager.State.INSTALLED;
    }

    private static boolean isChinese(String langHint) {
        return langHint != null && langHint.startsWith("zh");
    }

    /** 懒加载 + 换模型重建。加载失败会抛出，由调用方转成可读错误。 */
    private synchronized OfflineTts engineFor(ModelCatalog.Spec spec) throws IOException {
        if (tts != null && spec.id.equals(loadedModelId)) return tts;
        releaseEngine();

        File dir = models.modelDir(spec);
        File onnx = ModelManager.findOnnx(dir);
        if (onnx == null) throw new IOException(ctx.getString(R.string.tts_model_broken, "*.onnx"));
        File tokens = ModelManager.findTokens(dir);   // Piper 是 tokens.txt，但统一走后缀查找更稳
        if (tokens == null) throw new IOException(ctx.getString(R.string.tts_model_broken, "tokens.txt"));

        OfflineTtsVitsModelConfig vits = new OfflineTtsVitsModelConfig();
        vits.setModel(onnx.getAbsolutePath());
        vits.setTokens(tokens.getAbsolutePath());
        String espeak = dirPath(new File(dir, "espeak-ng-data"));
        String dict = dirPath(new File(dir, "dict"));
        String lexicons = joinLexicons(dir);
        if (espeak != null) vits.setDataDir(espeak);
        if (dict != null) vits.setDictDir(dict);
        if (lexicons != null) vits.setLexicon(lexicons);

        OfflineTtsModelConfig modelConfig = new OfflineTtsModelConfig();
        modelConfig.setNumThreads(NUM_THREADS);
        modelConfig.setDebug(false);
        modelConfig.setProvider("cpu");
        modelConfig.setVits(vits);

        OfflineTtsConfig config = new OfflineTtsConfig();
        config.setModel(modelConfig);
        config.setMaxNumSentences(1);

        // assetManager 传 null：见类注释（传非 null 会 native abort）
        tts = new OfflineTts(null, config);
        loadedModelId = spec.id;
        Log.i(TAG, "loaded TTS model " + spec.id + " from " + dir.getAbsolutePath());
        return tts;
    }

    private void releaseEngine() {
        if (tts != null) {
            try {
                tts.release();
            } catch (Exception ignored) {
            }
            tts = null;
            loadedModelId = null;
        }
    }

    /** 目录存在才返回路径（sherpa-onnx 对空字符串与不存在的路径处理不一致，宁可传 null 走默认）。 */
    private static String dirPath(File dir) {
        return dir.isDirectory() ? dir.getAbsolutePath() : null;
    }

    /** 把目录里所有 {@code lexicon*.txt} 按名排序拼成 sherpa-onnx 要的逗号分隔串；没有则返回 null。 */
    static String joinLexicons(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return null;
        List<String> paths = new ArrayList<>();
        for (File f : files) {
            String n = f.getName();
            if (f.isFile() && n.startsWith("lexicon") && n.endsWith(".txt")) {
                paths.add(f.getAbsolutePath());
            }
        }
        if (paths.isEmpty()) return null;
        String[] sorted = paths.toArray(new String[0]);
        Arrays.sort(sorted);
        return String.join(",", sorted);
    }

    /** float 采样（[-1,1]）→ 16bit 小端 PCM（{@link WavUtil} 要的格式）。 */
    static byte[] toPcm16(float[] samples) {
        byte[] out = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            float s = samples[i];
            if (s > 1f) s = 1f;
            if (s < -1f) s = -1f;
            int v = Math.round(s * 32767f);
            out[i * 2] = (byte) (v & 0xFF);
            out[i * 2 + 1] = (byte) ((v >> 8) & 0xFF);
        }
        return out;
    }

    // ── 播放与回调 ────────────────────────────────────────

    private void play(String key, byte[] wav) {
        audioPlayer.play(key, wav, new AudioPlayer.Listener() {
            @Override public void onComplete(String k) { notifyState(k, State.IDLE, null); }
            @Override public void onError(String k, String err) { notifyState(k, State.ERROR, err); }
        });
        notifyState(key, State.PLAYING, null);
    }

    private void fail(int gen, String key, String message) {
        main.post(() -> {
            if (gen != generation || !key.equals(currentKey)) return; // 过期错误不报
            notifyState(key, State.ERROR, message);
        });
    }

    private void notifyState(String key, State state, String error) {
        if (listener != null) listener.onState(key, state, error);
    }
}
