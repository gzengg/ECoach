package com.rd.englishcoach;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;

import static org.junit.Assert.*;

/**
 * 主界面 + 悬浮窗布局回归测试。
 *
 * <p>Android 16 + targetSdk 35 强制 edge-to-edge，
 * 根 ScrollView 没有 fitsSystemWindows=true 时内容会画到状态栏后面。</p>
 */
public class MainLayoutTest {

    /** 主界面状态栏信息框不能被状态栏裁掉。 */
    @Test
    public void mainActivity_scrollView_fitsSystemWindows() throws Exception {
        Element sv = parseLayout("/layout/activity_main.xml").getDocumentElement();
        assertEquals("主界面 ScrollView 必须 fitsSystemWindows=true，"
                        + "否则 Android 16 上状态信息框会被状态栏裁掉",
                "true", attr(sv, "fitsSystemWindows"));
    }

    /** tvStatus 必须存在（用户看状态的唯一出口）。 */
    @Test
    public void tvStatus_exists() throws Exception {
        Element el = elementById(parseLayout("/layout/activity_main.xml"), "tvStatus");
        assertNotNull("tvStatus 必须存在", el);
    }

    /** 悬浮窗根布局不能用 FLAG_LAYOUT_NO_LIMITS（会超出屏幕边界）。 */
    @Test
    public void floatingPanel_noLayoutNoLimits() throws Exception {
        String src = readFile("src/main/java/com/rd/englishcoach/FloatingPanel.java");
        assertFalse("FloatingPanel 不应使用 FLAG_LAYOUT_NO_LIMITS",
                src.contains("FLAG_LAYOUT_NO_LIMITS"));
    }

    // ── 工具 ──────────────────────────────

    private static Element elementById(Document doc, String id) {
        org.w3c.dom.NodeList all = doc.getElementsByTagName("TextView");
        for (int i = 0; i < all.getLength(); i++) {
            Element e = (Element) all.item(i);
            String v = e.getAttribute("android:id");
            if (v.equals(id) || v.equals("@+id/" + id)) return e;
        }
        return null;
    }

    private static String attr(Element e, String name) {
        return e.getAttribute("android:" + name);
    }

    private static Document parseLayout(String path) throws Exception {
        InputStream is = MainLayoutTest.class.getResourceAsStream(path);
        assertNotNull("classpath 上找不到: " + path, is);
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(is);
        is.close();
        return doc;
    }

    private static String readFile(String path) throws Exception {
        java.io.File f = new java.io.File(path);
        StringBuilder sb = new StringBuilder();
        try (java.util.Scanner sc = new java.util.Scanner(f, "UTF-8")) {
            while (sc.hasNextLine()) sb.append(sc.nextLine()).append('\n');
        }
        return sb.toString();
    }
}
