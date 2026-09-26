package com.rd.englishcoach;

import android.content.Context;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 识别降级链：按运行模式决定「先试谁」，失败再试下一个。
 *
 * <p>与朗读链（{@code FallbackSpeechPlayer}）的区别：识别是同步一问一答，
 * 所以这里是一条直线循环而不是状态机。</p>
 *
 * <p>引擎实例<b>由服务长期持有</b>（模型是懒加载并缓存的），别每次识别都新建——
 * 重建一次离线识别器要 1~3 秒。</p>
 */
final class AsrChain {

    private final Context ctx;
    private final Prefs prefs;
    private final AsrEngine offline;
    private final AsrEngine online;
    private String lastNote;

    AsrChain(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.prefs = new Prefs(this.ctx);
        this.offline = new OfflineAsrEngine(this.ctx);
        this.online = new OnlineAsrEngine(this.ctx);
    }

    /**
     * 引擎尝试顺序（纯函数，便于单测）。
     *
     * <p>自动（默认）= 离线优先、在线兜底：<b>没装离线模型时等价于纯在线</b>，
     * 所以默认行为与旧版一致。显式选了某一侧就只走那一侧，免得用户「只要在线」时还去加载离线模型。</p>
     */
    static List<AsrEngine> order(int engineMode, AsrEngine offline, AsrEngine online) {
        switch (engineMode) {
            case Prefs.MODE_ONLINE:
                return Collections.singletonList(online);
            case Prefs.MODE_OFFLINE:
                return Collections.singletonList(offline);
            default:
                return Arrays.asList(offline, online);
        }
    }

    /**
     * 识别整段 PCM，按链尝试。
     *
     * @return 非空文本；都识别不出内容时返回空串
     * @throws IOException 所有可用引擎都失败，或一个可用的都没有（消息已可读）
     */
    String transcribe(byte[] pcm, int sampleRate) throws Exception {
        // 逐个引擎尝试并记下原因：只报最后一条（在线）会把离线的真实原因掩盖掉
        java.util.List<String> failures = new java.util.ArrayList<>();
        boolean anyAvailable = false;
        lastNote = null;
        for (AsrEngine engine : order(prefs.engineMode(), offline, online)) {
            if (!engine.isAvailable()) continue;
            anyAvailable = true;
            try {
                String text = engine.transcribe(pcm, sampleRate);
                if (text != null && !text.trim().isEmpty()) {
                    lastNote = engine.lastNote();   // 诊断摘要给 UI（音频多长/切了几段/多少字）
                    return text.trim();
                }
            } catch (Exception e) {
                failures.add(engineLabel(engine) + " → "
                        + (e.getMessage() != null ? e.getMessage() : e.toString()));
            }
        }
        if (!failures.isEmpty()) {
            throw new IOException(ctx.getString(R.string.msg_asr_all_failed,
                    String.join("\n", failures)));
        }
        if (!anyAvailable) throw new IOException(ctx.getString(R.string.msg_asr_no_engine));
        // 引擎可用但都没识别出内容：不是错误，交给调用方按「没听到」处理
        return "";
    }

    /** 失败文案里区分离线/在线（靠实例判断，不靠引擎自报名字）。 */
    private String engineLabel(AsrEngine engine) {
        return engine == offline
                ? ctx.getString(R.string.engine_offline)
                : ctx.getString(R.string.engine_online);
    }

    /** 上次识别的诊断摘要（可空）：显示在悬浮窗状态栏，用于定位「只识别出一小段」类问题。 */
    String lastNote() {
        return lastNote;
    }

    /** 释放离线模型占用的 native 内存（服务销毁时调用）。 */
    void release() {
        if (offline instanceof OfflineAsrEngine) ((OfflineAsrEngine) offline).release();
    }

    /**
     * 预热离线识别模型（服务开始监听时后台调用）：把首次识别的加载等待挪到用户开口之前。
     *
     * <p>显式「仅在线」时不预热——那会白加载几百 MB 的离线模型。</p>
     */
    void warmUp() {
        if (prefs.engineMode() == Prefs.MODE_ONLINE) return;
        if (offline instanceof OfflineAsrEngine) ((OfflineAsrEngine) offline).preload();
    }
}
