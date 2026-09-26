package com.rd.englishcoach;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.Scanner;
import org.junit.Test;

/**
 * 识别预热（warmup）的源码契约测试。
 *
 * <p>为什么要有：模型是懒加载的，首次识别要现加载（1~3 秒），用户说完第一句还得干等。
 * 做法是「开始监听即后台加载」（参照实现白云歌 BaiYunGe 的 warmup）。
 * 这里钉住三件事，防止后续重构把预热悄悄弄丢或弄坏：</p>
 * <ol>
 *   <li>服务在启动时就发起预热，并且<b>只在服务实例没被换掉时</b>执行；</li>
 *   <li>显式「仅在线」模式<b>不预热</b>（否则白加载几百 MB 离线模型）；</li>
 *   <li>预热失败不影响正常识别（只记日志）。</li>
 * </ol>
 */
public class AsrWarmupTest {

    @Test
    public void service_warmsUpAsrOnStart() throws Exception {
        String src = readFile("src/main/java/com/rd/englishcoach/CaptureService.java");
        assertTrue("服务启动必须发起识别预热", src.contains("chain.warmUp()"));
        assertTrue("预热要放在后台线程，不能阻塞服务启动",
                src.contains("networkExec.execute(() -> {"));
        assertTrue("服务被销毁/重建后不应再预热（免得白加载一次模型）",
                src.contains("if (asrChain == chain)"));
    }

    @Test
    public void chain_skipsWarmupWhenOnlineOnly() throws Exception {
        String body = methodBody(
                readFile("src/main/java/com/rd/englishcoach/AsrChain.java"), "void warmUp()");
        assertNotNull("AsrChain 里找不到 warmUp()", body);
        assertTrue("仅在线模式必须跳过预热", body.contains("Prefs.MODE_ONLINE"));
        assertTrue("预热要落到离线引擎上", body.contains("preload()"));
    }

    @Test
    public void preload_loadsOnlyAndSwallowsFailure() throws Exception {
        String body = methodBody(
                readFile("src/main/java/com/rd/englishcoach/OfflineAsrEngine.java"), "void preload()");
        assertNotNull("OfflineAsrEngine 里找不到 preload()", body);
        assertTrue("没装离线模型时要静默返回", body.contains("spec == null"));
        assertTrue("必须真的加载识别器（只是读配置不算预热）", body.contains("recognizerFor(spec)"));
        assertTrue("预热失败只记日志，不许抛给用户", body.contains("catch (Exception"));
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
