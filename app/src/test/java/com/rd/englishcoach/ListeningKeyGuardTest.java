package com.rd.englishcoach;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.Scanner;
import org.junit.Test;

/**
 * 问题1（听力报 Invalid token）的源码契约测试：
 * <ul>
 *   <li>听力/AI 链路必须统一做「未填 API Key」守卫；</li>
 *   <li>守卫必须发生在动作之前（暂停采集/发起回答/发问题）；</li>
 *   <li>取词翻译不受 key 影响（免费接口），不能被守卫波及；</li>
 *   <li>服务端错误必须转成可读文案（不再整段吐 JSON）。</li>
 * </ul>
 */
public class ListeningKeyGuardTest {

    private static String readFile(String path) throws Exception {
        File f = new File(path);
        assertTrue("文件不存在: " + f.getAbsolutePath(), f.exists());
        try (Scanner s = new Scanner(f, "UTF-8")) {
            s.useDelimiter("\\A");
            return s.hasNext() ? s.next() : "";
        }
    }

    /** 去掉块注释与行注释，避免注释里的关键词误判。 */
    private static String stripComments(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int i = 0;
        while (i < src.length()) {
            if (i + 1 < src.length() && src.charAt(i) == '/' && src.charAt(i + 1) == '*') {
                int end = src.indexOf("*/", i + 2);
                i = end < 0 ? src.length() : end + 2;
            } else if (i + 1 < src.length() && src.charAt(i) == '/' && src.charAt(i + 1) == '/') {
                int end = src.indexOf('\n', i);
                i = end < 0 ? src.length() : end;
            } else {
                out.append(src.charAt(i));
                i++;
            }
        }
        return out.toString();
    }

    private static String methodBody(String src, String signature) {
        int start = src.indexOf(signature);
        if (start < 0) return null;
        int brace = src.indexOf('{', start);
        if (brace < 0) return null;
        int depth = 0;
        for (int i = brace; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return src.substring(brace + 1, i);
            }
        }
        return null;
    }

    private String captureService() throws Exception {
        return stripComments(readFile("src/main/java/com/rd/englishcoach/CaptureService.java"));
    }

    @Test
    public void requireApiKey_helper_promptsAndBlocks() throws Exception {
        String body = methodBody(captureService(), "private boolean requireApiKey()");
        assertNotNull("CaptureService 必须有 requireApiKey() 守卫", body);
        assertTrue("守卫必须检查 apiKey()", body.contains("apiKey()"));
        assertTrue("守卫必须用 msg_no_api_key 提示",
                body.contains("msg_no_api_key"));
        assertTrue("未填 key 必须返回 false 以中止流程", body.contains("return false"));
    }

    @Test
    public void doPause_doesNotBlockOnApiKey_becauseOfflineAsrNeedsNoKey() throws Exception {
        // v4：识别改为「离线优先 → 在线」降级链，暂停不再拦 API Key，
        // 否则装了离线模型也暂停不了。守卫在 OnlineAsrEngine / ApiClient 里。
        String body = methodBody(captureService(), "private void doPause()");
        assertNotNull("必须有 doPause", body);
        assertFalse("doPause 不得再拦 API Key", body.contains("requireApiKey()"));
        assertTrue("必须真的走识别链", body.contains("asrChain.transcribe("));
        assertTrue("暂停采集仍必须发生（与识别成败无关）", body.indexOf("listen.toggle()") >= 0);
    }

    @Test
    public void doAnswer_guardsBeforeAnswerRequest() throws Exception {
        String body = methodBody(captureService(), "private void doAnswer(long turnId)");
        assertNotNull("必须有 doAnswer", body);
        int guard = body.indexOf("requireApiKey()");
        int begin = body.indexOf("beginAnswerRequest");
        assertTrue("doAnswer 必须做 key 守卫", guard >= 0);
        assertTrue("守卫必须在发起请求之前", begin >= 0 && guard < begin);
    }

    @Test
    public void doAskQuestion_guardsBeforeAddQuestion() throws Exception {
        String body = methodBody(captureService(),
                "private void doAskQuestion(long turnId, String question)");
        assertNotNull("必须有 doAskQuestion", body);
        int guard = body.indexOf("requireApiKey()");
        int add = body.indexOf("addQuestion");
        assertTrue("doAskQuestion 必须做 key 守卫", guard >= 0);
        assertTrue("守卫必须在创建提问轮次之前", add >= 0 && guard < add);
    }

    @Test
    public void speak_usesSharedNoKeyPrompt() throws Exception {
        String src = captureService();
        String body = methodBody(src, "private void speak(String key, String text, String langHint)");
        assertNotNull("必须有 speak", body);
        assertTrue("朗读也要用统一提示", body.contains("msg_no_api_key"));
        assertFalse("旧字符串 msg_speak_no_api_key 应已清理",
                src.contains("msg_speak_no_api_key"));
    }

    @Test
    public void stringsXml_hasBothPromptStrings() throws Exception {
        String xml = readFile("src/main/res/values/strings.xml");
        assertTrue(xml.contains("<string name=\"msg_no_api_key\">"));
        assertTrue(xml.contains("<string name=\"msg_api_key_invalid\">"));
        assertFalse("旧 key 提示字符串应已删除",
                xml.contains("msg_speak_no_api_key"));
    }

    @Test
    public void grabTranslation_doesNotRequireApiKey() throws Exception {
        // 未填 Key 时取词翻译仍可用 → 翻译链路不得引用 apiKey
        String translator = stripComments(readFile("src/main/java/com/rd/englishcoach/Translator.java"));
        assertFalse("取词翻译（免费接口）不能依赖 API Key", translator.contains("apiKey"));
        // 取词入口也不应被 key 守卫拦截
        String src = captureService();
        String startGrab = methodBody(src, "private void startGrab()");
        assertNotNull("必须有 startGrab", startGrab);
        assertFalse("startGrab 不应被 API Key 守卫拦截（否则取词会一起失效）",
                startGrab.contains("requireApiKey"));
    }

    @Test
    public void asrErrors_areMadeReadable() throws Exception {
        // HTTP 错误转换住在共享的 Http.postJson() 里（ApiClient/TtsClient 共用），
        // 所以这里要连 Http.java 一起看，否则会把「换了个文件」误判成「丢了这个修复」。
        String api = stripComments(readFile("src/main/java/com/rd/englishcoach/ApiClient.java"))
                + stripComments(readFile("src/main/java/com/rd/englishcoach/Http.java"));
        assertTrue("HTTP 错误必须走 friendlyError 转换",
                api.contains("friendlyError(code, resp)"));
        assertTrue("必须识别鉴权类错误并给可执行提示",
                api.contains("MSG_API_KEY_INVALID"));
        assertTrue("必须去掉 request id 噪音", api.contains("request id"));
        assertTrue("未填 key 必须提前抛错（不发请求）",
                api.contains("MSG_NO_API_KEY"));
    }
}
