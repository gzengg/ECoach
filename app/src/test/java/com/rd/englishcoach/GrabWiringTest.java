package com.rd.englishcoach;

import org.junit.Test;
import static org.junit.Assert.*;

import java.io.File;
import java.lang.reflect.Method;
import java.util.Scanner;

/**
 * 取词链路接线回归测试。
 *
 * <p>问题：点「取词」按钮无任何反应。</p>
 * <p>根因：</p>
 * <ol>
 *   <li>btnGrabStart 从未 setOnClickListener；</li>
 *   <li>FloatingPanel.Callback 缺 onGrab()，CaptureService.startGrab() 是死代码；</li>
 *   <li>截图在主线程执行且首帧未到直接失败；</li>
 *   <li>框选后二次截屏，可能为 null 或与展示帧不一致。</li>
 * </ol>
 * <p>本测试锁死这四个修复点，防止回归。</p>
 */
public class GrabWiringTest {

    // ── 接线：按钮 → Callback → startGrab ─────────────

    @Test
    public void floatingPanel_grabButtonHasClickListener() throws Exception {
        String src = readFile("src/main/java/com/rd/englishcoach/FloatingPanel.java");
        assertTrue("btnGrabStart 必须 setOnClickListener（缺失则点击无反应）",
                src.contains("btnGrabStart.setOnClickListener"));
        assertTrue("取词按钮点击必须回调 cb.onGrab()",
                src.contains("cb.onGrab()"));
    }

    @Test
    public void callbackInterface_hasOnGrab() throws Exception {
        Method m = null;
        for (Method method : FloatingPanel.Callback.class.getDeclaredMethods()) {
            if (method.getName().equals("onGrab")) { m = method; break; }
        }
        assertNotNull("FloatingPanel.Callback 必须有 onGrab()", m);
        assertEquals("onGrab() 无参数", 0, m.getParameterCount());
        assertEquals(void.class, m.getReturnType());
    }

    @Test
    public void captureService_onGrab_callsStartGrab() throws Exception {
        String src = readFile("src/main/java/com/rd/englishcoach/CaptureService.java");
        int methodStart = src.indexOf("public void onGrab()");
        assertTrue("CaptureService.PanelCallback 必须实现 onGrab()", methodStart > 0);
        int braceStart = src.indexOf('{', methodStart);
        String body = extractMethodBody(src, braceStart);
        assertTrue("onGrab() 必须调用 startGrab()（否则取词链路是死代码）",
                stripComments(body).contains("startGrab()"));
    }

    // ── 截图不在主线程 + 首帧重试 ─────────────────────

    @Test
    public void grabManager_captureOnBackgroundThread_freshFrame() throws Exception {
        String src = readFile("src/main/java/com/rd/englishcoach/GrabManager.java");
        assertTrue("截图必须在后台线程执行",
                src.contains("new Thread("));
        assertTrue("必须走 grabFrame()（丢弃滞留帧 + 强制重绘取当下屏幕的新帧，"
                + "旧机制第二次取词等不到新帧、拿到的是陈旧画面）",
                src.contains("capture.grabFrame()"));
        assertFalse("取词流程不得直接轮询 acquireLatestImage（拿不到新帧/拿到旧帧）",
                src.contains("acquireLatestImage"));
    }

    // ── 不再二次截屏：框选直接裁剪展示帧 ──────────────

    @Test
    public void grabManager_noSecondCapture() throws Exception {
        String src = readFile("src/main/java/com/rd/englishcoach/GrabManager.java");
        assertFalse("onRegionSelected 不得再调用 capture/captureFrame/captureRegion（二次截屏会 null 或帧不一致）",
                regionBody(src).contains("capture.captureFrame")
                        || regionBody(src).contains("captureRegion"));
        assertTrue("onRegionSelected 必须接收展示帧裁好的 Bitmap",
                src.contains("public void onRegionSelected(Bitmap region)"));
    }

    @Test
    public void grabOverlay_cropsDisplayedFrameBeforeDismiss() throws Exception {
        String src = readFile("src/main/java/com/rd/englishcoach/GrabOverlay.java");
        int methodStart = src.indexOf("private void onSelectionComplete()");
        assertTrue("GrabOverlay 必须有 onSelectionComplete", methodStart > 0);
        int braceStart = src.indexOf('{', methodStart);
        String body = stripComments(extractMethodBody(src, braceStart));
        int cropIdx = body.indexOf("Bitmap.createBitmap(screenshot");
        assertTrue("必须从展示帧裁剪", cropIdx >= 0);
        int dismissIdx = body.indexOf("dismiss()", cropIdx);
        assertTrue("裁剪之后才能 dismiss（dismiss 会 recycle 截图）",
                dismissIdx > cropIdx);
        assertTrue("裁剪结果必须传给回调",
                body.contains("callback.onRegionSelected(region)"));
    }

    @Test
    public void grabOverlay_callbackReceivesBitmap() throws Exception {
        Method m = null;
        for (Method method : GrabOverlay.Callback.class.getDeclaredMethods()) {
            if (method.getName().equals("onRegionSelected")) { m = method; break; }
        }
        assertNotNull("GrabOverlay.Callback 必须有 onRegionSelected", m);
        assertEquals("回调必须传 Bitmap（而非坐标，坐标会导致二次截屏）",
                1, m.getParameterCount());
        assertEquals(android.graphics.Bitmap.class, m.getParameterTypes()[0]);
    }

    // ── failOnMain 语义：回 IDLE 后无条件通知 ─────────

    @Test
    public void grabManager_failOnMainUnconditionalNotify() throws Exception {
        String src = readFile("src/main/java/com/rd/englishcoach/GrabManager.java");
        int methodStart = src.indexOf("private void failOnMain");
        assertTrue("GrabManager 必须有 failOnMain", methodStart > 0);
        int braceStart = src.indexOf('{', methodStart);
        String body = stripComments(extractMethodBody(src, braceStart));
        // 旧 bug：post 后再判断 state == IDLE（恒真但绕，且 setState 也 post，顺序易错）
        assertFalse("failOnMain 不得再带恒真的 state==IDLE 条件判断",
                body.contains("state == State.IDLE"));
        assertTrue("failOnMain 必须无条件回调 onAborted",
                body.contains("callback.onAborted(reason)"));
    }

    // ── 死代码清理 ─────────────────────────────────

    @Test
    public void screenTextCapture_regionCaptureRemoved() throws Exception {
        String src = readFile("src/main/java/com/rd/englishcoach/ScreenTextCapture.java");
        assertFalse("captureRegion 已无调用者，应删除避免误用（它会二次截屏）",
                src.contains("captureRegion"));
    }

    // ── 工具 ─────────────────────────────────────────

    private static String readFile(String path) throws Exception {
        File f = new File(path);
        assertTrue("文件不存在: " + path, f.exists());
        try (Scanner s = new Scanner(f, "UTF-8")) {
            s.useDelimiter("\\A");
            return s.hasNext() ? s.next() : "";
        }
    }

    private static String regionBody(String src) {
        int methodStart = src.indexOf("public void onRegionSelected");
        if (methodStart < 0) return "";
        return stripComments(extractMethodBody(src, src.indexOf('{', methodStart)));
    }

    /** 提取从 braceStart 那个 '{' 开始的方法体（含外层大括号）。 */
    private static String extractMethodBody(String src, int braceStart) {
        assertTrue("找不到方法体起始 '{'", braceStart >= 0);
        int depth = 0;
        for (int i = braceStart; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return src.substring(braceStart, i + 1);
            }
        }
        fail("方法体大括号不配对");
        return "";
    }

    /** 去掉块注释与行注释，避免注释里的关键词误判。 */
    private static String stripComments(String s) {
        s = s.replaceAll("(?s)/\\*.*?\\*/", "");
        s = s.replaceAll("//[^\n]*", "");
        return s;
    }
}
