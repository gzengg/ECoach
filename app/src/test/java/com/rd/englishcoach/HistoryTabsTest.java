package com.rd.englishcoach;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.Scanner;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;

/**
 * 「历史页」契约测试（原「转录历史」对话框迁到四 Tab 的独立页面）。
 *
 * <p>需求不变：取词记录与听力转录<b>分成两个 Tab</b>展示（不混在一个列表里）；
 * 新增：搜索过滤、两种空状态（首启 / 搜索无结果）、点条目看全文（Bottom Sheet）、
 * 长按操作菜单、清空全部二次确认。</p>
 *
 * <p>HistoryPage 依赖 Android 框架，无法在 JVM 上实例化，故用源码契约 +
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

    private String historyPage() throws Exception {
        return stripComments(readFile("src/main/java/com/rd/englishcoach/HistoryPage.java"));
    }

    private static Element elementById(Document doc, String id) {
        org.w3c.dom.NodeList all = doc.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Element e = (Element) all.item(i);
            String v = e.getAttribute("android:id");
            if (v.equals("@+id/" + id) || v.equals("@id/" + id)) return e;
        }
        return null;
    }

    private static Document parseMainLayout() throws Exception {
        InputStream is = HistoryTabsTest.class.getResourceAsStream("/layout/activity_main.xml");
        assertNotNull("classpath 上找不到 activity_main.xml", is);
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(is);
        is.close();
        return doc;
    }

    // ── 两个类型 Tab ────────────────────────────

    @Test
    public void historyPage_hasTwoTabs() throws Exception {
        String src = historyPage();
        assertTrue("必须有「转录」Tab", src.contains("history_tab_transcript"));
        assertTrue("必须有「取词」Tab", src.contains("history_tab_grab"));
        assertTrue("必须用 countByType 给 Tab 显示条数",
                src.contains("HistoryStore.countByType"));
    }

    @Test
    public void historyPage_layoutHasTypeChipsAndList() throws Exception {
        Document doc = parseMainLayout();
        for (String id : new String[]{"pageHistory", "etHistorySearch",
                "btnHistTranscript", "btnHistGrab", "historyList", "historyEmpty",
                "tvEmptyTitle", "tvEmptyMsg", "btnEmptyAction"}) {
            assertNotNull("activity_main.xml 必须有 " + id, elementById(doc, id));
        }
    }

    @Test
    public void historyPage_filtersListByType() throws Exception {
        String src = historyPage();
        assertTrue("列表必须按类型过滤（取词与转录分开）",
                src.contains("e.isGrab() != showGrab"));
        assertTrue("取词条目按「原文 → 译文」展示",
                src.contains("\" → \""));
    }

    @Test
    public void historyPage_tabSwitchReRendersInPlace() throws Exception {
        String src = historyPage();
        assertTrue("切 Tab 必须原地重渲染（不重建页面）",
                src.contains("showGrab = false; render()") && src.contains("showGrab = true; render()"));
    }

    @Test
    public void historyPage_searchFilters() throws Exception {
        String src = historyPage();
        assertTrue("搜索框输入变化必须触发重渲染", src.contains("afterTextChanged"));
        assertTrue("搜索必须对原文与答案做包含匹配", src.contains("matches(e, q)"));
    }

    // ── 空状态 ─────────────────────────────────

    @Test
    public void historyPage_emptyStates_withGuide() throws Exception {
        String src = historyPage();
        assertTrue("首启空状态要有标题与引导文案",
                src.contains("history_empty_title") && src.contains("history_empty_guide"));
        assertTrue("首启空状态的引导 CTA 必须跳「监听」Tab",
                src.contains("history_empty_cta") && src.contains("switchTab(BottomBar.TAB_LISTEN)"));
        assertTrue("搜索无结果要回显搜索词并提供「清除搜索」",
                src.contains("history_search_empty_title") && src.contains("history_search_clear"));
    }

    // ── 删除与长按菜单 ──────────────────────────

    @Test
    public void historyPage_deleteKeepsCurrentTab() throws Exception {
        String src = historyPage();
        assertTrue("删除必须用原数组索引（分 Tab 后仍删对那一条）",
                src.contains("store.deleteAt(idx)"));
        assertTrue("删除后必须原地刷新（render()）", src.contains("render();"));
    }

    @Test
    public void historyPage_longPressMenu_speakCopyDelete() throws Exception {
        String src = historyPage();
        assertTrue("条目必须支持长按操作菜单", src.contains("setOnLongClickListener"));
        assertTrue("菜单要有 朗读/复制/删除",
                src.contains("menu_speak") && src.contains("menu_copy") && src.contains("menu_delete"));
        assertTrue("朗读必须先检查服务可用（canSpeak）", src.contains("act.canSpeak()"));
    }

    @Test
    public void historyPage_tapOpensDetailSheet() throws Exception {
        String src = historyPage();
        assertTrue("点条目必须用 Bottom Sheet 展示全文",
                src.contains("BottomSheetPanel") && src.contains("showDetail"));
    }

    @Test
    public void historyPage_clearAll_hasConfirmSheet() throws Exception {
        String src = historyPage();
        assertTrue("清空全部必须走二次确认（Bottom Sheet）",
                src.contains("confirmClearAll") && src.contains("history_clear_confirm"));
        assertTrue("清空后要有状态反馈", src.contains("status_history_cleared"));
    }

    // ── 顶栏入口 ────────────────────────────────

    @Test
    public void mainActivity_topAction_clearAllOnHistoryTab() throws Exception {
        String src = stripComments(readFile("src/main/java/com/rd/englishcoach/MainActivity.java"));
        String body = methodBody(src, "btnTopAction.setOnClickListener");
        assertNotNull("顶栏动作必须绑定点击", body);
        assertTrue("历史页顶栏动作 = 清空全部（confirmClearAll）",
                body.contains("historyPage.confirmClearAll()"));
        assertTrue("模型页顶栏动作 = 下载源（showSourceSheet）",
                body.contains("modelsPage.showSourceSheet()"));
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
        assertTrue(xml.contains("<string name=\"history_search_hint\">"));
        assertTrue(xml.contains("<string name=\"history_empty_cta\">"));
        assertTrue(xml.contains("<string name=\"history_search_clear\">"));
    }
}
