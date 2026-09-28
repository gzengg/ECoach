package com.rd.englishcoach;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * 模型页「在线 / 离线」两个子 Tab + 在线三套接口折叠卡的布局契约。
 *
 * <p>纯 JVM 测试（读 src/main/res 源文件与 Java 源文件），不实例化任何 View。</p>
 *
 * <p>⚠️ 这些断言钉住的是<b>信息架构</b>而不是像素：引擎模式必须在子 Tab 之上（它是「用哪边」的
 * 开关，自动模式下两侧同时参与，塞进任一侧都会让另一侧的用户找不到）；三套在线接口默认收起
 * （否则分完 Tab 在线侧仍有 ~1670dp、要滚 2.5 屏，分 Tab 就白分了）。</p>
 */
public class ModelsTabLayoutTest {

    private static final String LAYOUT = "/layout/activity_main.xml";
    private static final String MAIN_XML = "src/main/res/layout/activity_main.xml";
    private static final String MODELS_JAVA =
            "src/main/java/com/rd/englishcoach/ModelsPage.java";
    private static final String MAIN_JAVA =
            "src/main/java/com/rd/englishcoach/MainActivity.java";
    private static final String SECTION_JAVA =
            "src/main/java/com/rd/englishcoach/OnlineSection.java";
    private static final String PREFS_JAVA =
            "src/main/java/com/rd/englishcoach/Prefs.java";

    @Test
    public void modelsPage_hasTwoSubTabsEachWithItsOwnContainer() throws Exception {
        for (String id : new String[]{"btnTabOnline", "btnTabOffline", "pageOnline", "pageOffline"}) {
            assertNotNull("模型页缺少子 Tab 控件: " + id, elementById(id));
        }
        assertTrue("两个子 Tab 必须各有一个独立容器（不能靠共用一个容器 + 动态重填，切 Tab 会丢状态）",
                elementById("pageOnline") != elementById("pageOffline"));
    }

    @Test
    public void engineMode_staysAboveTheSubTabs() throws Exception {
        List<Element> all = new ArrayList<>();
        collect(parseLayout().getDocumentElement(), all);
        int mode = indexOfId(all, "btnModeAuto");
        int modeHint = indexOfId(all, "tvModelSummary");
        int tabs = indexOfId(all, "btnTabOnline");
        int onlinePage = indexOfId(all, "pageOnline");
        assertTrue("找不到引擎模式芯片", mode > 0);
        assertTrue("引擎模式必须在子 Tab 之上（自动模式两侧同时参与，不能塞进任一侧）",
                mode < tabs && modeHint < tabs);
        assertTrue("子 Tab 行必须在两个内容容器之前", tabs < onlinePage);
    }

    @Test
    public void onlineTab_holdsThreeCollapsibleInterfaceCards() throws Exception {
        String[][] pairs = {{"Chat", "问答"}, {"Asr", "识别"}, {"Tts", "朗读"}};        for (String[] p : pairs) {
            Element head = elementById("headIface" + p[0]);
            Element box = elementById("boxIface" + p[0]);
            assertNotNull(p[1] + "接口缺少收起态的摘要行 headIface" + p[0], head);
            assertNotNull(p[1] + "接口缺少展开容器 boxIface" + p[0], box);
            assertNotNull(p[1] + "接口缺少摘要文字 sumIface" + p[0], elementById("sumIface" + p[0]));
            assertNotNull(p[1] + "接口缺少箭头 caretIface" + p[0], elementById("caretIface" + p[0]));
            assertEquals("三套接口默认必须全部收起（否则在线侧仍要滚 2.5 屏）",
                    "gone", box.getAttribute("android:visibility"));
            assertTrue(p[1] + "接口的折叠头必须挂在 pageOnline 里", inside(box, "pageOnline"));
        }
    }

    @Test
    public void onlineTab_keepsTheThreeInterfacesIndependent() throws Exception {
        // 每套接口的 Base URL / Key / 模型都必须在自己的折叠容器里，不能挤在一张卡上
        String[] boxes = {"boxIfaceChat", "boxIfaceAsr", "boxIfaceTts"};
        String[][] fields = {
                {"etBaseUrl", "etApiKey", "etChatModel"},
                {"etAsrBaseUrl", "etAsrKey", "etAsrModel"},
                {"etTtsBaseUrl", "etTtsKey", "etTtsModel"},
        };
        for (int i = 0; i < boxes.length; i++) {
            for (String field : fields[i]) {
                Element e = elementById(field);
                assertNotNull("找不到字段 " + field, e);
                assertTrue(field + " 必须在 " + boxes[i] + " 内（三套接口各自独立配置）",
                        inside(e, boxes[i]));
            }
        }
    }

    @Test
    public void offlineTab_ownsModelListAndAsrPicker() throws Exception {
        for (String id : new String[]{"modelList", "asrModelChips"}) {
            Element e = elementById(id);
            assertNotNull("找不到 " + id, e);
            assertTrue(id + " 必须归离线子 Tab（它描述的是「用哪个本地模型」）",
                    inside(e, "pageOffline"));
        }
        assertFalse("modelList 不该同时出现在在线容器里", inside(elementById("modelList"), "pageOnline"));
    }

    @Test
    public void summaryRow_isSingleLineAndRightAligned() throws Exception {
        for (String k : new String[]{"Chat", "Asr", "Tts"}) {
            Element sum = elementById("sumIface" + k);
            assertEquals("摘要行必须单行（行高固定 48dp，折行会顶破一屏约束）",
                    "1", sum.getAttribute("android:maxLines"));
            assertEquals("摘要行超长要省略号", "end", sum.getAttribute("android:ellipsize"));
            assertTrue("摘要行要贴着箭头，不能被 76dp 的标签行推到中间",
                    sum.getAttribute("android:gravity").contains("end"));
        }
    }

    @Test
    public void collapsedRow_showsWhatIsMissing() throws Exception {
        String src = readFile(SECTION_JAVA);
        assertTrue("收起态摘要必须带上当前模型 id，否则收起后看不出用的哪个模型",
                src.contains("String summaryOf(ModelDiscovery.Kind k)"));
        assertTrue("摘要要写「未设置模型」", src.contains("R.string.models_iface_no_model"));
        assertTrue("识别 / 朗读的 Key 留空会回退问答 Key，所以两边都空才算没配",
                src.contains("val(keyField(k)).isEmpty() && val(etApiKey).isEmpty()"));
        assertTrue("摘要要写「未配 Key」", src.contains("R.string.models_iface_no_key"));
    }

    @Test
    public void expandingOneInterface_collapsesTheOthers() throws Exception {
        String src = readFile(MODELS_JAVA);
        assertTrue("展开一套必须收起另外两套，否则展开后高度不可控",
                src.contains("ifaceBoxes[i].setVisibility(i == idx && expand ? View.VISIBLE : View.GONE)"));
        assertTrue("箭头方向要跟着展开态走",
                src.contains("R.string.models_iface_expanded : R.string.models_iface_collapsed"));
    }

    @Test
    public void subTab_isPersisted_andDefaultsToOnline() throws Exception {
        assertTrue("子 Tab 选择要记住（离线侧会反复回来，不该每次都重选）",
                readFile(PREFS_JAVA).contains("modelsOnlineTab"));
        assertTrue("默认必须停在「在线」",
                readFile(PREFS_JAVA).contains("getBoolean(\"models_online_tab\", true)"));
        String src = readFile(MODELS_JAVA);
        assertTrue("切子 Tab 要落盘", src.contains("prefs.putModelsOnlineTab(online)"));
        assertTrue("子 Tab 只切显隐，不重载数据（两侧视图都一直活着）",
                src.contains("pageOnline.setVisibility(online ? View.VISIBLE : View.GONE)"));
    }

    @Test
    public void topBarSourceEntry_onlyOnOfflineTab() throws Exception {
        String main = readFile(MAIN_JAVA);
        assertTrue("顶栏动作要按子 Tab 重新计算",
                main.contains("private void applyTopAction()"));
        assertTrue("「下载源」只在离线侧显示",
                main.contains("topAction == BottomBar.TAB_MODELS && !modelsPage.onlineTab()"));
        assertTrue("子 Tab 变化要通知顶栏", main.contains("modelsPage.setSubTabListener("));
        assertTrue("ModelsPage 要暴露子 Tab 通知口", readFile(MODELS_JAVA).contains("interface SubTabListener"));
    }

    @Test
    public void newUi_reusesTokensAndAddsNoColorLiterals() throws Exception {
        String xml = readFile(MAIN_XML);
        // 新增区块只能引用既有 style / drawable / @color，不许出现裸色值（资源里不许散落十六进制）
        int from = xml.indexOf("@+id/pageModelsRoot");
        int to = xml.indexOf("@+id/modelList");
        assertTrue("找不到模型页区块", from > 0 && to > from);
        String page = xml.substring(from, to);
        assertFalse("模型页新区块出现裸十六进制色值（必须引用 @color/*）", page.contains("#"));
        assertTrue("子 Tab 要复用 ModelChip 样式", page.contains("@style/ModelChip"));
        assertTrue("折叠头要复用 SettingRow", page.contains("@style/SettingRow"));
    }

    // ── DOM 工具 ─────────────────────────────

    private static Document parseLayout() throws Exception {
        InputStream is = ModelsTabLayoutTest.class.getResourceAsStream(LAYOUT);
        assertNotNull("classpath 上找不到资源: " + LAYOUT, is);
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(false);
        Document doc = f.newDocumentBuilder().parse(is);
        is.close();
        return doc;
    }

    private static Element elementById(String id) throws Exception {
        List<Element> all = new ArrayList<>();
        collect(parseLayout().getDocumentElement(), all);
        for (Element e : all) {
            String v = e.getAttribute("android:id");
            if (v.equals("@+id/" + id) || v.equals("@id/" + id)) return e;
        }
        return null;
    }

    private static int indexOfId(List<Element> all, String id) {
        for (int i = 0; i < all.size(); i++) {
            String v = all.get(i).getAttribute("android:id");
            if (v.equals("@+id/" + id) || v.equals("@id/" + id)) return i;
        }
        return -1;
    }

    /** 沿父链判断 e 是否在 id 为 ancestor 的容器内（含自身）。 */
    private static boolean inside(Element e, String ancestor) {
        for (Node n = e; n != null; n = n.getParentNode()) {
            if (n instanceof Element) {
                String v = ((Element) n).getAttribute("android:id");
                if (v.equals("@+id/" + ancestor) || v.equals("@id/" + ancestor)) return true;
            }
        }
        return false;
    }

    private static void collect(Element e, List<Element> out) {
        out.add(e);
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element) collect((Element) n, out);
        }
    }

    /** 读源文件原文（契约断言用）。 */
    private static String readFile(String path) throws Exception {
        java.io.File f = new java.io.File(path);
        assertTrue("文件不存在: " + f.getAbsolutePath(), f.exists());
        try (Scanner sc = new Scanner(f, "UTF-8")) {
            sc.useDelimiter("\\A");
            return sc.hasNext() ? sc.next() : "";
        }
    }
}
