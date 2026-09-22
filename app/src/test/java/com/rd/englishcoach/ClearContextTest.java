package com.rd.englishcoach;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.Scanner;
import org.junit.Test;

/**
 * 「清空上下文」链路的契约测试（验证该功能是否真的有用）。
 *
 * <p>完整链路：主界面「清空上下文」→ {@code CaptureService.current.clearHistory()}
 * → {@code ConversationManager.clear()}（真正决定发给 AI 的上下文）
 * + {@code panel.clearTurns()} + {@code panel.clearGrabCards()}。</p>
 *
 * <p>行为层验证在 {@link ConversationManagerTest}（纯 Java，真跑）：
 * 清空后 {@code buildMessages()} 只剩 system 提示词 → 上下文确实清掉了。
 * 这里锁住 UI/服务侧接线与两个已修缺陷：</p>
 * <ol>
 *   <li>取词卡片也是上下文的一部分，必须跟听力卡片一起清（否则面板还显示旧卡片、
 *       AI 已经不知道了）；</li>
 *   <li>清空时若有 AI 请求在途，迟到的回答不得写进持久化历史（会挂到别的记录上）。</li>
 * </ol>
 */
public class ClearContextTest {

    private static String readFile(String path) throws Exception {
        File f = new File(path);
        assertTrue("文件不存在: " + f.getAbsolutePath(), f.exists());
        try (Scanner s = new Scanner(f, "UTF-8")) {
            s.useDelimiter("\\A");
            return s.hasNext() ? s.next() : "";
        }
    }

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

    private String mainActivity() throws Exception {
        return stripComments(readFile("src/main/java/com/rd/englishcoach/MainActivity.java"));
    }

    private String captureService() throws Exception {
        return stripComments(readFile("src/main/java/com/rd/englishcoach/CaptureService.java"));
    }

    // ── 主界面按钮接线 ──────────────────────────

    @Test
    public void mainActivity_clearContextButton_isWired() throws Exception {
        String src = mainActivity();
        int idx = src.indexOf("btnNewChat.setOnClickListener");
        assertTrue("主界面必须有「清空上下文」点击监听", idx >= 0);
        String body = src.substring(idx, Math.min(idx + 400, src.length()));
        assertTrue("必须调用服务的 clearHistory()", body.contains("clearHistory()"));
        assertTrue("成功后要给用户反馈", body.contains("status_chat_cleared"));
        assertTrue("服务未运行时也要提示（不能静默无反应）",
                body.contains("status_service_off"));
    }

    // ── 服务侧：清哪些、不清哪些 ─────────────────

    @Test
    public void clearHistory_clearsConversationAndBothCardLists() throws Exception {
        String body = methodBody(captureService(), "public void clearHistory()");
        assertNotNull("CaptureService 必须有 clearHistory()", body);
        assertTrue("必须清对话上下文（决定发给 AI 的 messages）",
                body.contains("conversation.clear()"));
        assertTrue("必须清听力卡片", body.contains("panel.clearTurns()"));
        assertTrue("取词卡片也是上下文的一部分，必须一起清（缺陷 1）",
                body.contains("panel.clearGrabCards()"));
    }

    @Test
    public void clearHistory_keepsPersistedHistoryFile() throws Exception {
        String body = methodBody(captureService(), "public void clearHistory()");
        assertNotNull(body);
        assertFalse("清空上下文不得删掉持久化的转录历史文件（用户还要回看）",
                body.contains("history.clear()") || body.contains("history.deleteAt"));
    }

    @Test
    public void clearGrabCards_alsoHidesStatusLine() throws Exception {
        String src = stripComments(readFile("src/main/java/com/rd/englishcoach/FloatingPanel.java"));
        String body = methodBody(src, "public void clearGrabCards()");
        assertNotNull("必须有 clearGrabCards", body);
        assertTrue("清取词卡片时也要把状态行收掉（避免残留旧提示）",
                body.contains("tvGrabStatus"));
    }

    // ── 缺陷 2：迟到回答不得污染持久化历史 ────────

    @Test
    public void doAnswer_guardsHistoryWrite() throws Exception {
        String body = methodBody(captureService(), "private void doAnswer(long turnId)");
        assertNotNull("必须有 doAnswer", body);
        int write = body.indexOf("history.updateLastAnswer");
        assertTrue("必须有写持久化历史", write >= 0);
        String before = body.substring(0, write);
        assertTrue("写历史前必须确认轮次仍存在（清空后迟到回答不能写进别的记录，缺陷 2）",
                before.contains("completeAnswer") && before.contains("applied"));
    }

    @Test
    public void doAskQuestion_guardsHistoryWrite() throws Exception {
        String body = methodBody(captureService(),
                "private void doAskQuestion(long turnId, String question)");
        assertNotNull("必须有 doAskQuestion", body);
        int write = body.indexOf("history.updateLastAnswer");
        assertTrue("必须有写持久化历史", write >= 0);
        String before = body.substring(0, write);
        assertTrue("写历史前必须确认轮次仍存在（缺陷 2）",
                before.contains("findById(qt.id) != null"));
    }
}
