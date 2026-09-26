package com.rd.englishcoach;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.Scanner;
import org.junit.Test;

/**
 * 设置保存流程的源码契约测试（真机踩过的两个问题回归）：
 * <ol>
 *   <li>填完 API Key 点保存，键盘不消失——键盘挡住下半页，用户看不到保存结果；</li>
 *   <li>保存后监听页仍挂着「未配 API Key」引导——用户以为没保存成功。</li>
 * </ol>
 *
 * <p>这类问题无法在 JVM 上实例化 Activity 验证，所以按本仓库惯例用源码契约测试钉住。</p>
 */
public class SettingsSaveFlowTest {

    @Test
    public void save_hidesKeyboardAndRefreshesKeyState() throws Exception {
        String src = readFile("src/main/java/com/rd/englishcoach/SettingsPage.java");
        String save = methodBody(src, "private void save()");
        assertNotNull("SettingsPage 里找不到 save()", save);
        assertTrue("保存后必须收起键盘（否则挡住下半页）", save.contains("hideKeyboard()"));
        assertTrue("保存后必须刷新 API Key 检测状态", save.contains("onConfigSaved()"));
    }

    @Test
    public void blankTap_dismissesKeyboard_butTapInsideInputKeepsIt() throws Exception {
        String src = readFile("src/main/java/com/rd/englishcoach/MainActivity.java");
        assertTrue("必须在 Activity 层拦触摸：挂在某个 View 上会被子 View 先吃掉事件",
                src.contains("dispatchTouchEvent(MotionEvent"));
        assertTrue("点空白要收起键盘", src.contains("hideKeyboard()"));
        assertTrue("点在输入框里要保留键盘（否则刚点上去就被收走）",
                src.contains("hitsAnyEditText"));
        assertFalse("不要用「只在 content 上挂监听」的写法（收不到子 View 区域）",
                src.contains("content.setOnTouchListener"));
    }

    @Test
    public void onConfigSaved_rechecksApiKeyState() throws Exception {
        String src = readFile("src/main/java/com/rd/englishcoach/MainActivity.java");
        String body = methodBody(src, "void onConfigSaved()");
        assertNotNull("找不到 onConfigSaved()", body);
        assertTrue("必须重跑 updateStatus()（它负责「未配 API Key」引导的显隐）",
                body.contains("updateStatus()"));
    }

    private static String readFile(String path) throws Exception {
        File f = new File(path);
        assertTrue("文件不存在: " + f.getAbsolutePath(), f.exists());
        try (Scanner s = new Scanner(f, "UTF-8")) {
            s.useDelimiter("\\A");
            return s.hasNext() ? s.next() : "";
        }
    }

    /** 取出签名对应的方法体（按花括号配对）。 */
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
                if (depth == 0) return src.substring(brace, i + 1);
            }
        }
        return null;
    }
}
