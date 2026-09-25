package com.rd.englishcoach;

/**
 * 离线识别模型清单——<b>唯一来源</b>（地位等同 {@link Prefs}）。
 *
 * <p><b>v4 范围</b>：只做离线识别。朗读 / 翻译 / 问答都走在线（见 AGENTS）。
 * 三个识别档位全部由用户在应用内下载，<b>不内置</b>，所以没装任何模型时行为与旧版一致（全在线）。</p>
 *
 * <p><b>为什么下载源不写死在这里</b>：{@code relativePath} 只是相对路径，基址由
 * {@link Prefs#modelBaseUrl()} 配置；URL = 基址 + "/" + 文件名，所以这里的文件名
 * <b>必须与上游一字不差</b>（改了要去上游核对）。</p>
 *
 * <p><b>模型目录约定</b>：解包后目录里要有 {@code *.onnx} + {@code tokens.txt}；
 * 解包后若是「只有一层子目录」会自动拍平到模型目录根下。</p>
 */
final class ModelCatalog {

    /** 模型用途。VAD（切句，识别的依赖）/ ASR（识别）/ TTS（朗读）。 */
    enum Kind { VAD, ASR, TTS }

    /**
     * 模型系列（模型页分组标题用，同系列归一组）。
     * 引擎加载时<b>不</b>按它判类型——那看目录内容（见 {@code OfflineAsrEngine}）。
     */
    enum Family { VAD, WHISPER, QWEN3, SENSEVOICE, TTS }

    /** 一个模型条目的全部静态信息（纯数据，可在 JVM 上直接断言）。 */
    static final class Spec {
        /** 稳定标识：同时用作目录名与持久化 key，<b>不许改名</b>（改了等于用户已装模型全丢）。 */
        final String id;
        final Kind kind;
        /** 显示名资源（文案一律进 strings.xml，不在 Java 里硬编码）。 */
        final int labelRes;
        /** 预估下载体积（字节），用于空间预检。 */
        final long sizeBytes;
        /** 相对下载路径，基址见 {@link Prefs#modelBaseUrl()}。 */
        final String relativePath;
        /** 内容摘要；空字符串 = 未定档，下载时只校验体积。 */
        final String sha256;
        /** 许可说明资源。 */
        final int licenseRes;
        /** 推荐档：模型选择器里排在前面（体积/速度/准确率最平衡的那个）。 */
        final boolean recommended;
        /** 依赖的模型 id（识别依赖 VAD）。 */
        final String[] dependsOn;
        /** 所属系列（模型页分组展示用）。 */
        final Family family;

        Spec(String id, Kind kind, int labelRes, long sizeBytes, String relativePath,
             String sha256, int licenseRes, boolean recommended, Family family,
             String... dependsOn) {
            this.id = id;
            this.kind = kind;
            this.labelRes = labelRes;
            this.sizeBytes = sizeBytes;
            this.relativePath = relativePath;
            this.sha256 = sha256;
            this.licenseRes = licenseRes;
            this.recommended = recommended;
            this.family = family;
            this.dependsOn = dependsOn;
        }

        boolean needsVerify() { return sha256 != null && !sha256.isEmpty(); }

        /**
         * 是否是「单文件载荷」（下载下来直接用，不需要解包）。
         *
         * <p>目前只有 VAD（{@code silero_vad.onnx}）是单文件；识别 / 朗读都是多文件目录，必须是归档。
         * 没这个区分的话 VAD 会报「需要 zip 或 tar.bz2 包」——**下得下来、装不上**（真机踩过）。</p>
         */
        boolean isSingleFilePayload() {
            return relativePath != null && relativePath.endsWith(".onnx");
        }
    }

    /** 语音活动检测：切句用，识别的前置依赖，体积可忽略。 */
    static final String VAD = "silero_vad";
    /** 推荐档：英文准确率接近 large-v3，速度却快得多。 */
    static final String ASR_TURBO = "asr_whisper_turbo";
    /** 小体积档：中英粤日韩都支持、快，准确率中等。 */
    static final String ASR_SENSEVOICE = "asr_sensevoice";
    /** 最高质量档：英文最准，体积最大。 */
    static final String ASR_LARGE_V3 = "asr_whisper_large_v3";
    /** Qwen3 系列：LLM 解码器架构，中文/方言/抗噪强，但慢（非推荐档）。 */
    static final String ASR_QWEN3_06B = "asr_qwen3_06b";
    /** 离线朗读：Piper 英文。一个模型只含一种语言的 voice，所以中英各一条。 */
    static final String TTS_PIPER_EN = "tts_piper_en";
    /** 离线朗读：Piper 中文。 */
    static final String TTS_PIPER_ZH = "tts_piper_zh";

    private static final long MB = 1024L * 1024L;

    private static final Spec[] ALL = {
            new Spec(VAD, Kind.VAD, R.string.model_silero_vad, 1 * MB,
                    "silero_vad.onnx", "", R.string.license_mit, false, Family.VAD),

            // 推荐档放最前：模型选择器直接按清单顺序展示
            new Spec(ASR_TURBO, Kind.ASR, R.string.model_whisper_turbo, 538 * MB,
                    "sherpa-onnx-whisper-turbo.tar.bz2", "", R.string.license_mit, true,
                    Family.WHISPER, VAD),

            new Spec(ASR_SENSEVOICE, Kind.ASR, R.string.model_sensevoice, 156 * MB,
                    "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2", "",
                    R.string.license_funasr, false, Family.SENSEVOICE, VAD),

            new Spec(ASR_LARGE_V3, Kind.ASR, R.string.model_whisper_large_v3, 1019 * MB,
                    "sherpa-onnx-whisper-large-v3.tar.bz2", "", R.string.license_mit, false,
                    Family.WHISPER, VAD),

            // Qwen3-ASR：LLM 解码器架构（官方包实测 878702423 字节）。
            // 慢（RTF 约为 turbo 的十倍量级），放清单末尾，回落优先级也最低。
            new Spec(ASR_QWEN3_06B, Kind.ASR, R.string.model_qwen3_06b, 878702423L,
                    "sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25.tar.bz2", "",
                    R.string.license_apache, false, Family.QWEN3, VAD),

            // 离线朗读（Piper/VITS）：一模型一语言，中英各一条；只想读英文就只装英文那条。
            new Spec(TTS_PIPER_EN, Kind.TTS, R.string.model_piper_en, 64 * MB,
                    "vits-piper-en_US-lessac-medium.tar.bz2", "",
                    R.string.license_per_voice, false, Family.TTS),

            new Spec(TTS_PIPER_ZH, Kind.TTS, R.string.model_piper_zh, 64 * MB,
                    "vits-piper-zh_CN-huayan-medium.tar.bz2", "",
                    R.string.license_per_voice, false, Family.TTS),
    };

    private ModelCatalog() {}

    /** 全部条目（顺序即 UI 展示顺序）。 */
    static Spec[] all() { return ALL.clone(); }

    /** 按 id 查条目；未知 id 返回 null（调用方必须处理，别假设一定存在）。 */
    static Spec byId(String id) {
        if (id == null) return null;
        for (Spec s : ALL) {
            if (s.id.equals(id)) return s;
        }
        return null;
    }

    /** 依赖是否已满足（{@code installed} 由调用方传入，便于纯 JVM 测试）。 */
    static String firstMissingDependency(Spec spec, java.util.Set<String> installed) {
        for (String dep : spec.dependsOn) {
            if (!installed.contains(dep)) return dep;
        }
        return null;
    }
}
