package com.rd.englishcoach;

/**
 * 在线识别：把现有的 {@link ApiClient#transcribe} 包成 {@link AsrEngine}。
 *
 * <p>未填 key 时 {@link #isAvailable()} 为 false，链会跳过它；若被直接调用，
 * {@code ApiClient} 自己会抛出可读的「请先到设置页填 API Key」。</p>
 */
final class OnlineAsrEngine implements AsrEngine {

    /**
     * 单次上传的音频上限（秒）。
     *
     * <p>服务端对单次音频长度有限制，超了会<b>静默截断</b>（真机现象：1 分钟只识别出前一段）。
     * 拿不到确切上限，所以按 25 秒保守切段（静音处下刀），宁可多几次请求。
     * 不超长时仍是单次请求，与旧版行为完全一致。</p>
     */
    static final int MAX_CHUNK_SECONDS = 25;

    private final Prefs prefs;
    private final android.content.Context ctx;
    private String lastNote;

    OnlineAsrEngine(android.content.Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.prefs = new Prefs(ctx);
    }

    @Override
    public boolean isAvailable() {
        return !prefs.apiKey().trim().isEmpty();
    }

    @Override
    public String lastNote() {
        return lastNote;
    }

    @Override
    public String transcribe(byte[] pcm, int sampleRate) throws Exception {
        StringBuilder out = new StringBuilder();
        long t0 = System.currentTimeMillis();
        java.util.List<byte[]> chunks = AudioChunker.splitPcm(pcm,
                AudioChunker.maxSamples(sampleRate, MAX_CHUNK_SECONDS));
        for (byte[] chunk : chunks) {
            byte[] wav = WavUtil.toWav(chunk, sampleRate);
            String part = ApiClient.transcribe(wav, prefs.baseUrl(), prefs.apiKey(),
                    prefs.asrModel());
            if (part != null && !part.trim().isEmpty()) {
                if (out.length() > 0) out.append(' ');
                out.append(part.trim());
            }
        }
        double elapsed = (System.currentTimeMillis() - t0) / 1000.0;
        // 与离线同格式的短摘要（悬浮窗那行要短，见 strings.asr_note）
        lastNote = ctx.getString(R.string.asr_note,
                String.format(java.util.Locale.US, "%.1f", pcm.length / 2.0 / sampleRate),
                out.length(), String.format(java.util.Locale.US, "%.1f", elapsed));
        return out.toString();
    }
}
