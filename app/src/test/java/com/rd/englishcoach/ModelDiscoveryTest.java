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
}
