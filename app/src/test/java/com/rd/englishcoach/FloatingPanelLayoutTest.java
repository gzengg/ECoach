package com.rd.englishcoach;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;

import static org.junit.Assert.*;

/**
 * 悬浮窗面板布局契约测试（问题1：输入法收起后输入框自动消失）：
 * - inputRow 初始 visibility=gone（默认隐藏）
 * - etQuestion 存在且可聚焦
 * - btnSendQuestion 存在
 */
public class FloatingPanelLayoutTest {

    // ── inputRow 初始隐藏 ────────────────────

    @Test
    public void inputRow_defaultHidden() throws Exception {
        Element el = elementById("inputRow");
        assertNotNull("inputRow 必须存在", el);
        String vis = el.getAttribute("android:visibility");
        assertEquals("inputRow 默认必须是 gone", "gone", vis);
    }

    // ── etQuestion 存在 ────────────────────

    @Test
    public void etQuestion_exists() throws Exception {
        Element el = elementById("etQuestion");
        assertNotNull("etQuestion 必须存在", el);
    }

    @Test
    public void etQuestion_isFocusable() throws Exception {
        Element el = elementById("etQuestion");
        assertNotNull(el);
        // EditText 默认可聚焦，检查 inputType 存在即可
        String inputType = el.getAttribute("android:inputType");
        assertNotNull("etQuestion 必须有 inputType", inputType);
        assertTrue("etQuestion inputType 不能为空", !inputType.isEmpty());
    }

    // ── btnSendQuestion 存在 ────────────────

    @Test
    public void btnSendQuestion_exists() throws Exception {
        Element el = elementById("btnSendQuestion");
        assertNotNull("btnSendQuestion 必须存在", el);
    }

    // ── 关闭按钮存在 ────────────────────────

    @Test
    public void btnClose_exists() throws Exception {
        Element el = elementById("btnClose");
        assertNotNull("btnClose 必须存在（用于关闭面板）", el);
    }

    // ── 公共提示区：两个 Tab 都要能看到 ──────

    @Test
    public void tvMessage_visibleFromBothTabs() throws Exception {
        assertNotInsideTabPage("tvMessage");
    }

    @Test
    public void btnReconsent_visibleFromBothTabs() throws Exception {
        assertNotInsideTabPage("btnReconsent");
    }

    @Test
    public void grabTab_hasGrabButtonAndStatus() throws Exception {
        Element btn = elementById("btnGrabStart");
        assertNotNull("btnGrabStart 必须存在", btn);
        assertNotNull("tvGrabStatus 必须存在（取词页自己的状态行）", elementById("tvGrabStatus"));
        // 取词页自身可显示状态 → 取词失败时在「取词」页也能看到
        assertTrue("tvGrabStatus 应默认隐藏，有内容才显示",
                "gone".equals(elementById("tvGrabStatus").getAttribute("android:visibility")));
    }

    /**
     * 断言元素不在 tabListening / tabGrab 内部：
     * 之前 tvMessage / btnReconsent 放在听力页里，切到取词页后任何失败提示都看不到，
     * 用户表现就是「点取词完全没反应」。
     */
    private static void assertNotInsideTabPage(String id) throws Exception {
        Element el = elementById(id);
        assertNotNull(id + " 必须存在", el);
        org.w3c.dom.Node n = el.getParentNode();
        while (n != null) {
            if (n instanceof Element) {
                String a = ((Element) n).getAttribute("android:id");
                assertFalse(id + " 不能放在 tab 页内部（否则切到另一页就看不到提示）：" + a,
                        a.equals("@+id/tabListening") || a.equals("@+id/tabGrab"));
            }
            n = n.getParentNode();
        }
    }

    // ── 解析实现 ──────────────────────────────

    private static Document parseLayout() throws Exception {
        return parseResource("/layout/window_panel.xml");
    }

    private static Element elementById(String id) throws Exception {
        Document doc = parseLayout();
        NodeList all = doc.getElementsByTagName("LinearLayout");
        for (int i = 0; i < all.getLength(); i++) {
            Element e = (Element) all.item(i);
            String attr = e.getAttribute("android:id");
            if (attr.equals(id) || attr.equals("@+id/" + id) || attr.equals("@id/" + id)) return e;
        }
        // Also check other element types
        NodeList texts = doc.getElementsByTagName("TextView");
        for (int i = 0; i < texts.getLength(); i++) {
            Element e = (Element) texts.item(i);
            String attr = e.getAttribute("android:id");
            if (attr.equals(id) || attr.equals("@+id/" + id) || attr.equals("@id/" + id)) return e;
        }
        NodeList edits = doc.getElementsByTagName("EditText");
        for (int i = 0; i < edits.getLength(); i++) {
            Element e = (Element) edits.item(i);
            String attr = e.getAttribute("android:id");
            if (attr.equals(id) || attr.equals("@+id/" + id) || attr.equals("@id/" + id)) return e;
        }
        return null;
    }

    private static Document parseResource(String path) throws Exception {
        InputStream is = FloatingPanelLayoutTest.class.getResourceAsStream(path);
        assertNotNull("classpath 上找不到资源: " + path, is);
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(false);
        Document doc = f.newDocumentBuilder().parse(is);
        is.close();
        return doc;
    }
}
