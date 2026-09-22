package com.rd.englishcoach;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * 悬浮窗双 Tab 布局契约测试。
 * 验证 window_panel.xml 的结构约束（dragBar 在 Tab 外、inputRow 在听力页内）。
 */
public class FloatingPanelTabTest {

    @Test
    public void windowPanel_hasTabBar() throws Exception {
        String xml = readFile("src/main/res/layout/window_panel.xml");
        assertTrue("布局必须有 tabBar", xml.contains("android:id=\"@+id/tabBar\""));
        assertTrue("布局必须有 btnTabListen", xml.contains("android:id=\"@+id/btnTabListen\""));
        assertTrue("布局必须有 btnTabGrab", xml.contains("android:id=\"@+id/btnTabGrab\""));
    }

    @Test
    public void windowPanel_hasTabListening() throws Exception {
        String xml = readFile("src/main/res/layout/window_panel.xml");
        assertTrue("布局必须有 tabListening", xml.contains("android:id=\"@+id/tabListening\""));
        // inputRow 的 id 声明必须在 tabListening 的 id 声明之后
        int listenIdIdx = xml.indexOf("@+id/tabListening");
        int inputIdIdx = xml.indexOf("@+id/inputRow");
        assertTrue("inputRow 必须在 tabListening 之后", inputIdIdx > listenIdIdx);
    }

    @Test
    public void windowPanel_hasTabGrab() throws Exception {
        String xml = readFile("src/main/res/layout/window_panel.xml");
        assertTrue("布局必须有 tabGrab", xml.contains("android:id=\"@+id/tabGrab\""));
        assertTrue("布局必须有 btnGrabStart", xml.contains("android:id=\"@+id/btnGrabStart\""));
        assertTrue("布局必须有 tvGrabStatus", xml.contains("android:id=\"@+id/tvGrabStatus\""));
        assertTrue("布局必须有 scrollGrabs", xml.contains("android:id=\"@+id/scrollGrabs\""));
        assertTrue("布局必须有 grabList", xml.contains("android:id=\"@+id/grabList\""));
    }

    @Test
    public void windowPanel_dragBarBeforeTabBar() throws Exception {
        String xml = readFile("src/main/res/layout/window_panel.xml");
        int dragIdx = xml.indexOf("android:id=\"@+id/dragBar\"");
        int tabIdx = xml.indexOf("android:id=\"@+id/tabBar\"");
        assertTrue("dragBar 必须在 tabBar 之前", dragIdx < tabIdx);
    }

    @Test
    public void windowPanel_tabGrabHiddenByDefault() throws Exception {
        String xml = readFile("src/main/res/layout/window_panel.xml");
        // tabGrab 的 visibility 应该是 gone
        int grabIdx = xml.indexOf("android:id=\"@+id/tabGrab\"");
        String grabSection = xml.substring(grabIdx, Math.min(grabIdx + 300, xml.length()));
        assertTrue("tabGrab 默认必须隐藏",
                grabSection.contains("android:visibility=\"gone\""));
    }

    @Test
    public void floatingPanel_hasTabSwitchMethod() throws Exception {
        String src = readFile("src/main/java/com/rd/englishcoach/FloatingPanel.java");
        assertTrue("FloatingPanel 必须有 switchToTab 方法",
                src.contains("switchToTab"));
        assertTrue("FloatingPanel 必须有 getCurrentTab 方法",
                src.contains("getCurrentTab"));
    }

    // ── 两个 Tab 的主按钮风格统一（用户反馈 UI 不一致） ──

    @Test
    public void applyFontSize_scalesBothTabCtas() throws Exception {
        String src = readFile("src/main/java/com/rd/englishcoach/FloatingPanel.java");
        int start = src.indexOf("public void applyFontSize(int sp)");
        assertTrue("必须有 applyFontSize", start > 0);
        int brace = src.indexOf('{', start);
        int depth = 0;
        int end = brace;
        for (int i = brace; i < src.length(); i++) {
            if (src.charAt(i) == '{') depth++;
            else if (src.charAt(i) == '}') {
                depth--;
                if (depth == 0) { end = i; break; }
            }
        }
        String body = src.substring(brace, end);
        assertTrue("听力页主按钮（继续）字号要跟随设置", body.contains("btnPause.setTextSize"));
        assertTrue("取词页主按钮（取词）字号必须与它一致，否则两个 Tab 风格仍不统一",
                body.contains("btnGrabStart.setTextSize"));
        assertTrue("两个主按钮必须用同一个字号变量", body.contains("ctaSp"));
    }

    @Test
    public void switchToTab_togglesPrimaryButtons() throws Exception {
        String src = readFile("src/main/java/com/rd/englishcoach/FloatingPanel.java");
        int start = src.indexOf("private void switchToTab(int tab)");
        assertTrue("必须有 switchToTab", start > 0);
        int brace = src.indexOf('{', start);
        int depth = 0;
        int end = brace;
        for (int i = brace; i < src.length(); i++) {
            if (src.charAt(i) == '{') depth++;
            else if (src.charAt(i) == '}') {
                depth--;
                if (depth == 0) { end = i; break; }
            }
        }
        String body = src.substring(brace, end);
        assertTrue("切 Tab 必须切「继续」按钮的可见性（与取词按钮共用同一位置）",
                body.contains("btnPause.setVisibility"));
        assertTrue("切 Tab 必须切「取词」按钮的可见性",
                body.contains("btnGrabStart.setVisibility"));
    }

    // ── 工具 ──────────────────────────────

    private static String readFile(String path) throws Exception {
        java.io.File f = new java.io.File(path);
        assertTrue("文件不存在: " + path, f.exists());
        StringBuilder sb = new StringBuilder();
        try (java.util.Scanner sc = new java.util.Scanner(f, "UTF-8")) {
            while (sc.hasNextLine()) sb.append(sc.nextLine()).append('\n');
        }
        return sb.toString();
    }
}
