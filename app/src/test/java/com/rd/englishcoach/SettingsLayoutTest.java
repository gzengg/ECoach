package com.rd.englishcoach;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * 设置页布局契约测试。
 *
 * <p>回归的 bug：旧版每个输入框上方单独占一行标签，整页高达 ~746dp，
 * 而用户机器（ColorOS / Android 16，1080x2376 @3x = 360x792dp）扣掉状态栏、
 * 标题栏、导航栏后可用视口只有 ~653dp。结果必须滚动；滚到底时最上面那行
 * 输入框被切成只剩 10px 高的一条（看起来像乱码），用户报「设置界面显示不完全」。</p>
 *
 * <p>修法：标签改到左侧（不再各占一行），整页压到 ~575dp，一屏放得下。
 * 本测试用「按 dp 累加」的方式把总高度算出来并卡上限——没有真机也能守住这条线。</p>
 */
public class SettingsLayoutTest {

    /**
     * 可用视口预算。360x792dp 机型：792 - 37(状态栏) - 56(标题栏) - 46(导航栏) ≈ 653dp，
     * 留 ~13dp 余量取 640dp。
     */
    private static final int VIEWPORT_BUDGET_DP = 640;

    // ── 核心：一屏放得下 ──────────────────────

    @Test
    public void contentFitsOnOneScreen() throws Exception {
        double total = contentHeightDp();
        System.out.println("[SettingsLayoutTest] 设置页估算总高 = "
                + String.format("%.1f", total) + "dp / 预算 " + VIEWPORT_BUDGET_DP + "dp");
        assertTrue(String.format(
                "设置页内容高 %.0fdp，超过可用视口 %ddp —— 会出现必须滚动、"
                        + "顶部输入框被切成半截（看起来像乱码）的老问题。"
                        + "加行/加间距请同步压缩别处。",
                total, VIEWPORT_BUDGET_DP),
                total <= VIEWPORT_BUDGET_DP);
    }

    @Test
    public void heightEstimate_isSane() throws Exception {
        double total = contentHeightDp();
        assertTrue("高度估算异常（" + total + "dp），估算器可能坏了", total > 300);
    }

    // ── 打开设置页不能自己滚走 / 弹键盘 ────────

    @Test
    public void root_takesInitialFocus() throws Exception {
        Element root = contentContainer();
        assertEquals("根布局必须 focusableInTouchMode=true，否则第一个输入框会抢到焦点，"
                        + "打开设置页时键盘弹出、页面自己滚到中间",
                "true", attr(root, "focusableInTouchMode"));
    }

    /** Android 16 + targetSdk 35 强制 edge-to-edge，内容会画到状态栏后面。 */
    @Test
    public void scrollView_fitsSystemWindows() throws Exception {
        Element sv = parseLayout().getDocumentElement();
        assertEquals("ScrollView 必须 fitsSystemWindows=true，否则 Android 16 上 "
                        + "Base URL / API Key 会被状态栏裁掉（用户报「显示不全」）",
                "true", attr(sv, "fitsSystemWindows"));
    }

    // ── 标签在左（不再各占一行） ───────────────

    @Test
    public void everyField_hasSideLabel() throws Exception {
        for (String id : new String[]{"etBaseUrl", "etApiKey", "etAsrModel", "etChatModel"}) {
            Element field = elementById(id);
            assertNotNull("找不到 " + id, field);
            Element row = (Element) field.getParentNode();
            assertEquals(id + " 必须和标签并排放在横向 LinearLayout 里（标签不能独占一行）",
                    "horizontal", attr(row, "orientation"));
            assertNotNull(id + " 同一行里必须有带 labelFor 的标签", labelFor(row, id));
        }
    }

    @Test
    public void promptField_hasOwnLabelRow() throws Exception {
        Element prompt = elementById("etSysPrompt");
        assertNotNull(prompt);
        Element label = elementById("etSysPromptLabel");
        assertNotNull("系统提示词需要一个独立标签（文字太长，放不进左侧栏）", label);
        assertEquals("标签要指向 etSysPrompt", "@id/etSysPrompt", attr(label, "labelFor"));
    }

    // ── API Key 可见性开关（用户把一排圆点当成了乱码） ──

    @Test
    public void apiKey_startsMasked_withToggle() throws Exception {
        Element key = elementById("etApiKey");
        assertEquals("API Key 默认必须打码", "textPassword", attr(key, "inputType"));

        Element toggle = elementById("btnToggleKey");
        assertNotNull("API Key 旁必须有「显示/隐藏」开关", toggle);
        assertEquals("开关初始文案应为「显示」", "@string/set_show_key", attr(toggle, "text"));
    }

    // ── 不要用已废弃属性 / 输入框要能滚动 ──────

    @Test
    public void noDeprecatedSingleLine() throws Exception {
        List<Element> all = new ArrayList<>();
        collect(parseLayout().getDocumentElement(), all);
        for (Element e : all) {
            assertTrue(e.getTagName() + " 不应再使用已废弃的 android:singleLine，"
                            + "改用 inputType 控制单行",
                    attr(e, "singleLine").isEmpty());
        }
    }

    @Test
    public void promptField_isScrollableMultiline() throws Exception {
        Element prompt = elementById("etSysPrompt");
        assertTrue("提示词框必须是多行", attr(prompt, "inputType").contains("textMultiLine"));
        assertEquals("多行框要有滚动条，否则看不出内容还没显示完", "vertical", attr(prompt, "scrollbars"));
    }

    // ── 高度估算 ──────────────────────────────

    private static double contentHeightDp() throws Exception {
        Map<String, Map<String, String>> styles = parseStyles();
        Element root = contentContainer();
        double total = padding(root, styles, "paddingVertical") * 2
                + padding(root, styles, "paddingTop")
                + padding(root, styles, "paddingBottom");
        for (Element child : childElements(root)) {
            if (isGone(child)) continue;
            total += marginTop(child, styles) + extentDp(child, styles);
        }
        return total;
    }

    /** 隐藏的 Tab 不参与高度估算（v4 起设置页与模型合并成两个 Tab，同时只显示一个）。 */
    private static boolean isGone(Element e) {
        return "gone".equals(e.getAttribute("android:visibility"));
    }

    /** 一个控件（或一行横向容器）占用的垂直高度。 */
    private static double extentDp(Element e, Map<String, Map<String, String>> styles) {
        if ("LinearLayout".equals(e.getTagName())
                && "horizontal".equals(attr(e, styles, "orientation"))) {
            double max = 0;
            for (Element c : childElements(e)) max = Math.max(max, extentDp(c, styles));
            return max;
        }
        // v2.0：纵向 LinearLayout（卡片容器）→ 递归累加子元素高度 + 内边距
        if ("LinearLayout".equals(e.getTagName())
                && "vertical".equals(attr(e, styles, "orientation"))) {
            double total = 0;
            for (Element c : childElements(e)) {
                if (isGone(c)) continue;
                total += marginTop(c, styles) + extentDp(c, styles);
            }
            total += padding(e, styles, "paddingTop") + padding(e, styles, "paddingBottom");
            return total;
        }
        String h = attr(e, styles, "layout_height");
        if (h.endsWith("dp")) return parseDp(h);

        double base = "Button".equals(e.getTagName())
                ? 48                                   // 框架 Button 默认 minHeight
                : textHeightDp(attr(e, styles, "textSize"));
        String minH = attr(e, styles, "minHeight");
        if (minH.endsWith("dp")) base = Math.max(base, parseDp(minH));
        return base;
    }

    /** TextView/EditText 的 wrap_content 高度：行高 + 上下 fontPadding。 */
    private static double textHeightDp(String textSize) {
        if (!textSize.endsWith("sp")) return 24;
        return Double.parseDouble(textSize.replace("sp", "").trim()) * 1.2 + 8;
    }

    private static double marginTop(Element e, Map<String, Map<String, String>> styles) {
        String v = attr(e, styles, "layout_marginTop");
        return v.endsWith("dp") ? parseDp(v) : 0;
    }

    private static double padding(Element e, Map<String, Map<String, String>> styles, String name) {
        String v = attr(e, styles, name);
        return v.endsWith("dp") ? parseDp(v) : 0;
    }

    private static double parseDp(String v) {
        return Double.parseDouble(v.replace("dp", "").trim());
    }

    // ── 样式解析（style="@style/X" → 属性） ────

    private static Map<String, Map<String, String>> parseStyles() throws Exception {
        Document doc = parseResource("/values/styles.xml");
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        NodeList all = doc.getElementsByTagName("style");
        for (int i = 0; i < all.getLength(); i++) {
            Element style = (Element) all.item(i);
            Map<String, String> attrs = new LinkedHashMap<>();
            NodeList items = style.getElementsByTagName("item");
            for (int j = 0; j < items.getLength(); j++) {
                Element item = (Element) items.item(j);
                attrs.put(item.getAttribute("name"), item.getTextContent().trim());
            }
            out.put(style.getAttribute("name"), attrs);
        }
        return out;
    }

    /** 取属性：元素自身优先，其次 style 引用的样式。 */
    private static String attr(Element e, String name) throws Exception {
        return attr(e, parseStyles(), name);
    }

    private static String attr(Element e, Map<String, Map<String, String>> styles, String name) {
        String own = e.getAttribute("android:" + name);
        if (own != null && !own.isEmpty()) return own;
        String ref = e.getAttribute("style");
        if (ref.startsWith("@style/")) {
            Map<String, String> style = styles.get(ref.substring("@style/".length()));
            if (style != null) {
                String v = style.get("android:" + name);
                if (v != null && !v.isEmpty()) return v;
            }
        }
        return "";
    }

    // ── DOM 工具 ─────────────────────────────

    private static Document parseLayout() throws Exception {
        return parseResource("/layout/activity_settings.xml");
    }

    /** ScrollView 里那个纵向 LinearLayout。 */
    private static Element contentContainer() throws Exception {
        NodeList all = parseLayout().getElementsByTagName("LinearLayout");
        for (int i = 0; i < all.getLength(); i++) {
            Element e = (Element) all.item(i);
            if ("vertical".equals(e.getAttribute("android:orientation"))) return e;
        }
        fail("activity_settings.xml 里找不到纵向 LinearLayout");
        return null;
    }

    private static Element elementById(String id) throws Exception {
        List<Element> all = new ArrayList<>();
        collect(parseLayout().getDocumentElement(), all);
        for (Element e : all) {
            String v = e.getAttribute("android:id");
            if (v.equals(id) || v.equals("@+id/" + id) || v.equals("@id/" + id)) return e;
        }
        return null;
    }

    /** 在 row 里找 labelFor 指向 id 的标签。 */
    private static Element labelFor(Element row, String id) {
        for (Element c : childElements(row)) {
            String v = c.getAttribute("android:labelFor");
            if (v.equals("@id/" + id) || v.equals("@+id/" + id)) return c;
        }
        return null;
    }

    private static void collect(Element e, List<Element> out) {
        out.add(e);
        for (Element c : childElements(e)) collect(c, out);
    }

    private static List<Element> childElements(Element parent) {
        List<Element> out = new ArrayList<>();
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE) out.add((Element) n);
        }
        return out;
    }

    /** 读源文件原文（契约断言用，与 ListeningKeyGuardTest 的 readFile 同款）。 */
    private static String readFile(String path) throws Exception {
        java.io.File f = new java.io.File(path);
        assertTrue("文件不存在: " + f.getAbsolutePath(), f.exists());
        try (java.util.Scanner sc = new java.util.Scanner(f, "UTF-8")) {
            sc.useDelimiter("\\A");
            return sc.hasNext() ? sc.next() : "";
        }
    }

    private static Document parseResource(String path) throws Exception {
        InputStream is = SettingsLayoutTest.class.getResourceAsStream(path);
        assertNotNull("classpath 上找不到资源: " + path, is);
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(false);
        Document doc = f.newDocumentBuilder().parse(is);
        is.close();
        return doc;
    }

    // ── 选择芯片布局（回归：芯片被挤压导致文字换行） ──────────

    @Test
    public void pickerChips_useWeightlessStyle() throws Exception {
        // 回归：ModelChip 带 layout_weight=1，且**子视图里覆盖不掉**，
        // 多个芯片会被等分挤压、文字换行（真机截图：中文音色 5 个时「白桦」被压成两行）。
        String chip = readFile("src/main/res/layout/item_chip.xml");
        assertTrue("选择芯片必须用 PickChip（wrap_content、无 weight）",
                chip.contains("@style/PickChip"));
        assertFalse("不得复用带 weight 的 ModelChip", chip.contains("@style/ModelChip"));

        String styles = readFile("src/main/res/values/styles.xml");
        int pick = styles.indexOf("name=\"PickChip\"");
        assertTrue("styles.xml 必须有 PickChip", pick >= 0);
        String pickBlock = styles.substring(pick, Math.min(styles.length(), pick + 600));
        assertTrue("PickChip 宽度必须是 wrap_content", pickBlock.contains("wrap_content"));
        assertTrue("PickChip 不得带 weight", pickBlock.contains("<item name=\"android:layout_weight\">0</item>"));
    }

    @Test
    public void pickerRows_areHorizontallyScrollable() throws Exception {
        // 芯片多了要能横向滚动，而不是被挤压换行
        String layout = readFile("src/main/res/layout/activity_settings.xml");
        int count = 0, idx = 0;
        while ((idx = layout.indexOf("HorizontalScrollView", idx + 1)) > 0) count++;
        assertEquals("三个选择行（识别模型 / 英文音色 / 中文音色）都应在 HorizontalScrollView 里"
                + "（开+闭共 6 处）", 6, count);
    }

}
