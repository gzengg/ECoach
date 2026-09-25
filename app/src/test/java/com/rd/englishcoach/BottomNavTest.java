package com.rd.englishcoach;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * 底部导航栏契约测试（四 Tab 自绘组件，不引 Material 库）。
 *
 * <p>热区硬约束：每 Tab 等分屏宽（weight=1，≥90dp）x 56dp 高，≥ 44px；
 * 角标随上下文联动（监听中 / 历史新记录 / 识别模型缺失）。</p>
 */
public class BottomNavTest {

    private static Document parse(String path) throws Exception {
        InputStream is = BottomNavTest.class.getResourceAsStream(path);
        assertNotNull("classpath 上找不到: " + path, is);
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(is);
        is.close();
        return doc;
    }

    private static List<Element> allElements(Document doc) {
        List<Element> out = new ArrayList<>();
        NodeList all = doc.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) out.add((Element) all.item(i));
        return out;
    }

    private static Element byId(Document doc, String id) {
        for (Element e : allElements(doc)) {
            String v = e.getAttribute("android:id");
            if (v.equals("@+id/" + id) || v.equals("@id/" + id)) return e;
        }
        return null;
    }

    // ── 4 个 Tab ──────────────────────────────

    @Test
    public void bottomBar_hasFourTabs() throws Exception {
        Document doc = parse("/layout/view_bottom_bar.xml");
        String[] tabs = {"tabListen", "tabHistory", "tabModels", "tabSettings"};
        for (String id : tabs) {
            Element tab = byId(doc, id);
            assertNotNull("底部导航必须有 " + id, tab);
            assertEquals(id + " 必须等分屏宽（weight=1）", "1", attr(tab, "layout_weight"));
            assertTrue(id + " 热区高度必须 ≥48dp（实际 " + attr(tab, "layout_height") + "）",
                    dp(attr(tab, "layout_height")) >= 48);
            assertEquals(id + " 必须可点", "true", attr(tab, "clickable"));
        }
    }

    @Test
    public void bottomBar_labelsFromStrings() throws Exception {
        Document bar = parse("/layout/view_bottom_bar.xml");
        String strings = readFile("src/main/res/values/strings.xml");
        for (String[] pair : new String[][]{
                {"tabListen", "tab_listen"}, {"tabHistory", "tab_history"},
                {"tabModels", "tab_models"}, {"tabSettings", "tab_settings"}}) {
            Element tab = byId(bar, pair[0]);
            assertNotNull(tab);
            Element label = firstTextChild(tab);
            assertNotNull(pair[0] + " 里必须有文字标签", label);
            assertEquals(pair[0] + " 文案必须引用 @" + pair[1],
                    "@string/" + pair[1], attr(label, "text"));
            assertTrue("strings.xml 必须定义 " + pair[1],
                    strings.contains("<string name=\"" + pair[1] + "\">"));
        }
    }

    @Test
    public void bottomBar_hasBadgesForDynamicContext() throws Exception {
        Document doc = parse("/layout/view_bottom_bar.xml");
        assertNotNull("监听 Tab 要有角标（监听中呼吸点）", byId(doc, "badgeListen"));
        assertNotNull("历史 Tab 要有角标（新记录）", byId(doc, "badgeHistory"));
        assertNotNull("模型 Tab 要有角标（识别模型缺失 warn 点）", byId(doc, "badgeModels"));
    }

    @Test
    public void bottomBar_usesNoExternalLib() throws Exception {
        String src = readFile("src/main/java/com/rd/englishcoach/BottomBar.java");
        assertFalse("依赖白名单：不得 import Material/AndroidX",
                src.contains("com.google.android.material") || src.contains("androidx."));
        assertFalse("不得使用 FLAG_LAYOUT_NO_LIMITS", src.contains("FLAG_LAYOUT_NO_LIMITS"));
    }

    // ── 容器接线 ──────────────────────────────

    @Test
    public void mainActivity_switchesFourPagesAndSyncsBadges() throws Exception {
        String src = readFile("src/main/java/com/rd/englishcoach/MainActivity.java");
        for (String page : new String[]{"pageListen", "pageHistory", "pageModels", "pageSettings"}) {
            assertTrue("switchTab 必须切换 " + page, src.contains(page));
        }
        assertTrue("必须注册历史变更广播（历史 Tab 角标）",
                src.contains("ACTION_HISTORY_CHANGED"));
        assertTrue("必须联动模型缺失角标", src.contains("asrOfflineMissing"));
        assertTrue("角标联动必须走 syncBadges", src.contains("syncBadges"));
    }

    // ── 工具 ──────────────────────────────

    private static String attr(Element e, String name) {
        return e.getAttribute("android:" + name);
    }

    private static double dp(String v) {
        if (v == null || !v.endsWith("dp")) return 0;
        return Double.parseDouble(v.replace("dp", "").trim());
    }

    /** Tab（FrameLayout）里第一个 TextView 子节点 = 文字标签。 */
    private static Element firstTextChild(Element tab) {
        NodeList kids = tab.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE
                    && "TextView".equals(n.getNodeName())) {
                return (Element) n;
            }
        }
        return null;
    }

    private static String readFile(String path) throws Exception {
        java.io.File f = new java.io.File(path);
        assertTrue("文件不存在: " + f.getAbsolutePath(), f.exists());
        try (java.util.Scanner s = new java.util.Scanner(f, "UTF-8")) {
            s.useDelimiter("\\A");
            return s.hasNext() ? s.next() : "";
        }
    }
}
