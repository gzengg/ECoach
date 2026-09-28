package com.rd.englishcoach;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * ModelDiscovery 纯函数单测：模型目录解析 + 三类分类。
 * 网络（fetchModels / probe*）在真机上验证，不在 JVM 测试里打网络。
 */
public class ModelDiscoveryTest {

    private static final String JSON = "{\"data\":["
        + "{\"id\":\"deepseek-v4.1-flash\",\"name\":\"DeepSeek V4.1 Flash\","
        + " \"context_length\":1000000,"
        + " \"supported_protocols\":[\"openai:chat-completions\",\"anthropic:messages\"]},"
        + "{\"id\":\"qwen-audio-3.0-asr-flash\",\"name\":\"Qwen Audio ASR\","
        + " \"context_length\":0,\"supported_protocols\":[\"qwen:audio-asr\"]},"
        + "{\"id\":\"mimo-v2.5-tts\",\"name\":\"MiMo TTS\","
        + " \"context_length\":8000,\"supported_protocols\":[\"openai:chat-completions\"]},"
        + "{\"id\":\"minimax-speech-2.8-hd\",\"name\":\"Speech HD\","
        + " \"context_length\":0,\"supported_protocols\":[\"minimax:t2a_v2\"]},"
        + "{\"id\":\"seedream-5.0\",\"name\":\"Seedream\","
        + " \"context_length\":0,\"supported_protocols\":[\"ark:image-generations\"]}"
        + "]}";

    @Test
    public void parseModels_readsFields() {
        List<ModelDiscovery.ModelInfo> all = ModelDiscovery.parseModels(JSON);
        assertEquals(5, all.size());
        assertEquals("deepseek-v4.1-flash", all.get(0).id);
        assertEquals(2, all.get(0).protocols.size());
        assertEquals(1000000, all.get(0).contextLength);
    }

    @Test
    public void parseModels_nonJson_returnsEmpty() {
        assertTrue(ModelDiscovery.parseModels("<html>gateway error</html>").isEmpty());
        assertTrue(ModelDiscovery.parseModels("{}").isEmpty());
    }

    @Test
    public void kindOf_chat() {
        List<ModelDiscovery.ModelInfo> all = ModelDiscovery.parseModels(JSON);
        assertEquals(ModelDiscovery.Kind.CHAT, ModelDiscovery.kindOf(all.get(0)));
    }

    @Test
    public void kindOf_asr() {
        List<ModelDiscovery.ModelInfo> all = ModelDiscovery.parseModels(JSON);
        assertEquals(ModelDiscovery.Kind.ASR, ModelDiscovery.kindOf(all.get(1)));
    }

    @Test
    public void kindOf_tts_byId_evenWhenProtocolIsChatCompletions() {
        // mimo-v2.5-tts 只声明 openai:chat-completions、context_length>0，
        // 仅靠协议会误判成问答，必须靠 id 里的 tts 识别
        List<ModelDiscovery.ModelInfo> all = ModelDiscovery.parseModels(JSON);
        assertEquals(ModelDiscovery.Kind.TTS, ModelDiscovery.kindOf(all.get(2)));
    }

    @Test
    public void kindOf_tts_byProtocol() {
        List<ModelDiscovery.ModelInfo> all = ModelDiscovery.parseModels(JSON);
        assertEquals(ModelDiscovery.Kind.TTS, ModelDiscovery.kindOf(all.get(3)));
    }

    @Test
    public void kindOf_nonConversational_isNull() {
        List<ModelDiscovery.ModelInfo> all = ModelDiscovery.parseModels(JSON);
        assertNull(ModelDiscovery.kindOf(all.get(4)));
    }

    @Test
    public void byKind_filtersCorrectly() {
        List<ModelDiscovery.ModelInfo> all = ModelDiscovery.parseModels(JSON);
        assertEquals(1, ModelDiscovery.byKind(all, ModelDiscovery.Kind.CHAT).size());
        assertEquals(1, ModelDiscovery.byKind(all, ModelDiscovery.Kind.ASR).size());
        assertEquals(2, ModelDiscovery.byKind(all, ModelDiscovery.Kind.TTS).size());
    }

    @Test
    public void modelsUrl_openAiRoot_appendsModels() {
        assertEquals("https://x/v1/models", ModelDiscovery.modelsUrl("https://x/v1"));
        assertEquals("https://x/v1/models", ModelDiscovery.modelsUrl("https://x/v1/"));
    }

    @Test
    public void modelsUrl_dashScopeEndpoint_usesGatewayCatalog() {
        // 识别端点是完整路径，没有 /models；要用同网关的目录
        assertEquals("https://tokendance.space/gateway/v1/models", ModelDiscovery.modelsUrl(
                "https://tokendance.space/gateway/alibaba/api/v1/services/aigc/multimodal-generation/generation"));
    }

    @Test
    public void modelsUrl_vendorGatewayPaths_useGatewayCatalog() {
        // 厂商协议的 Base 不是 /gateway/v1*，直接拼 /models 会 404（真机：朗读模型列表出不来）
        assertEquals("https://tokendance.space/gateway/v1/models",
                ModelDiscovery.modelsUrl("https://tokendance.space/gateway/minimax/v1"));
        assertEquals("https://tokendance.space/gateway/v1/models",
                ModelDiscovery.modelsUrl("https://tokendance.space/gateway/ark"));
    }

    @Test
    public void modelsUrl_geminiBeta_keepsOwnPath() {
        assertEquals("https://tokendance.space/gateway/v1beta/models",
                ModelDiscovery.modelsUrl("https://tokendance.space/gateway/v1beta"));
    }

    /** new-api / tbtk 风格的目录：字段是 supported_endpoint_types，且没有 context_length。 */
    private static final String NEW_API_JSON = "{\"data\":["
            + "{\"id\":\"gpt-5.6-terra\",\"object\":\"model\",\"created\":1,"
            + " \"owned_by\":\"openai\",\"supported_endpoint_types\":[\"openai\"]},"
            + "{\"id\":\"claude-opus-5\",\"object\":\"model\",\"created\":1,"
            + " \"owned_by\":\"claude\","
            + " \"supported_endpoint_types\":[\"openai\",\"openai-response\",\"anthropic\"]}"
            + "]}";

    @Test
    public void parseModels_readsSupportedEndpointTypes() {
        List<ModelDiscovery.ModelInfo> all = ModelDiscovery.parseModels(NEW_API_JSON);
        assertEquals(2, all.size());
        assertEquals(2, ModelDiscovery.byKind(all, ModelDiscovery.Kind.CHAT).size());
    }

    /**
     * Gemini 的目录形状与其他家都不同：列表在 {@code models}（不是 {@code data}）、
     * id 带 {@code models/} 前缀、字段叫 {@code displayName} / {@code inputTokenLimit}。
     * 不认这三种形状中的任意一种，用户换成 Gemini 就「一个模型都选不了」。
     */
    @Test
    public void parseModels_geminiShape() {
        String json = "{\"models\":[{\"name\":\"models/gemini-2.5-flash\","
                + "\"displayName\":\"Gemini 2.5 Flash\",\"inputTokenLimit\":1048576}]}";
        List<ModelDiscovery.ModelInfo> all = ModelDiscovery.parseModels(json);
        assertEquals(1, all.size());
        assertEquals("models/ 前缀必须剥掉（拼 URL 时自己加）", "gemini-2.5-flash", all.get(0).id);
        assertEquals("Gemini 2.5 Flash", all.get(0).name);
        assertEquals(1048576, all.get(0).contextLength);
    }

    /** Anthropic 的目录条目只有 id + display_name（无 supported_protocols）。 */
    @Test
    public void parseModels_anthropicShape() {
        String json = "{\"data\":[{\"id\":\"claude-sonnet-4-5\","
                + "\"display_name\":\"Claude Sonnet 4.5\"}]}";
        List<ModelDiscovery.ModelInfo> all = ModelDiscovery.parseModels(json);
        assertEquals(1, all.size());
        assertEquals("claude-sonnet-4-5", all.get(0).id);
        assertEquals("Claude Sonnet 4.5", all.get(0).name);
    }

    /**
     * 拉目录的鉴权头按协议发：Gemini 只认 x-goog-api-key，Anthropic 要 x-api-key + 版本头。
     * 统一发 Bearer 的话这两家直接 401，用户看到的是「没拿到模型」但地址完全正确。
     */
    @Test
    public void headers_perProtocol() {
        assertTrue(ModelDiscovery.headers(ChatProtocols.GEMINI, "k").containsKey("x-goog-api-key"));
        assertFalse("Gemini 不能发 Bearer（会 401）",
                ModelDiscovery.headers(ChatProtocols.GEMINI, "k").containsKey("Authorization"));

        assertTrue(ModelDiscovery.headers(ChatProtocols.ANTHROPIC, "k").containsKey("x-api-key"));
        assertEquals("2023-06-01",
                ModelDiscovery.headers(ChatProtocols.ANTHROPIC, "k").get("anthropic-version"));

        assertEquals("Bearer k",
                ModelDiscovery.headers(ChatProtocols.OPENAI_CHAT, "k").get("Authorization"));

        // 无 Key 时不得凭空发空头（有些网关会因空 Authorization 直接 401）
        assertFalse(ModelDiscovery.headers(ChatProtocols.OPENAI_CHAT, "").containsKey("Authorization"));
    }
}
