package com.rd.englishcoach;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Providers 预置清单的契约测试（纯 JVM，不打网络）。
 *
 * <p>清单出错的表现都很隐蔽：协议写错 → 选完芯片点不亮、要走错分支；地址格式乱 → 拼出
 * {@code //models} 或 404；默认模型没写上 → 用户还得自己敲一份。所以这些都必须钉住。</p>
 */
public class ProvidersTest {

    private static final List<ModelDiscovery.Kind> KINDS =
            Arrays.asList(ModelDiscovery.Kind.CHAT, ModelDiscovery.Kind.ASR, ModelDiscovery.Kind.TTS);

    /** 默认（第一条）必须指向 TokenDance 与 Prefs 里的默认值：软件开箱即用这一家的模型。 */
    @Test
    public void firstEntryOfEachKind_usesTokenDanceDefaults() {
        assertEquals(Prefs.DEF_BASE_URL, Providers.chat().get(0).baseUrl);
        assertEquals(Prefs.DEF_CHAT_MODEL, Providers.chat().get(0).models[0]);

        assertEquals(Prefs.DEF_ASR_BASE_URL, Providers.asr().get(0).baseUrl);
        assertEquals(Prefs.DEF_ASR_MODEL, Providers.asr().get(0).models[0]);

        assertEquals(Prefs.DEF_TTS_BASE_URL, Providers.tts().get(0).baseUrl);
        assertEquals(Prefs.DEF_TTS_MODEL, Providers.tts().get(0).models[0]);

        for (ModelDiscovery.Kind k : KINDS) {
            assertFalse(k + " 的默认服务商必须是 TokenDance 默认值",
                    Providers.of(k).get(0).baseUrl.isEmpty());
        }
    }

    /** 每条预置的协议必须是该接口真实支持的协议——写错就等于给用户一个选不中的选项。 */
    @Test
    public void everyEntry_protocolIsSupportedByThatInterface() {
        Set<String> chatOk = new HashSet<>(Arrays.asList(ChatProtocols.ALL));
        Set<String> asrOk = new HashSet<>(Arrays.asList(AsrProtocols.ALL));
        Set<String> ttsOk = new HashSet<>(Arrays.asList(TtsProtocols.ALL));
        for (Providers.Entry e : Providers.chat()) assertTrue("问答协议非法：" + e.protocol, chatOk.contains(e.protocol));
        for (Providers.Entry e : Providers.asr()) assertTrue("识别协议非法：" + e.protocol, asrOk.contains(e.protocol));
        for (Providers.Entry e : Providers.tts()) assertTrue("朗读协议非法：" + e.protocol, ttsOk.contains(e.protocol));
    }

    /**
     * 地址必须是干净的根地址：无尾斜杠（拼接时会出现 {@code //models}）、带 http(s) 前缀。
     * 尾斜杠在部分网关上是 404 的直接原因，且用户很难看出。
     */
    @Test
    public void everyEntry_baseUrlIsClean() {
        for (ModelDiscovery.Kind k : KINDS) {
            for (Providers.Entry e : Providers.of(k)) {
                assertTrue(k + " 的地址必须带 http(s) 前缀：" + e.baseUrl,
                        e.baseUrl.startsWith("http://") || e.baseUrl.startsWith("https://"));
                assertFalse(k + " 的地址不能有尾斜杠：" + e.baseUrl, e.baseUrl.endsWith("/"));
                assertTrue("服务商名必须来自 strings.xml（不许硬编码中文）", e.nameRes != 0);
            }
        }
    }

    /** 兜底清单：非空、去重、按声明序，且第一条就是该接口的默认模型。 */
    @Test
    public void fallbackModels_areDedupedAndNonEmpty() {
        for (ModelDiscovery.Kind k : KINDS) {
            List<String> m = Providers.fallbackModels(k);
            assertFalse(k + " 的内置候选清单不能为空", m.isEmpty());
            assertEquals(k + " 的内置候选清单不能有重复", new HashSet<>(m).size(), m.size());
            for (String id : m) assertFalse(k + " 的候选 id 不能为空串", id.trim().isEmpty());
            assertEquals(k + " 的候选第一条应是该接口默认模型",
                    Providers.of(k).get(0).models[0], m.get(0));
        }
    }

    /** 默认模型取值规则：清单非空取第一个，空清单返回空串（UI 据此决定要不要覆盖输入框）。 */
    @Test
    public void entry_defaultModel() {
        assertEquals("m", new Providers.Entry(1, ChatProtocols.OPENAI_CHAT, "https://x/v1", "m").defaultModel());
        assertEquals("", new Providers.Entry(1, ChatProtocols.OPENAI_CHAT, "https://x/v1").defaultModel());
    }
}
