package com.rd.englishcoach;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.Assert.*;

/**
 * 回答卡片的<b>可读性契约测试</b>：直接从布局与 drawable 源文件里读出真实颜色，
 * 按 WCAG 2.1 计算对比度。
 *
 * <p>历史 bug：{@code bg_answer} 是 {@code #F2FFFFFF}（近白），
 * 而 {@code tvAnswerText} 是 {@code #FFFFFF}（白）→ 对比度 1.0:1，
 * 用户看到"白灰底板上白字，看不见回答"。</p>
 *
 * <p>不依赖 Android 运行时，纯 JVM 解析 XML，所以在 {@code testDebugUnitTest} 里就能跑。</p>
 */
public class AnswerCardContrastTest {

    /** WCAG 2.1 AA 对正文的最低对比度。 */
    private static final double MIN_CONTRAST = 4.5;

    private static final String LAYOUT = "item_answer.xml";
    private static final String ANSWER_ID = "@+id/tvAnswerText";
    private static final String TRANSCRIPT_ID = "@+id/tvTranscriptText";

    // ── 核心断言 ──────────────────────────────

    @Test
    public void answerText_meetsWcagAa_regression() throws Exception {
        double ratio = contrastOf(ANSWER_ID);
        assertTrue(String.format(
                "回归：回答正文与卡片底板的对比度只有 %.2f:1（需 >= %.1f:1）——白底白字会看不见回答",
                ratio, MIN_CONTRAST), ratio >= MIN_CONTRAST);
    }

    @Test
    public void transcriptText_meetsWcagAa() throws Exception {
        double ratio = contrastOf(TRANSCRIPT_ID);
        assertTrue(String.format("原文小字对比度只有 %.2f:1（需 >= %.1f:1）", ratio, MIN_CONTRAST),
                ratio >= MIN_CONTRAST);
    }

    @Test
    public void answerText_isNotSameColorAsCardBackground_regression() throws Exception {
        int fg = parseColor(textColorOf(ANSWER_ID));
        int bg = parseColor(cardBackgroundColor());
        assertNotEquals("回归：文字颜色不能等于卡片底色，否则完全不可见", bg, fg);
    }

    @Test
    public void cardBackground_isOpaque() throws Exception {
        int bg = parseColor(cardBackgroundColor());
        assertEquals("卡片底板必须不透明，否则对比度取决于底下的面板，无法保证可读",
                0xFF, (bg >>> 24) & 0xFF);
    }

    // ── 自检：确保对比度算法本身是对的 ──────────

    @Test
    public void contrastMath_whiteOnBlack_is21() {
        assertEquals(21.0, contrast(0xFFFFFFFF, 0xFF000000), 0.01);
    }

    @Test
    public void contrastMath_sameColor_is1() {
        assertEquals(1.0, contrast(0xFFFFFFFF, 0xFFFFFFFF), 0.001);
        assertEquals(1.0, contrast(0xFF232833, 0xFF232833), 0.001);
    }

    @Test
    public void contrastMath_isSymmetric() {
        assertEquals(contrast(0xFFFFFFFF, 0xFF232833), contrast(0xFF232833, 0xFFFFFFFF), 1e-9);
    }

    // ── 解析实现 ──────────────────────────────

    private double contrastOf(String viewId) throws Exception {
        return contrast(parseColor(textColorOf(viewId)), parseColor(cardBackgroundColor()));
    }

    /** 读出指定 id 的 android:textColor 字面量。 */
    private String textColorOf(String viewId) throws Exception {
        Document doc = parse(res("layout", LAYOUT));
        NodeList all = doc.getElementsByTagName("TextView");
        for (int i = 0; i < all.getLength(); i++) {
            Element el = (Element) all.item(i);
            if (viewId.equals(el.getAttribute("android:id"))) {
                String c = el.getAttribute("android:textColor");
                assertTrue("tvAnswerText/tvTranscriptText 的颜色必须是字面量（如 #FFFFFFFF），实际=" + c,
                        c.startsWith("#"));
                return c;
            }
        }
        fail("布局 " + LAYOUT + " 里找不到 id=" + viewId);
        return null;
    }

    /** 读出根布局引用的 drawable 里的 solid 颜色。 */
    private String cardBackgroundColor() throws Exception {
        Document doc = parse(res("layout", LAYOUT));
        Element root = doc.getDocumentElement();
        String bgRef = root.getAttribute("android:background");
        assertTrue("根布局必须用 android:background 引用卡片底板，实际=" + bgRef,
                bgRef.startsWith("@drawable/"));
        String drawableName = bgRef.substring("@drawable/".length()) + ".xml";

        Document d = parse(res("drawable", drawableName));
        NodeList solids = d.getElementsByTagName("solid");
        assertTrue("drawable/" + drawableName + " 里必须有 <solid android:color=...>",
                solids.getLength() > 0);
        String c = ((Element) solids.item(0)).getAttribute("android:color");
        assertTrue("solid 颜色必须是字面量，实际=" + c, c.startsWith("#"));
        return c;
    }

    private static Document parse(File f) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false); // 让属性名保持 "android:textColor" 原样
        return factory.newDocumentBuilder().parse(f);
    }

    /** 从测试工作目录向上找 src/main/res。 */
    private static File res(String sub, String name) {
        File dir = new File("").getAbsoluteFile();
        for (int i = 0; i < 6 && dir != null; i++) {
            for (String prefix : new String[]{"", "app/"}) {
                File cand = new File(dir, prefix + "src/main/res/" + sub + "/" + name);
                if (cand.isFile()) return cand;
            }
            dir = dir.getParentFile();
        }
        throw new IllegalStateException("找不到 res/" + sub + "/" + name
                + "，当前工作目录=" + new File("").getAbsolutePath());
    }

    // ── 颜色与对比度 ──────────────────────────

    static int parseColor(String hex) {
        String s = hex.startsWith("#") ? hex.substring(1) : hex;
        if (s.length() == 6) s = "FF" + s;           // #RRGGBB -> 不透明
        if (s.length() != 8) throw new IllegalArgumentException("无法解析颜色: " + hex);
        return (int) Long.parseLong(s, 16);
    }

    static double contrast(int argb1, int argb2) {
        double l1 = luminance(argb1);
        double l2 = luminance(argb2);
        double hi = Math.max(l1, l2);
        double lo = Math.min(l1, l2);
        return (hi + 0.05) / (lo + 0.05);
    }

    /** WCAG 2.1 相对亮度。 */
    static double luminance(int argb) {
        double r = channel((argb >> 16) & 0xFF);
        double g = channel((argb >> 8) & 0xFF);
        double b = channel(argb & 0xFF);
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    private static double channel(int v) {
        double c = v / 255.0;
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }
}
