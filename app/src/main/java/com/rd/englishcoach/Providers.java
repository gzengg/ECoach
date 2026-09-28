package com.rd.englishcoach;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 常用服务商预置（照 kelivo 的 {@code _defaultBase()} 思路做的「一键换服务商」）。
 *
 * <p>两个用途：</p>
 * <ol>
 *   <li><b>常用按钮</b>：一键把「协议 + 地址」（可选默认模型）填成某家服务商，省得手抄端点；</li>
 *   <li><b>内置候选清单</b>：拉不到 {@code /models} 时兜底——有些服务商根本不提供模型目录，
 *       或者目录被网关挡住，这时至少还能从清单里点选一个 id。</li>
 * </ol>
 *
 * <p>⚠️ 地址是「各家的常见默认值」，不保证长期有效（服务商会改端点）；用户可随手改。
 * 本类<b>不含任何 API Key</b>。</p>
 *
 * <p>⚠️ 同一服务商在不同接口下的地址/协议可能不同（如 DashScope 的识别端点是完整的一长串 URL，
 * 而它的 OpenAI 兼容入口在 {@code /compatible-mode/v1}），所以三个接口各有一份清单。</p>
 */
final class Providers {

    /** 一条预置：服务商显示名 + 该接口下用哪个协议 + 地址 + 内置候选模型（可为空）。 */
    static final class Entry {
        final int nameRes;
        final String protocol;
        final String baseUrl;
        final String[] models;

        Entry(int nameRes, String protocol, String baseUrl, String... models) {
            this.nameRes = nameRes;
            this.protocol = protocol;
            this.baseUrl = baseUrl;
            this.models = models;
        }

        /** 该接口下的默认模型（清单非空时取第一个）。 */
        String defaultModel() {
            return models.length > 0 ? models[0] : "";
        }
    }

    private Providers() {}

    // ── 问答（OpenAI 兼容为主；Gemini / Anthropic 走各自协议） ──

    static List<Entry> chat() {
        List<Entry> out = new ArrayList<>();
        out.add(new Entry(R.string.prov_tokendance, ChatProtocols.OPENAI_CHAT,
                Prefs.DEF_BASE_URL, Prefs.DEF_CHAT_MODEL));
        out.add(new Entry(R.string.prov_openai, ChatProtocols.OPENAI_CHAT,
                "https://api.openai.com/v1", "gpt-4o-mini", "gpt-4o"));
        out.add(new Entry(R.string.prov_deepseek, ChatProtocols.OPENAI_CHAT,
                "https://api.deepseek.com/v1", "deepseek-chat", "deepseek-reasoner"));
        out.add(new Entry(R.string.prov_gemini, ChatProtocols.GEMINI,
                "https://generativelanguage.googleapis.com/v1beta",
                "gemini-2.5-flash", "gemini-2.5-pro"));
        out.add(new Entry(R.string.prov_anthropic, ChatProtocols.ANTHROPIC,
                "https://api.anthropic.com/v1",
                "claude-sonnet-4-5", "claude-haiku-4-5"));
        out.add(new Entry(R.string.prov_moonshot, ChatProtocols.OPENAI_CHAT,
                "https://api.moonshot.cn/v1", "kimi-k2-0905-preview", "moonshot-v1-8k"));
        out.add(new Entry(R.string.prov_siliconflow, ChatProtocols.OPENAI_CHAT,
                "https://api.siliconflow.cn/v1",
                "deepseek-ai/DeepSeek-V3", "Qwen/Qwen2.5-7B-Instruct"));
        out.add(new Entry(R.string.prov_zhipu, ChatProtocols.OPENAI_CHAT,
                "https://open.bigmodel.cn/api/paas/v4", "glm-4-flash", "glm-4-plus"));
        out.add(new Entry(R.string.prov_openrouter, ChatProtocols.OPENAI_CHAT,
                "https://openrouter.ai/api/v1", "openai/gpt-4o-mini"));
        out.add(new Entry(R.string.prov_xai, ChatProtocols.OPENAI_CHAT,
                "https://api.x.ai/v1", "grok-4", "grok-3-mini"));
        out.add(new Entry(R.string.prov_dashscope, ChatProtocols.OPENAI_CHAT,
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "qwen-plus", "qwen-max"));
        out.add(new Entry(R.string.prov_ark, ChatProtocols.OPENAI_CHAT,
                "https://ark.cn-beijing.volces.com/api/v3", "doubao-seed-1-6-250615"));
        return out;
    }

    // ── 识别 ──

    static List<Entry> asr() {
        List<Entry> out = new ArrayList<>();
        out.add(new Entry(R.string.prov_tokendance, AsrProtocols.DASHSCOPE,
                Prefs.DEF_ASR_BASE_URL, Prefs.DEF_ASR_MODEL));
        out.add(new Entry(R.string.prov_tokendance, AsrProtocols.CHAT_AUDIO,
                Prefs.DEF_BASE_URL, "qwen3-asr-flash"));
        out.add(new Entry(R.string.prov_tokendance, AsrProtocols.TRANSCRIPTIONS,
                Prefs.DEF_BASE_URL, "whisper-1"));
        out.add(new Entry(R.string.prov_dashscope, AsrProtocols.TRANSCRIPTIONS,
                "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen3-asr-flash"));
        out.add(new Entry(R.string.prov_openai, AsrProtocols.TRANSCRIPTIONS,
                "https://api.openai.com/v1", "whisper-1", "gpt-4o-transcribe"));
        out.add(new Entry(R.string.prov_siliconflow, AsrProtocols.TRANSCRIPTIONS,
                "https://api.siliconflow.cn/v1", "FunAudioLLM/SenseVoiceSmall"));
        return out;
    }

    // ── 朗读 ──

    static List<Entry> tts() {
        List<Entry> out = new ArrayList<>();
        out.add(new Entry(R.string.prov_tokendance, TtsProtocols.CHAT_TTS,
                Prefs.DEF_TTS_BASE_URL, Prefs.DEF_TTS_MODEL));
        out.add(new Entry(R.string.prov_openai, TtsProtocols.SPEECH,
                "https://api.openai.com/v1", "gpt-4o-mini-tts", "tts-1"));
        out.add(new Entry(R.string.prov_siliconflow, TtsProtocols.SPEECH,
                "https://api.siliconflow.cn/v1", "FunAudioLLM/CosyVoice2-0.5B"));
        out.add(new Entry(R.string.prov_tokendance, TtsProtocols.MINIMAX_T2A,
                "https://tokendance.space/gateway/minimax/v1", "minimax-speech-2.8-turbo"));
        out.add(new Entry(R.string.prov_ark, TtsProtocols.ARK_TTS,
                "https://tokendance.space/gateway/ark", "seed-tts-2.0"));
        return out;
    }

    static List<Entry> of(ModelDiscovery.Kind kind) {
        switch (kind) {
            case ASR: return asr();
            case TTS: return tts();
            default:  return chat();
        }
    }

    /**
     * 该类目所有预置里的候选模型 id（去重、保持声明序）。
     *
     * <p>拉不到 {@code /models} 时用它当兜底清单：至少能让用户点一个正确的 id，
     * 而不是对着空列表自己敲。</p>
     */
    static List<String> fallbackModels(ModelDiscovery.Kind kind) {
        Set<String> seen = new LinkedHashSet<>();
        for (Entry e : of(kind)) {
            for (String m : e.models) if (!m.isEmpty()) seen.add(m);
        }
        return new ArrayList<>(seen);
    }
}
