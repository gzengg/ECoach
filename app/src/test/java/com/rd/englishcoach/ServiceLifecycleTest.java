package com.rd.englishcoach;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.File;
import java.util.Scanner;

/**
 * 服务生命周期回归测试。
 *
 * <p>问题：点「停止」或悬浮窗关闭后，app 进程被杀。
 * 根因：ACTION_STOP 直接 stopSelf() + onDestroy 调 panel.hide()
 * 导致 Android 16 上前台服务销毁时进程一起死。</p>
 *
 * <p>修复：ACTION_STOP 只移除前台通知（stopForeground），
 * 不再调 stopSelf()；onDestroy 不再调 panel.hide()；
 * 只有悬浮窗关闭按钮和 MainActivity.onDestroy(isFinishing) 才停服务。</p>
 */
public class ServiceLifecycleTest {

    // ── FloatingPanel：不再使用 FLAG_LAYOUT_NO_LIMITS ────

    @Test
    public void floatingPanel_noLayoutNoLimits() throws Exception {
        String src = readFile("src/main/java/com/rd/englishcoach/FloatingPanel.java");
        assertFalse("FloatingPanel 不应使用 FLAG_LAYOUT_NO_LIMITS，"
                        + "它会导致 Android 16 上窗口超出屏幕边界被裁掉",
                src.contains("FLAG_LAYOUT_NO_LIMITS"));
    }

    // ── CaptureService：onDestroy 不调 panel.hide() ────

    @Test
    public void captureService_onDestroy_noPanelHide() throws Exception {
        String src = readFile("src/main/java/com/rd/englishcoach/CaptureService.java");
        // 找到 onDestroy 方法体（精确匹配方法签名后的大括号内容）
        int methodStart = src.indexOf("public void onDestroy()");
        assertTrue("CaptureService 必须有 onDestroy", methodStart > 0);
        int braceStart = src.indexOf('{', methodStart);
        String body = extractMethodBody(src, braceStart);
        // 去掉注释后再检查
        String stripped = stripComments(body);
        assertFalse("onDestroy 里不应调 panel.hide()（会触发 Android 16 进程终止），"
                        + "悬浮窗应由 WindowManager 在进程退出时自动清理",
                stripped.contains("panel.hide"));
    }

    // ── CaptureService：ACTION_STOP 路径不调 stopSelf ────

    @Test
    public void captureService_actionStop_doesNotCallStopSelf() throws Exception {
        String src = readFile("src/main/java/com/rd/englishcoach/CaptureService.java");
        // 找 onStartCommand 方法
        int methodStart = src.indexOf("public int onStartCommand");
        assertTrue("CaptureService 必须有 onStartCommand", methodStart > 0);
        int braceStart = src.indexOf('{', methodStart);
        String body = extractMethodBody(src, braceStart);
        // 在 ACTION_STOP 分支内检查（从 ACTION_STOP 到下一个 return）
        int stopIdx = body.indexOf("ACTION_STOP");
        assertTrue("onStartCommand 必须处理 ACTION_STOP", stopIdx > 0);
        String stopBranch = body.substring(stopIdx, Math.min(stopIdx + 400, body.length()));
        String stripped = stripComments(stopBranch);
        assertFalse("ACTION_STOP 分支不应直接调 stopSelf()（会让前台服务销毁时进程一起死），"
                        + "应改为 stopForeground 移除通知即可",
                stripped.contains("stopSelf()"));
        assertTrue("ACTION_STOP 分支应调 stopForeground 移除前台通知",
                stripped.contains("stopForeground"));
    }

    // ── MainActivity：停止按钮用 stopService ────

    @Test
    public void mainActivity_stopButton_usesStopService() throws Exception {
        String src = readFile("src/main/java/com/rd/englishcoach/MainActivity.java");
        // 找 btnStop 的 onClick
        int idx = src.indexOf("btnStop.setOnClickListener");
        assertTrue("MainActivity 必须有 btnStop 点击监听", idx > 0);
        String block = src.substring(idx, Math.min(idx + 300, src.length()));
        assertTrue("btnStop 点击应调 stopService() 直接停止服务",
                block.contains("stopService"));
    }

    // ── MainActivity：onDestroy 只在 isFinishing 时停服务 ────

    @Test
    public void mainActivity_onDestroy_checksIsFinishing() throws Exception {
        String src = readFile("src/main/java/com/rd/englishcoach/MainActivity.java");
        int idx = src.indexOf("protected void onDestroy()");
        assertTrue("MainActivity 必须有 onDestroy", idx > 0);
        String body = src.substring(idx, Math.min(idx + 500, src.length()));
        assertTrue("onDestroy 必须检查 isFinishing()，"
                        + "跳设置页时 isFinishing=false，不应停服务",
                body.contains("isFinishing()"));
        assertTrue("onDestroy 中 isFinishing 时应调 stopService",
                body.contains("stopService"));
    }

    // ── 工具 ──────────────────────────────

    /** 从开括号位置提取匹配的方法体（处理嵌套大括号）。 */
    private static String extractMethodBody(String src, int braceStart) {
        int depth = 0;
        for (int i = braceStart; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') { depth--; if (depth == 0) return src.substring(braceStart, i + 1); }
        }
        return src.substring(braceStart);
    }

    /** 去掉 // 单行注释和 /* ... *\/ 块注释（避免注释里的关键词干扰断言）。 */
    private static String stripComments(String s) {
        return s.replaceAll("//[^\n]*", "")
                .replaceAll("/\\*[\\s\\S]*?\\*/", "");
    }

    private static String readFile(String path) throws Exception {
        File f = new File(path);
        assertTrue("文件不存在: " + path, f.exists());
        StringBuilder sb = new StringBuilder();
        try (Scanner sc = new Scanner(f, "UTF-8")) {
            while (sc.hasNextLine()) sb.append(sc.nextLine()).append('\n');
        }
        return sb.toString();
    }
}
