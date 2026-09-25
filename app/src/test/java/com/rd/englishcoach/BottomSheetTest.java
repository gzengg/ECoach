package com.rd.englishcoach;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;

import static org.junit.Assert.*;

/**
 * Bottom Sheet（自绘底部弹窗）契约测试。
 *
 * <p>约束：不用 Material 库（白名单）；遮罩/面板色只引用 token；
 * §4.5 只做进入动画（关闭直接消失）；点遮罩可关、系统返回可关（右滑返回不拦截）。</p>
 */
public class BottomSheetTest {

    private static Document parse(String path) throws Exception {
        InputStream is = BottomSheetTest.class.getResourceAsStream(path);
        assertNotNull("classpath 上找不到: " + path, is);
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(is);
        is.close();
        return doc;
    }

    private static String text(Document doc) {
        StringBuilder sb = new StringBuilder();
        collect(doc.getDocumentElement(), sb);
        return sb.toString();
    }

    private static void collect(Element e, StringBuilder sb) {
        sb.append('<').append(e.getTagName()).append(' ');
        for (int i = 0; i < e.getAttributes().getLength(); i++) {
            sb.append(e.getAttributes().item(i).getNodeName()).append('=')
              .append(e.getAttributes().item(i).getNodeValue()).append(' ');
        }
        sb.append('>');
        for (int i = 0; i < e.getChildNodes().getLength(); i++) {
            org.w3c.dom.Node n = e.getChildNodes().item(i);
            if (n.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE) {
                collect((Element) n, sb);
            }
        }
    }

    @Test
    public void sheetDrawable_usesTokensNotBareHex() throws Exception {
        String xml = text(parse("/drawable/bg_sheet.xml"));
        assertTrue("面板底必须是 token 色（surface_1，不透明）", xml.contains("@color/surface_1"));
        assertTrue("顶部圆角必须用 radius_lg（与卡片同一套）", xml.contains("@dimen/radius_lg"));
        assertFalse("资源里不许散落裸十六进制色值（规则 §1-9）", xml.contains("#"));
    }

    @Test
    public void sheetHandle_usesTokens() throws Exception {
        String xml = text(parse("/drawable/sheet_handle.xml"));
        assertTrue("把手必须用描边 token 色", xml.contains("@color/stroke_strong"));
        assertFalse(xml.contains("#"));
    }

    @Test
    public void scrimDefinedInColors() throws Exception {
        String colors = readFile("src/main/res/values/colors.xml");
        assertTrue("遮罩色 scrim 必须定义在 colors.xml（token 单一来源）",
                colors.contains("<color name=\"scrim\">"));
    }

    @Test
    public void enterAnimationOnly_noExit() throws Exception {
        String anim = readFile("src/main/res/anim/sheet_enter.xml");
        assertTrue("入场必须是上滑", anim.contains("translate"));
        assertTrue("入场时长 180ms（与卡片入场一致）", anim.contains("180"));

        String styles = readFile("src/main/res/values/styles.xml");
        int i = styles.indexOf("name=\"SheetAnimation\"");
        assertTrue("styles.xml 必须有 SheetAnimation", i >= 0);
        String block = styles.substring(i, Math.min(styles.length(), i + 300));
        assertTrue("窗口动画只配入场", block.contains("windowEnterAnimation"));
        assertFalse("§4.5：只做进入动画，不配退出动画", block.contains("windowExitAnimation"));
    }

    @Test
    public void panel_usesFrameworkDialogAndTokens() throws Exception {
        String src = readFile("src/main/java/com/rd/englishcoach/BottomSheetPanel.java");
        assertTrue("必须用框架 Dialog（不引 Material 库）", src.contains("new Dialog("));
        assertTrue("遮罩必须走 token", src.contains("R.color.scrim"));
        assertTrue("面板底必须走 bg_sheet", src.contains("R.drawable.bg_sheet"));
        assertTrue("点遮罩必须能关", src.contains("setCanceledOnTouchOutside(true)"));
        assertFalse("不得使用 FLAG_LAYOUT_NO_LIMITS", src.contains("FLAG_LAYOUT_NO_LIMITS"));
        assertFalse("依赖白名单：不得 import Material/AndroidX",
                src.contains("com.google.android.material") || src.contains("androidx."));
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
