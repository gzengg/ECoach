package com.rd.englishcoach;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;

import static org.junit.Assert.*;

/**
 * 卡片布局契约测试（功能 1 + C 修复的回归守护）：
 * - 原文在文档顺序上优先于答案（位于上方）
 * - 原文字号 >= 答案字号（视觉优先）
 * - 原文始终可见（visibility 不是 gone）
 * - 所有文字元素与底板对比度 >= 4.5:1
 * - 底板必须不透明
 *
 * <p>通过 classpath 读取 layout/drawable 源文件（build.gradle 已把 res 目录加入 test resources），
 * 不依赖工作目录，不依赖 Android 运行时。</p>
 */
public class SegmentCardLayoutTest {

    private static final double MIN_CONTRAST = 4.5;

    // ── 功能 1：原文优先 ────────────────────

    @Test
    public void transcript_beforeAnswer_inLayoutOrder() throws Exception {
        int tIdx = indexOfId("tvTranscriptText");
        int aIdx = indexOfId("tvAnswerText");
        assertTrue("原文 (idx=" + tIdx + ") 必须在答案 (idx=" + aIdx + ") 之前显示",
                tIdx < aIdx);
    }

    @Test
    public void transcript_alwaysVisible() throws Exception {
        Element el = elementById("tvTranscriptText");
        String vis = el.getAttribute("android:visibility");
        assertTrue("原文必须始终可见（不能是 gone），实际=" + vis,
                vis.isEmpty() || !vis.equals("gone"));
    }

    @Test
    public void transcript_fontSize_noSmallerThanAnswer() throws Exception {
        int tsT = parseSp(elementById("tvTranscriptText").getAttribute("android:textSize"));
        int tsA = parseSp(elementById("tvAnswerText").getAttribute("android:textSize"));
        assertTrue("原文字号 (" + tsT + "sp) 必须 >= 答案字号 (" + tsA + "sp)",
                tsT >= tsA);
    }

    // ── 对比度（从文件真实颜色计算） ─────────

    @Test
    public void transcript_contrast_meetsWcagAA() throws Exception {
        String fgHex = resolveColorRef(elementById("tvTranscriptText").getAttribute("android:textColor"));
        double r = contrast(parseColor(fgHex), parseColor(bgColor()));
        assertTrue("原文对比度只有 " + String.format("%.1f", r) + ":1（需 >= " + MIN_CONTRAST + "）",
                r >= MIN_CONTRAST);
    }

    @Test
    public void answer_contrast_meetsWcagAA() throws Exception {
        String fgHex = resolveColorRef(elementById("tvAnswerText").getAttribute("android:textColor"));
        double r = contrast(parseColor(fgHex), parseColor(bgColor()));
        assertTrue("答案对比度只有 " + String.format("%.1f", r) + ":1", r >= MIN_CONTRAST);
    }

    @Test
    public void has_bothAnswerAndQuestionButtons() throws Exception {
        assertNotNull("应有「参考回答」按钮", elementById("btnAskAnswer"));
        assertNotNull("应有「询问AI」按钮", elementById("btnAskQuestion"));
    }

    @Test
    public void cardBackground_isOpaque() throws Exception {
        int bg = parseColor(bgColor());
        assertEquals("卡片底板必须不透明", 0xFF, (bg >>> 24) & 0xFF);
    }

    // ── WCAG 数学自检 ────────────────────

    @Test
    public void contrast_whiteOnBlack_is21() {
        assertEquals(21.0, contrast(0xFFFFFFFF, 0xFF000000), 0.01);
    }

    // ── 解析实现（classpath） ────────────────

    private static Document parseLayout() throws Exception {
        return parseResource("/layout/item_segment.xml");
    }

    private static Element elementById(String id) throws Exception {
        Document doc = parseLayout();
        NodeList all = doc.getElementsByTagName("TextView");
        for (int i = 0; i < all.getLength(); i++) {
            Element e = (Element) all.item(i);
            String attr = e.getAttribute("android:id");
            // android:id 的值可能是 "@+id/tvXxx" 或 "tvXxx"
            if (attr.equals(id) || attr.equals("@+id/" + id) || attr.equals("@id/" + id)) return e;
        }
        fail("找不到 id=" + id + ", 共 " + all.getLength() + " 个 TextView");
        return null;
    }

    // ── 喇叭按钮必须能区分（用户反馈「两个读分不清」） ──

    @Test
    public void segmentCard_speakButtons_haveDistinctLabels() throws Exception {
        String transcript = elementById("btnSpeakTranscript").getAttribute("android:text");
        String answer = elementById("btnSpeakAnswer").getAttribute("android:text");
        assertFalse("两个喇叭按钮不能同名（用户分不清哪个读原文、哪个读回答）",
                transcript.equals(answer));
        assertTrue("原文喇叭要写明读的是原文，实际=" + transcript, transcript.contains("原文"));
        assertTrue("回答喇叭要写明读的是回答，实际=" + answer, answer.contains("回答"));
    }

    @Test
    public void grabCard_speakButtons_haveDistinctLabels() throws Exception {
        // 同类问题排查：取词卡片的两个喇叭
        Document doc = parseResource("/layout/item_grab.xml");
        String src = byId(doc, "btnSpeakSrc");
        String dst = byId(doc, "btnSpeakDst");
        assertFalse("取词卡两个喇叭不能同名，实际=" + src + "/" + dst, src.equals(dst));
        assertTrue("读原文按钮要写明原文，实际=" + src, src.contains("原文"));
        assertTrue("读译文按钮要写明译文，实际=" + dst, dst.contains("译文"));
    }

    /** 在指定布局文档里按 id 取 android:text（找不到则 fail）。 */
    private static String byId(Document doc, String id) {
        NodeList all = doc.getElementsByTagName("TextView");
        for (int i = 0; i < all.getLength(); i++) {
            Element e = (Element) all.item(i);
            String attr = e.getAttribute("android:id");
            if (attr.equals(id) || attr.equals("@+id/" + id) || attr.equals("@id/" + id)) {
                return e.getAttribute("android:text");
            }
        }
        fail("找不到 id=" + id);
        return null;
    }

    private static int indexOfId(String id) throws Exception {
        Document doc = parseLayout();
        NodeList all = doc.getElementsByTagName("TextView");
        for (int i = 0; i < all.getLength(); i++) {
            String attr = ((Element) all.item(i)).getAttribute("android:id");
            if (attr.equals(id) || attr.equals("@+id/" + id) || attr.equals("@id/" + id)) return i;
        }
        return -1;
    }

    private static String bgColor() throws Exception {
        Document d = parseResource("/drawable/bg_answer.xml");
        String raw = ((Element) d.getElementsByTagName("solid").item(0)).getAttribute("android:color");
        return resolveColorRef(raw);
    }

    /** 解析 @color/xxx 引用，返回实际的十六进制值。支持裸 #hex。 */
    private static String resolveColorRef(String ref) {
        if (!ref.startsWith("@color/")) return ref;
        String name = ref.substring("@color/".length());
        try {
            Document d = parseResource("/values/colors.xml");
            NodeList all = d.getElementsByTagName("color");
            for (int i = 0; i < all.getLength(); i++) {
                Element e = (Element) all.item(i);
                if (name.equals(e.getAttribute("name"))) return e.getTextContent().trim();
            }
        } catch (Exception ignored) { }
        fail("无法解析颜色引用: " + ref);
        return null;
    }

    private static Document parseResource(String path) throws Exception {
        InputStream is = SegmentCardLayoutTest.class.getResourceAsStream(path);
        assertNotNull("classpath 上找不到资源: " + path, is);
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(false);
        Document doc = f.newDocumentBuilder().parse(is);
        is.close();
        return doc;
    }

    private static int parseSp(String s) {
        return Integer.parseInt(s.replace("sp", "").trim());
    }

    private static int parseColor(String hex) {
        String s = hex.startsWith("#") ? hex.substring(1) : hex;
        if (s.length() == 6) s = "FF" + s;
        return (int) Long.parseLong(s, 16);
    }

    private static double contrast(int fg, int bg) {
        double l1 = lum(fg), l2 = lum(bg);
        return (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05);
    }

    private static double lum(int argb) {
        double r = ch((argb >> 16) & 0xFF), g = ch((argb >> 8) & 0xFF), b = ch(argb & 0xFF);
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    private static double ch(int v) {
        double c = v / 255.0;
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }
}
