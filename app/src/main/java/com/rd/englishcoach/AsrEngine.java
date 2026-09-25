package com.rd.englishcoach;

/**
 * 「整段音频 → 文本」的抽象。两套实现：{@link OnlineAsrEngine}（云端）/ {@link OfflineAsrEngine}（端上）。
 *
 * <p><b>为什么是同步阻塞式而不是事件回调：</b>识别是「一问一答」，调用方本来就在后台线程里等结果
 * （现有链路就在 {@code networkExec} 里跑）。做成回调只会把一条直线流程拆成状态机，反而更难查。
 * 与朗读不同——朗读要跨「加载/播放/停止」多个时刻，所以那边才用事件式的 {@code SpeechPlayer}。</p>
 */
interface AsrEngine {

    /** 现在能不能用：在线看 key 填没填，离线看模型装没装。 */
    boolean isAvailable();

    /**
     * 识别整段 16bit 单声道 PCM。
     *
     * @param pcm        PCM16 小端、单声道
     * @param sampleRate 采样率；实现需自行重采样到模型要求的采样率
     * @return 识别文本（可能为空串，表示没识别出内容）
     */
    String transcribe(byte[] pcm, int sampleRate) throws Exception;

    /**
     * 上次识别的**诊断摘要**（可空），给 UI 排障用。
     *
     * <p>为什么要有这个：真机出现过「1 分钟音频只识别出 1 个词」这类问题，
     * 光看文本无法判断是「没录到音频」「切段切错」还是「模型把静音幻觉成了词」。
     * 把「音频多长 / 切了几段 / 出了多少字」直接显示出来，一眼定位。</p>
     */
    default String lastNote() {
        return null;
    }
}
