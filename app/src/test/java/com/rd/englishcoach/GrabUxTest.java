package com.rd.englishcoach;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.Scanner;
import org.junit.Test;

/**
 * 取词「悬浮窗不再消失」的契约测试（问题2，用户选定方案 B + C）。
 *
 * <p>用户反馈：取词提取阶段悬浮窗完全消失，会让人以为 App 退出了。</p>
 * <ul>
 *   <li><b>C</b>：截图期间不 hide 面板，只把窗口设为全透明（alpha=0）并禁止触摸
 *       —— 视觉上面板「没消失」，截图里也不会带上面板文字；</li>
 *   <li><b>B</b>：框选一完成就立即恢复面板并显示「取词识别中…」，
 *       消掉 OCR + 翻译期间 1~3 秒的完全空白期。</li>
 * </ul>
 */
public class GrabUxTest {

    private static String readFile(String path) throws Exception {
        File f = new File(path);
        assertTrue("文件不存在: " + f.getAbsolutePath(), f.exists());
        try (Scanner s = new Scanner(f, "UTF-8")) {
            s.useDelimiter("\\A");
            return s.hasNext() ? s.next() : "";
        }
    }

    /** 去掉块注释与行注释，避免注释里的关键词误判。 */
    private static String stripComments(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int i = 0;
        while (i < src.length()) {
            if (i + 1 < src.length() && src.charAt(i) == '/' && src.charAt(i + 1) == '*') {
                int end = src.indexOf("*/", i + 2);
                i = end < 0 ? src.length() : end + 2;
            } else if (i + 1 < src.length() && src.charAt(i) == '/' && src.charAt(i + 1) == '/') {
                int end = src.indexOf('\n', i);
                i = end < 0 ? src.length() : end;
            } else {
                out.append(src.charAt(i));
                i++;
            }
        }
        return out.toString();
    }

    private static String methodBody(String src, String signature) {
        int start = src.indexOf(signature);
        if (start < 0) return null;
        int brace = src.indexOf('{', start);
        if (brace < 0) return null;
        int depth = 0;
        for (int i = brace; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return src.substring(brace + 1, i);
            }
        }
        return null;
    }

    private String panel() throws Exception {
        return stripComments(readFile("src/main/java/com/rd/englishcoach/FloatingPanel.java"));
    }

    private String captureService() throws Exception {
        return stripComments(readFile("src/main/java/com/rd/englishcoach/CaptureService.java"));
    }

    // ── C：截图期间只透明、不隐藏 ────────────────

    @Test
    public void floatingPanel_hasSetCaptureInvisible() throws Exception {
        String src = panel();
        String body = methodBody(src, "public void setCaptureInvisible(boolean invisible)");
        assertNotNull("FloatingPanel 必须有 setCaptureInvisible()", body);
        assertTrue("取词期间必须把窗口设为全透明（alpha=0）",
                body.contains("wlp.alpha = invisible ? 0f : 1f"));
        assertTrue("透明期间必须禁止触摸（避免点到看不见的按钮）",
                body.contains("FLAG_NOT_TOUCHABLE"));
        assertTrue("必须真正更新窗口布局",
                body.contains("wm.updateViewLayout(root, wlp)"));
        assertTrue("面板已被移除时要能安全返回",
                body.contains("getParent() == null"));
    }

    @Test
    public void floatingPanel_showRestore_restoresCaptureVisibility() throws Exception {
        String body = methodBody(panel(), "public void showRestore()");
        assertNotNull("必须有 showRestore", body);
        assertTrue("取词结束/取消后必须恢复面板可见性（否则面板永远透明）",
                body.contains("setCaptureInvisible(false)"));
    }

    @Test
    public void captureService_startGrab_transparentInsteadOfHide() throws Exception {
        String body = methodBody(captureService(), "private void startGrab()");
        assertNotNull("必须有 startGrab", body);
        assertTrue("取词必须把面板设为透明", body.contains("setCaptureInvisible(true)"));
        assertFalse("取词不得再 hide 面板（用户会以为 App 退出）",
                body.contains("panel.hide()"));
    }

    // ── B：框选完立即恢复面板 ───────────────────

    @Test
    public void captureService_restoresPanelAsSoonAsSelectionDone() throws Exception {
        String body = methodBody(captureService(), "private void startGrab()");
        assertNotNull(body);
        assertTrue("必须在 OCR 状态（= 框选刚完成）就恢复面板",
                body.contains("GrabManager.State.OCR"));
        int ocrIdx = body.indexOf("GrabManager.State.OCR");
        int restoreIdx = body.indexOf("showRestore()", ocrIdx);
        assertTrue("OCR 分支必须调用 showRestore()", restoreIdx > ocrIdx);
        assertTrue("必须显示「取词识别中…」状态",
                body.contains("msg_grab_recognizing"));
        assertTrue("必须切到取词页展示结果",
                body.contains("switchToTabExternal(1)"));
    }

    @Test
    public void captureService_restoreHappensBeforeTranslationResult() throws Exception {
        String body = methodBody(captureService(), "private void startGrab()");
        assertNotNull(body);
        // 面板恢复必须发生在 onTranslationResult 之前（即不依赖翻译完成）
        int ocrRestore = body.indexOf("GrabManager.State.OCR");
        int translationResult = body.indexOf("onTranslationResult");
        assertTrue("必须有 onTranslationResult 回调", translationResult > 0);
        assertTrue("恢复面板必须在 OCR 阶段就做，而不是等翻译回来（否则中间 1~3 秒全空白）",
                ocrRestore > 0 && ocrRestore < translationResult);
    }

    @Test
    public void stringsXml_hasGrabRecognizing() throws Exception {
        String xml = readFile("src/main/res/values/strings.xml");
        assertTrue(xml.contains("<string name=\"msg_grab_recognizing\">"));
    }
}
