package com.rd.englishcoach;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.Scanner;
import org.junit.Test;

/**
 * 问题2（取词依旧用不了）的源码契约测试。真机症状：点「取词」完全没反应
 * （悬浮窗不消失、无提示）。逐条锁住修掉的缺陷：
 * <ol>
 *   <li>失败提示必须能在「取词」页看到（布局契约见 FloatingPanelLayoutTest）；</li>
 *   <li>取词不可用时必须给出原因 + 恢复路径（重新授权），不能静默；</li>
 *   <li>GrabManager 状态机不得静默吞掉点击（卡住要强制复位）；</li>
 *   <li>非交互阶段必须有看门狗，保证面板一定会回来；</li>
 *   <li>OCR 线程任何异常都不能把状态机卡死；</li>
 *   <li>OCR 位图在识别完成前不得回收（ML Kit 不拷贝像素）；</li>\n *   <li>OCR 错误不能跨次污染；</li>
 *   <li>截帧失败必须走 onCaptureFailed 并恢复面板；</li>
 *   <li>截屏通道要有像素格式回退、真实分辨率、失败原因。</li>
 * </ol>
 */
public class GrabRobustnessTest {

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

    private String grabManager() throws Exception {
        return stripComments(readFile("src/main/java/com/rd/englishcoach/GrabManager.java"));
    }

    private String captureService() throws Exception {
        return stripComments(readFile("src/main/java/com/rd/englishcoach/CaptureService.java"));
    }

    private String screenCapture() throws Exception {
        return stripComments(readFile("src/main/java/com/rd/englishcoach/ScreenTextCapture.java"));
    }

    // ── GrabManager：不静默、不卡死 ─────────────

    @Test
    public void startGrab_resetsStuckState_insteadOfSilentReturn() throws Exception {
        String body = methodBody(grabManager(), "public void startGrab(ScreenTextCapture capture, GrabOverlay overlay)");
        assertNotNull("必须有 startGrab", body);
        assertFalse("不能再出现「状态不对就静默 return」", body.contains("startGrab ignored"));
        int reset = body.indexOf("setState(State.IDLE)");
        int start = body.indexOf("setState(State.CAPTURING)");
        assertTrue("卡住时必须先强制复位到 IDLE", reset >= 0);
        assertTrue("复位必须发生在开始新流程之前", start >= 0 && reset < start);
    }

    @Test
    public void startGrab_armsWatchdog() throws Exception {
        String src = grabManager();
        String body = methodBody(src, "public void startGrab(ScreenTextCapture capture, GrabOverlay overlay)");
        assertNotNull(body);
        assertTrue("启动取词必须武装看门狗", body.contains("armWatchdog("));
        assertTrue("必须有 watchdog Runnable", src.contains("Runnable watchdog"));
        assertTrue("进入 IDLE 必须撤销看门狗", src.contains("removeCallbacks(watchdog)"));
        assertTrue("看门狗超时要有用户可见提示", src.contains("取词超时"));
    }

    @Test
    public void onRegionSelected_survivesUnexpectedThrowable() throws Exception {
        String src = grabManager();
        assertTrue("OCR 线程必须兜住 Throwable，否则状态机永远卡在 OCR",
                src.contains("catch (Throwable"));
        String body = methodBody(src, "public void onRegionSelected(Bitmap region)");
        assertNotNull(body);
        assertTrue("裁剪后的区域必须在 finally 里回收", body.contains("finally"));
    }

    @Test
    public void onRegionSelected_recyclesAfterOcrNotBefore() throws Exception {
        String body = methodBody(grabManager(), "public void onRegionSelected(Bitmap region)");
        assertNotNull(body);
        int waitIdx = body.indexOf("ocrDone.wait(");
        int recycleIdx = body.lastIndexOf("region.recycle()");
        assertTrue("必须有等待 OCR 完成的逻辑", waitIdx >= 0);
        assertTrue("必须回收裁剪位图", recycleIdx >= 0);
        assertTrue("必须在 OCR 完成之后才 recycle（ML Kit 的 InputImage 不拷贝像素，"
                + "提前回收会导致 OCR 必失败）", waitIdx < recycleIdx);
    }

    @Test
    public void ocrError_isLocalPerRun() throws Exception {
        String src = grabManager();
        assertFalse("ocrError 不能再是实例字段（会被上一次失败污染）",
                src.contains("private final String[] ocrError"));
        String body = methodBody(src, "public void onRegionSelected(Bitmap region)");
        assertNotNull(body);
        assertTrue("ocrError 必须是每次运行的局部变量",
                body.contains("final String[] ocrError"));
    }

    @Test
    public void captureFailure_goesThroughOnCaptureFailed() throws Exception {
        String src = grabManager();
        String body = methodBody(src, "public void startGrab(ScreenTextCapture capture, GrabOverlay overlay)");
        assertNotNull(body);
        assertTrue("截帧失败必须走 captureFailed()", body.contains("captureFailed("));
        assertTrue("captureFailed 必须真的调用 onCaptureFailed（此前是死回调）",
                src.contains("callback.onCaptureFailed("));
        assertTrue("截屏失败原因要带上截屏通道的错误",
                body.contains("reasonSuffix(capture)"));
    }

    // ── CaptureService：取词入口可读、可恢复 ─────

    @Test
    public void startGrab_notReady_givesReasonAndRecovery() throws Exception {
        String body = methodBody(captureService(), "private void startGrab()");
        assertNotNull("必须有 startGrab", body);
        assertTrue("必须给用户可读提示", body.contains("msg_grab_not_ready"));
        assertTrue("必须露出「重新授权」按钮作为恢复路径", body.contains("setReconsentVisible(true)"));
        assertTrue("必须在取词页也能看到状态", body.contains("setGrabStatus("));
        assertTrue("应切到取词页显示失败原因", body.contains("switchToTabExternal(1)"));
        assertTrue("必须记录不可用原因", body.contains("lastError()"));
    }

    @Test
    public void onCaptureFailed_restoresPanel() throws Exception {
        String body = methodBody(captureService(), "private void startGrab()");
        assertNotNull(body);
        int idx = body.indexOf("public void onCaptureFailed(String r)");
        assertTrue("必须实现 onCaptureFailed", idx >= 0);
        String slice = body.substring(idx);
        int next = slice.indexOf("@Override", 1);
        if (next > 0) slice = slice.substring(0, next);
        assertTrue("截帧失败必须恢复悬浮窗（否则用户以为窗口消失）",
                slice.contains("showRestore()"));
        assertTrue("截帧失败必须有提示", slice.contains("showMessage("));
    }

    @Test
    public void stringsXml_hasGrabNotReadyMessage() throws Exception {
        String xml = readFile("src/main/res/values/strings.xml");
        assertTrue(xml.contains("<string name=\"msg_grab_not_ready\">"));
    }

    @Test
    public void startCapture_dismissesPreviousPanel_noStackedOverlay() throws Exception {
        String body = methodBody(captureService(), "private void startCapture(int resultCode, Intent data)");
        assertNotNull("必须有 startCapture", body);
        int hide = body.indexOf("if (panel != null) panel.hide();");
        int create = body.indexOf("panel = new FloatingPanel(");
        assertTrue("必须撤掉旧面板（重新授权时否则会叠两个悬浮窗）", hide >= 0);
        assertTrue("必须先撤旧的再建新的", create >= 0 && hide < create);
    }

    @Test
    public void startCapture_releasesPreviousCapture_first() throws Exception {
        String body = methodBody(captureService(), "private void startCapture(int resultCode, Intent data)");
        assertNotNull(body);
        int release = body.indexOf("releaseCapture();");
        int getProjection = body.indexOf("getMediaProjection(");
        assertTrue("重新授权时必须先释放旧采集资源（含旧 VirtualDisplay）", release >= 0);
        assertTrue("释放必须发生在新投影建立之前", getProjection >= 0 && release < getProjection);
    }

    @Test
    public void initFailure_isLoggedWithReason() throws Exception {
        String body = methodBody(captureService(), "private void startCapture(int resultCode, Intent data)");
        assertNotNull(body);
        assertTrue("截屏通道初始化失败不能再静默（必须 error 日志 + 原因）",
                body.contains("Log.e(TAG") && body.contains("lastError()"));
    }

    // ── ScreenTextCapture：可建、可诊断 ─────────

    @Test
    public void screenCapture_usesRealSizeForFullScreenMirror() throws Exception {
        String src = screenCapture();
        assertTrue("必须用 getRealSize（getMetrics 会排除导航栏，导致截图与框选错位）",
                src.contains("getRealSize("));
    }

    @Test
    public void screenCapture_fallsBackOnReaderFormat_rgbaFirst() throws Exception {
        String src = screenCapture();
        int rgba = src.indexOf("PixelFormat.RGBA_8888");
        int flex = src.indexOf("ImageFormat.FLEX_RGBA_8888");
        assertTrue("必须有像素格式回退列表", rgba >= 0 && flex >= 0);
        assertTrue("探针实测的 RGBA_8888 必须优先", rgba < flex);
    }

    @Test
    public void screenCapture_callsCreateVirtualDisplayOnlyOnce() throws Exception {
        String src = screenCapture();
        int count = 0;
        int i = src.indexOf("createVirtualDisplay(");
        while (i >= 0) {
            count++;
            i = src.indexOf("createVirtualDisplay(", i + 1);
        }
        // 声明处（projection 参数类型不算）→ 只允许方法调用出现 1 次：
        // 同一 MediaProjection 第二次 createVirtualDisplay 会抛 SecurityException 并停掉整个会话
        assertEquals("createVirtualDisplay 只能出现一次（不能放在格式回退循环里）", 1, count);
    }

    @Test
    public void screenCapture_exposesLastError() throws Exception {
        String src = screenCapture();
        assertTrue("必须暴露 lastError() 供 UI 提示", src.contains("public String lastError()"));
        assertTrue("初始化失败必须记录原因", src.contains("lastError = "));
        assertTrue("会话释放后要能解释原因", methodBody(src, "public void release()").contains("lastError = "));
    }
}
