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
