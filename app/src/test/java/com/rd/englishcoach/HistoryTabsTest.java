package com.rd.englishcoach;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.Scanner;
import org.junit.Test;

/**
 * 「转录历史」对话框分 Tab 的契约测试。
 *
 * <p>需求：取词记录与听力转录<b>分成两个 Tag</b>展示（不再混在一个列表里），
 * 且对话框底部按钮文字改为白色。</p>
 *
 * <p>MainActivity 依赖 Android 框架，无法在 JVM 上实例化，故用源码契约 +
 * HistoryStore 行为测试（见 {@link HistoryStoreTest}）共同锁住。</p>
 */
public class HistoryTabsTest {

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

    private String mainActivity() throws Exception {
        return stripComments(readFile("src/main/java/com/rd/englishcoach/MainActivity.java"));
    }

    // ── 两个 Tab ────────────────────────────────

    @Test
    public void historyDialog_hasTwoTabs() throws Exception {
        String body = methodBody(mainActivity(), "private void showTranscriptHistory()");
        assertNotNull("必须有 showTranscriptHistory", body);
        assertTrue("必须有「转录」Tab", body.contains("history_tab_transcript"));
        assertTrue("必须有「取词」Tab", body.contains("history_tab_grab"));
        assertTrue("必须用 countByType 给 Tab 显示条数",
                body.contains("HistoryStore.countByType"));
    }

    @Test
    public void historyDialog_filtersListByType() throws Exception {
        String src = mainActivity();
        String body = methodBody(src, "private void renderHistoryList(LinearLayout list, HistoryStore store,");
        assertNotNull("必须有 renderHistoryList", body);
        assertTrue("列表必须按类型过滤（取词与转录分开）",
                body.contains("e.isGrab() != showGrab"));
        assertTrue("取词条目按「原文 → 译文」展示",
                body.contains("\" → \""));
    }

    @Test
    public void historyDialog_tabSwitchReRendersInPlace() throws Exception {
        String body = methodBody(mainActivity(), "private void showTranscriptHistory()");
        assertNotNull(body);
        assertTrue("切 Tab 必须原地重渲染（不重开对话框）", body.contains("render[0].run()"));
        assertTrue("两个 Tab 都要能点", body.contains("tabTranscript.setOnClickListener")
                && body.contains("tabGrab.setOnClickListener"));
    }

    @Test
    public void historyDialog_deleteKeepsCurrentTab() throws Exception {
        String body = methodBody(mainActivity(), "private void renderHistoryList(LinearLayout list, HistoryStore store,");
        assertNotNull(body);
        assertTrue("删除必须用原数组索引（分 Tab 后仍删对那一条）",
                body.contains("store.deleteAt(idx)"));
        assertTrue("删除后必须原地刷新而不是重开对话框",
                body.contains("onChanged.run()"));
        assertFalse("删除后不得再调 showTranscriptHistory()（会丢当前 Tab）",
                body.contains("showTranscriptHistory()"));
    }

    // ── 底部按钮白色 ────────────────────────────

    @Test
    public void historyDialog_bottomButtonsAreWhite() throws Exception {
        String body = methodBody(mainActivity(), "private void showTranscriptHistory()");
        assertNotNull(body);
        assertTrue("必须拿到确定按钮并设为白色",
                body.contains("AlertDialog.BUTTON_POSITIVE")
                        && body.contains("android.graphics.Color.WHITE"));
        assertTrue("必须拿到清空全部按钮并设为白色",
                body.contains("AlertDialog.BUTTON_NEUTRAL"));
    }

    // ── 写入侧：取词走类型化接口 ─────────────────

    @Test
    public void captureService_writesGrabWithTypeNotPrefixHack() throws Exception {
        String src = stripComments(readFile("src/main/java/com/rd/englishcoach/CaptureService.java"));
        assertTrue("取词历史必须走 appendGrab（类型化）", src.contains("history.appendGrab("));
        assertFalse("不得再把「[取词] 」前缀塞进 transcript 来区分类型",
                src.contains("\"[取词] \""));
    }

    @Test
    public void stringsXml_hasHistoryTabLabels() throws Exception {
        String xml = readFile("src/main/res/values/strings.xml");
        assertTrue(xml.contains("<string name=\"history_tab_transcript\">"));
        assertTrue(xml.contains("<string name=\"history_tab_grab\">"));
    }
}
