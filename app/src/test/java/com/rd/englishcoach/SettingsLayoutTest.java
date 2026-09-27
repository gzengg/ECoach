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
 * 设置 / 模型页布局契约测试（v4.1 迁入 activity_main.xml 四 Tab 容器后重定义）。
 *
 * <p>历史回归：旧版每个输入框上方单独占一行标签，整页高达 ~746dp，而用户机器
 * （ColorOS / Android 16，360x792dp）可用视口只有 ~653dp，必须滚动；滚到底时最上面
 * 那行被切成半截（看起来像乱码）。</p>
 *
 * <p>v4.1 决策：四 Tab 后设置页允许整页滚动，但约束改为——
 * <b>每个卡片分组（card_surface）内部高度必须 ≤ 可用视口</b>：任何一组在一屏内
 * 都能完整看到，滚动只发生在组与组之间，不会再出现「半截行」的乱码观感。</p>
 */
public class SettingsLayoutTest {

    /** 可用视口预算。360x792dp 机型：792 - 37(状态栏) - 56(标题栏) - 46(导航栏) ≈ 653dp。 */
    private static final int VIEWPORT_BUDGET_DP = 640;

    // ── 核心：每个分组一屏放得下 ──────────────

    @Test
    public void everyGroupFitsOnOneScreen() throws Exception {
        List<Element> groups = cardGroups();
        assertTrue("activity_main.xml 里应至少有设置/模型的分组卡片（设置 4 组 + 模型 1 组）",
                groups.size() >= 5);
        for (Element group : groups) {
            double h = groupHeightDp(group);
            assertTrue(String.format("分组（paddingTop=%s）内容高 %.0fdp，超过可用视口 %ddp —— "
                            + "组内会出现滚动/半截行。加行/加间距请拆组或压缩别处。",
                    group.getAttribute("android:paddingTop"), h, VIEWPORT_BUDGET_DP),
                    h <= VIEWPORT_BUDGET_DP);
            assertTrue("分组高度估算异常（" + h + "dp），估算器可能坏了", h > 16);
        }
    }

    // ── 四 Tab 容器骨架 ────────────────────────

    @Test
    public void shell_hasFourPagesAndBottomBar() throws Exception {
        for (String id : new String[]{"pageListen", "pageHistory", "pageModels",
                "pageSettings", "bottomBar", "topBar", "tvTopTitle", "btnTopAction"}) {
            assertNotNull("activity_main.xml 必须有 " + id, elementById(id));
        }
    }

    /** Android 16 + targetSdk 35 强制 edge-to-edge，内容会画到状态栏后面。 */
    @Test
    public void root_fitsSystemWindows() throws Exception {
        Element root = parseLayout().getDocumentElement();
        assertEquals("根布局必须 fitsSystemWindows=true，否则 Android 16 上内容被状态栏裁掉",
                "true", attr(root, "fitsSystemWindows"));
    }

    // ── 打开设置页不能自己滚走 / 弹键盘 ────────

    @Test
    public void settingsRoot_takesInitialFocus() throws Exception {
        Element root = elementById("settingsRoot");
        assertNotNull("必须有 settingsRoot", root);
        assertEquals("settingsRoot 必须 focusableInTouchMode=true，否则第一个输入框会抢到焦点，"
                        + "打开设置页时键盘弹出、页面自己滚到中间",
                "true", attr(root, "focusableInTouchMode"));
    }

    // ── 字段与标签同行（标签不独占一行） ────────

    @Test
    public void everyField_hasSideLabel() throws Exception {
        for (String id : new String[]{"etBaseUrl", "etApiKey", "etChatModel",
                "etAsrBaseUrl", "etAsrKey", "etAsrModel",
                "etTtsBaseUrl", "etTtsKey", "etTtsModel"}) {
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
        // 三套 Key 都必须打码
        for (String id : new String[]{"etApiKey", "etAsrKey", "etTtsKey"}) {
            Element key = elementById(id);
            assertEquals(id + " 默认必须打码", "textPassword", attr(key, "inputType"));
        }

        Element toggle = elementById("btnToggleKey");
        assertNotNull("API Key 旁必须有「显示/隐藏」开关", toggle);
        assertEquals("开关初始文案应为「显示」", "@string/set_show_key", attr(toggle, "text"));
        assertTrue("开关热区必须 ≥48dp", parseDp(attr(toggle, "layout_height")) >= 48);
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

    // ── 分组高度估算 ──────────────────────────

    /** 找出设置/模型页里所有 card_surface 分组（排除 item_model.xml 运行时才有的行）。 */
    private static List<Element> cardGroups() throws Exception {
        List<Element> out = new ArrayList<>();
        List<Element> all = new ArrayList<>();
        collect(parseLayout().getDocumentElement(), all);
        for (Element e : all) {
            if ("LinearLayout".equals(e.getTagName())
                    && "vertical".equals(attr(e, "orientation"))
                    && attr(e, "background").endsWith("card_surface")) {
                out.add(e);
            }
        }
        return out;
    }

    /** 一个控件（或一行横向容器）占用的垂直高度。 */
    private static double extentDp(Element e, Map<String, Map<String, String>> styles) {
        if ("LinearLayout".equals(e.getTagName())
                && "horizontal".equals(attr(e, styles, "orientation"))) {
            double max = 0;
            for (Element c : childElements(e)) max = Math.max(max, extentDp(c, styles));
            return max;
        }
        // 纵向 LinearLayout（分组/子容器）→ 递归累加子元素高度 + 内边距
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

    /** 一个分组（含自身内边距）的总高度。 */
    private static double groupHeightDp(Element group) throws Exception {
        Map<String, Map<String, String>> styles = parseStyles();
        double total = 0;
        for (Element c : childElements(group)) {
            if (isGone(c)) continue;
            total += marginTop(c, styles) + extentDp(c, styles);
        }
        total += padding(group, styles, "paddingTop") + padding(group, styles, "paddingBottom");
        return total;
    }

    private static boolean isGone(Element e) {
        return "gone".equals(e.getAttribute("android:visibility"));
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
        if (v == null || !v.endsWith("dp")) return 0;
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
        return parseResource("/layout/activity_main.xml");
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

    /** 读源文件原文（契约断言用）。 */
    private static String readFile(String path) throws Exception {
        java.io.File f = new java.io.File(path);
        assertTrue("文件不存在: " + f.getAbsolutePath(), f.exists());
        try (java.util.Scanner sc = new java.util.Scanner(f, "UTF-8")) {
            sc.useDelimiter("\\A");
            return sc.hasNext() ? sc.next() : "";
        }
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
        // 识别模型芯片行在 XML（模型页）；音色芯片行迁到 Bottom Sheet（SettingsPage 代码构建）。
        String layout = readFile("src/main/res/layout/activity_main.xml");
        assertTrue("识别模型选择行必须横向可滚（HorizontalScrollView）",
                layout.contains("HorizontalScrollView"));

        String settingsPage = readFile("src/main/java/com/rd/englishcoach/SettingsPage.java");
        assertTrue("音色选择 sheet 的芯片行也必须横向可滚",
                settingsPage.contains("HorizontalScrollView"));
    }
}
