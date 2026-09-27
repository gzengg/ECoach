package com.rd.englishcoach;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 在线朗读 {@link SpeechPlayer} 实现：按 {@code ttsProtocol} 走不同协议
 * （mimo chat+modalities / OpenAI /audio/speech / MiniMax T2A / 豆包 Ark TTS）。
 * 网络请求在后台线程，播放/回调在主线程。
 *
 * <p><b>为什么必须分段合成：</b>长文本整段发给 TTS 会失败（真机：1000 词直接报错，
 * 之后兜底到系统朗读也失败，用户看到的是「系统朗读初始化失败」——根因被兜底错误盖住了）。
 * 所以按句切成 ≤{@link #MAX_TTS_CHARS} 字符的片段逐段合成。</p>
 *
 * <p><b>边合成边播（流水线）</b>：合成一段就立刻开播，同时后台继续合成后面的段。
 * 整段文本有几百字时，若等全部合成完再播，用户要干等十几秒；
 * 流水线让首段出声时间与文本长度无关。段与段之间若合成来不及，中间会有短暂停顿——
 * 这是可接受的，好过整体晚出声。</p>
 *
 * <p>缓存按「片段」粒度（{@code key#i}）：同一段回答重复朗读时逐段命中，不必重合成。</p>
 */
public final class OnlineTtsEngine implements SpeechPlayer {

    private static final String TAG = "OnlineTts";

    /**
     * 单次合成的文本上限（字符）。
     *
     * <p>上游没给出确切限制，但真机在 1000 词（约 5000 字符）时必失败，所以按 300 保守切。
     * 分段有流水线兜底，多几次请求不会拖慢首段出声。</p>
     */
    static final int MAX_TTS_CHARS = 300;

    private final Context ctx;
    private final Prefs prefs;
    private final AudioPlayer audioPlayer;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private Listener listener;
    private String currentKey;
    /** 代数计数：stop()/新 speak 时递增，在途的网络合成结果据此作废。 */
    private int generation;

    // ── 分段播放队列（只在主线程访问） ─────────────────────
    private final ArrayDeque<Chunk> queue = new ArrayDeque<>();
    /** 是否正在播某一段。 */
    private boolean playing;
    /** 后台是否还在合成（合成未完但队列暂时空，不能报 IDLE）。 */
    private boolean producing;

    public OnlineTtsEngine(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.prefs = new Prefs(this.ctx);
        this.audioPlayer = new AudioPlayer(this.ctx);
    }

    @Override
    public void speak(String key, String text, String langHint) {
        if (text == null || text.isEmpty()) return;

        if (prefs.ttsApiKey().isEmpty()) {
            notifyState(key, State.ERROR, ctx.getString(R.string.msg_no_api_key));
            return;
        }

        // 同 key 再点 = 停止（LOADING 期间 isSpeaking 为 false，所以要连队列/合成状态一起判）
        if (key.equals(currentKey) && (playing || producing || audioPlayer.isPlaying())) {
            stop();
            return;
        }

        generation++; // 作废在途的合成结果
        final int gen = generation;
        currentKey = key;
        queue.clear();
        playing = false;

        final List<String> chunks = TextChunker.split(text, MAX_TTS_CHARS);
        final String voice = pickVoice(langHint);
        producing = true;

        notifyState(key, State.LOADING, null);
        exec.execute(() -> {
            for (int i = 0; i < chunks.size(); i++) {
                if (gen != generation) return;
                byte[] wav = audioPlayer.getCached(chunkKey(key, i));
                if (wav == null) {
                    try {
                        String model = prefs.ttsModel().isEmpty() ? Prefs.DEF_TTS_MODEL : prefs.ttsModel();
                        wav = TtsProtocols.synthesize(prefs.ttsProtocol(), prefs.ttsBaseUrl(),
                                prefs.ttsApiKey(), model, voice, chunks.get(i));
                    } catch (Exception e) {
                        Log.e(TAG, "synthesize failed (chunk " + i + "/" + chunks.size()
                                + "): " + e.getMessage());
                        final String msg = ctx.getString(R.string.speak_online_failed,
                                e.getMessage() != null ? e.getMessage() : e.toString());
                        mainHandler.post(() -> {
                            if (gen != generation) return;
                            producing = false;
                            queue.clear();
                            notifyState(key, State.ERROR, msg);
                        });
                        return;
                    }
                }
                final byte[] w = wav;
                final int index = i;
                mainHandler.post(() -> {
                    if (gen != generation) return;
                    queue.add(new Chunk(chunkKey(key, index), w));
                    if (!playing) playNext(key);
                });
            }
            mainHandler.post(() -> {
                if (gen != generation) return;
                producing = false;
                if (!playing && queue.isEmpty()) notifyState(key, State.IDLE, null);
            });
        });
    }

    @Override
    public void stop() {
        generation++; // 取消在途合成
        boolean wasActive = playing || producing || audioPlayer.isPlaying();
        String key = currentKey;
        producing = false;
        playing = false;
        queue.clear();
        audioPlayer.stop();
        currentKey = null;
        if (wasActive && key != null) {
            notifyState(key, State.IDLE, null); // 让按钮颜色复位
        }
    }

    @Override
    public boolean isSpeaking() {
        return playing || audioPlayer.isPlaying();
    }

    @Override
    public void release() {
        audioPlayer.release();
        exec.shutdownNow();
    }

    @Override
    public void setListener(Listener l) {
        this.listener = l;
    }

    // ── 内部 ──────────────────────────────────────────────

    /** 队列里有就接着播；播完且合成也结束才报 IDLE（否则会中途闪一下 IDLE）。 */
    private void playNext(String key) {
        Chunk next = queue.poll();
        if (next == null) {
            playing = false;
            if (!producing) notifyState(key, State.IDLE, null);
            return;
        }
        playing = true;
        audioPlayer.play(next.key, next.wav, new AudioPlayer.Listener() {
            @Override public void onComplete(String k) {
                if (key.equals(currentKey)) playNext(key);
            }
            @Override public void onError(String k, String err) {
                if (!key.equals(currentKey)) return;
                producing = false;
                queue.clear();
                playing = false;
                notifyState(key, State.ERROR, err);
            }
        });
        notifyState(key, State.PLAYING, null);
    }

    /** 片段缓存键：按片段粒度缓存，重复朗读逐段命中。 */
    private static String chunkKey(String key, int index) {
        return key + "#" + index;
    }

    /** 根据语言提示选择声音。Prefs 已保证非空（空值回落默认声音），这里不再重复兜底。 */
    private String pickVoice(String langHint) {
        return (langHint != null && langHint.startsWith("zh"))
                ? prefs.ttsVoiceChinese() : prefs.ttsVoiceEnglish();
    }

    private void notifyState(String key, State state, String error) {
        if (listener != null) listener.onState(key, state, error);
    }

    /** 一段已合成的音频 + 它的缓存键。 */
    private static final class Chunk {
        final String key;
        final byte[] wav;

        Chunk(String key, byte[] wav) {
            this.key = key;
            this.wav = wav;
        }
    }
}
