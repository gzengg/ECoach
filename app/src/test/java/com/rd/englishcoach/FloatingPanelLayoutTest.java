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
    public void grabButton_label_differsFromTabLabel() throws Exception {
        // 同类问题排查：同一屏上 tab「取词」与按钮「取词」同名会分不清（一个是切页、一个是动作）
        Element btn = elementById("btnGrabStart");
        Element tab = elementById("btnTabGrab");
        assertNotNull(btn);
        assertNotNull(tab);
        assertFalse("取词页的按钮不能与 tab 同名（用户分不清哪个是切页、哪个是动作）",
                btn.getAttribute("android:text").equals(tab.getAttribute("android:text")));
    }

    @Test
    public void btnSendQuestion_exists() throws Exception {
        Element el = elementById("btnSendQuestion");
        assertNotNull("btnSendQuestion 必须存在", el);
    }

    // ── 主按钮配色必须与面板其他控件同色系 ──

    // ── 主按钮配色：v4.1 玻璃卡片化改与应用内 CTA 同款渐变胶囊 ──

    @Test
    public void primaryButtons_useCtaStyle() throws Exception {
        // v4.1 重构：主按钮从 glass 弱对比改为 pill_primary 渐变胶囊（与应用内主 CTA 同款，
        // 对比度 10.5:1），原「与动作芯片同色」的旧设计作废。
        assertEquals("听力页主按钮文字色应为 accent_on（渐变胶囊上的深色文字）",
                "@color/accent_on", elementById("btnPause").getAttribute("android:textColor"));
        assertEquals("取词页主按钮文字色应与听力页一致",
                "@color/accent_on", elementById("btnGrabStart").getAttribute("android:textColor"));
        assertEquals("主按钮应使用与应用内 CTA 同款渐变胶囊",
                "@drawable/pill_primary", elementById("btnPause").getAttribute("android:background"));
    }

    @Test
    public void tabBar_isSegmentedControl() throws Exception {
        // 方案 B：分段控件 = segment_track 底槽 + 每半区一个 pill_glass 滑块（代码切可见性）
        String xml = readResourceText("/layout/window_panel.xml");
        assertTrue("Tab 栏必须用分段底槽 segment_track",
                xml.contains("@drawable/segment_track"));
        assertTrue("必须有滑块 tabThumbListen / tabThumbGrab",
                xml.contains("@+id/tabThumbListen") && xml.contains("@+id/tabThumbGrab"));
        assertTrue("滑块用 pill_glass（既有 token）", xml.contains("@drawable/pill_glass"));
    }

    @Test
    public void pillGlass_matchesPanelControlColors() throws Exception {
        String pill = readResourceText("/drawable/pill_glass.xml");
        String colors = readResourceText("/values/colors.xml");
        String chip = readResourceText("/drawable/bg_chip.xml");

        assertTrue("主按钮底色必须用 accent_glass token（不散落裸十六进制）",
                pill.contains("@color/accent_glass"));
        assertTrue("主按钮描边必须用面板描边 token（与 bg_chip 同族）",
                pill.contains("@color/stroke_strong") || pill.contains("@color/stroke_soft"));
        assertTrue("bg_chip 用的也是同一族描边 token", chip.contains("@color/stroke_soft"));
        assertFalse("不得再有白色高光渐变（半透明叠在深色玻璃上会发灰，用户反馈不适配）",
                pill.contains("#59FFFFFF"));
        assertTrue("必须保留胶囊圆角", pill.contains("@dimen/radius_pill"));

        assertTrue("colors.xml 必须定义 accent_glass",
                colors.contains("name=\"accent_glass\""));
        String glass = extractColor(colors, "accent_glass");
        String solid = extractColor(colors, "accent_solid");
        assertNotNull(glass);
        assertNotNull(solid);
        assertEquals("accent_glass 的 RGB 必须与 accent_solid 一致（同色系）",
                solid.substring(3), glass.substring(3));
        int alpha = Integer.parseInt(glass.substring(1, 3), 16);
        assertTrue("主按钮必须是半透明的（alpha < 50%），实际 alpha=" + alpha, alpha < 0x80);
        assertTrue("但不能全透明到看不见，alpha=" + alpha, alpha >= 0x10);
    }

    private static String extractColor(String colorsXml, String name) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<color name=\"" + name + "\">(#([0-9A-Fa-f]{6,8}))</color>")
                .matcher(colorsXml);
        return m.find() ? m.group(1) : null;
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

    // ── 两个 Tab 的主按钮风格必须一致（用户反馈 UI 不统一） ──

    @Test
    public void bothTabs_primaryButtons_shareSameStyle() throws Exception {
        Element pause = elementById("btnPause");
        Element grab = elementById("btnGrabStart");
        assertNotNull("btnPause 必须存在", pause);
        assertNotNull("btnGrabStart 必须存在", grab);

        assertEquals("两个 Tab 的主按钮背景必须一致",
                grab.getAttribute("android:background"), pause.getAttribute("android:background"));
        assertEquals("两个 Tab 的主按钮高度必须一致",
                grab.getAttribute("android:layout_height"), pause.getAttribute("android:layout_height"));
        assertEquals("两个 Tab 的主按钮文字色必须一致",
                grab.getAttribute("android:textColor"), pause.getAttribute("android:textColor"));
        assertEquals("两个 Tab 的主按钮字号必须一致",
                grab.getAttribute("android:textSize"), pause.getAttribute("android:textSize"));
    }

    @Test
    public void bothTabs_primaryButtons_useCtaPill() throws Exception {
        // v4.1：两个 Tab 主按钮统一为应用内 CTA 同款渐变胶囊（pill_primary + accent_on）
        Element pause = elementById("btnPause");
        Element grab = elementById("btnGrabStart");
        assertEquals("听力页主按钮必须是渐变胶囊",
                "@drawable/pill_primary", pause.getAttribute("android:background"));
        assertEquals("取词页主按钮必须同一套样式",
                "@drawable/pill_primary", grab.getAttribute("android:background"));
        assertEquals("渐变胶囊上文字用深色 accent_on（对比度 10.5:1）",
                "@color/accent_on", pause.getAttribute("android:textColor"));
        assertEquals("两个主按钮必须同一高度 token",
                "@dimen/height_cta_sm", pause.getAttribute("android:layout_height"));
        assertEquals("取词页主按钮必须同一高度 token",
                "@dimen/height_cta_sm", grab.getAttribute("android:layout_height"));
    }

    @Test
    public void bothTabs_primaryButtons_shareOneContainer() throws Exception {
        // 必须用同一份 DOM（每次 parseLayout 都会新建树，节点对象不同）
        Document doc = parseLayout();
        Element pause = elementById(doc, "btnPause");
        Element grab = elementById(doc, "btnGrabStart");
        assertNotNull(pause);
        assertNotNull(grab);
        org.w3c.dom.Node pParent = pause.getParentNode();
        org.w3c.dom.Node gParent = grab.getParentNode();
        assertNotNull(pParent);
        assertSame("两个 Tab 的主按钮必须在同一容器里（结构上保证位置一致，"
                        + "此前「继续」被 levelBar 顶到了不同 Y）",
                pParent, gParent);
        assertEquals("共用容器必须是 primaryBar",
                "@+id/primaryBar", ((Element) pParent).getAttribute("android:id"));
        assertEquals("默认显示「继续」，取词按钮默认隐藏",
                "gone", grab.getAttribute("android:visibility"));
        assertFalse("默认不能隐藏「继续」",
                "gone".equals(pause.getAttribute("android:visibility")));
    }

    @Test
    public void levelBar_isShared_notInsideTabPages() throws Exception {
        // levelBar 之前在听力页内部 → 把「继续」往下顶，两个 Tab 主按钮位置不一致
        assertNotInsideTabPage("levelBar");
    }

    @Test
    public void panelCta_isCompact() throws Exception {
        String dimens = readResourceText("/values/dimens.xml");
        assertTrue("必须新增悬浮窗专用的小一号 CTA 高度 token",
                dimens.contains("name=\"height_cta_sm\""));
        assertTrue("主按钮要比主界面 CTA（52dp）小：44dp",
                dimens.contains("<dimen name=\"height_cta_sm\">44dp</dimen>"));
    }

    private static String readResourceText(String path) throws Exception {
        InputStream is = FloatingPanelLayoutTest.class.getResourceAsStream(path);
        assertNotNull("classpath 上找不到资源: " + path, is);
        java.util.Scanner s = new java.util.Scanner(is, "UTF-8");
        s.useDelimiter("\\A");
        String text = s.hasNext() ? s.next() : "";
        s.close();
        is.close();
        return text;
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
        return elementById(parseLayout(), id);
    }

    /** 按 id 找元素（搜索所有标签，含 ProgressBar / FrameLayout 等）。 */
    private static Element elementById(Document doc, String id) {
        NodeList all = doc.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Element e = (Element) all.item(i);
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
