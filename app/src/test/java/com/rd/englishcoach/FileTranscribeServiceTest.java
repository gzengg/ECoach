package com.rd.englishcoach;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;
import org.junit.Test;

/**
 * 文件转录前台服务的契约。
 *
 * <p>服务与通知都是 Android 框架对象，JVM 里实例化不了，故用源码 + manifest 契约锁住
 * 三件“坏了很难查”的事：清单里的前台服务类型/权限、取消后必须自己收尾、取消后不许再报“完成”。</p>
 */
public class FileTranscribeServiceTest {

    private static String read(String path) throws Exception {
        File f = new File(path);
        assertTrue("文件不存在: " + f.getAbsolutePath(), f.exists());
        try (Scanner s = new Scanner(f, StandardCharsets.UTF_8.name())) {
            s.useDelimiter("\\A");
            return s.hasNext() ? s.next() : "";
        }
    }

    private static String service() throws Exception {
        return read("src/main/java/com/rd/englishcoach/FileTranscribeService.java")
                .replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
    }

    // ── manifest ───────────────────────────

    @Test
    public void manifest_declaresDataSyncForegroundService() throws Exception {
        String xml = read("src/main/AndroidManifest.xml");
        assertTrue("服务必须声明为 dataSync 前台服务（转码/转写本地文件语义）",
                xml.contains(".FileTranscribeService")
                        && xml.contains("foregroundServiceType=\"dataSync\""));
        assertTrue("必须申请 FOREGROUND_SERVICE_DATA_SYNC，否则 startForeground 直接抛异常",
                xml.contains("android.permission.FOREGROUND_SERVICE_DATA_SYNC"));
        assertTrue("后台长跑必须用前台服务，不能靠普通 Service",
                xml.contains("android.permission.FOREGROUND_SERVICE"));
    }

    @Test
    public void manifest_doesNotAddStoragePermissions() throws Exception {
        String xml = read("src/main/AndroidManifest.xml");
        assertTrue("文件读取只能走 SAF，不许申请全盘存储权限",
                !xml.contains("READ_EXTERNAL_STORAGE")
                        && !xml.contains("MANAGE_EXTERNAL_STORAGE")
                        && !xml.contains("READ_MEDIA_AUDIO")
                        && !xml.contains("READ_MEDIA_VIDEO"));
    }

    // ── 取消路径（真机踩过的坑：通知挂着不走）──

    @Test
    public void cancel_stopsSelfWhenNoWorkerRunning() throws Exception {
        String src = service();
        assertTrue("取消时必须清理还没开始的记录", src.contains("cleanupPending()"));
        int branch = src.indexOf("ACTION_CANCEL.equals(intent.getAction())");
        assertTrue("必须有取消分支", branch >= 0);
        int guard = src.indexOf("if (!running)", branch);
        int stop = src.indexOf("stopSelf()", branch);
        assertTrue("队列没在跑时没人会调 finishQueue，必须在取消分支里自己 stopSelf，"
                        + "否则前台通知永远挂着（用户以为还在转）",
                guard > branch && stop > guard);
    }

    @Test
    public void cancel_doesNotPostCompletionNotification() throws Exception {
        String src = service();
        int done = src.indexOf("private void postDoneNotification");
        assertTrue("必须有完成通知", done >= 0);
        int canceledCheck = src.indexOf("if (canceled)", done);
        int build = src.indexOf("Notification.Builder", done);
        assertTrue("用户主动停止后不能再弹「转录完成」，那会让人以为是自己没停掉",
                canceledCheck > done && canceledCheck < build);
        assertTrue("完成通知的构建必须在取消拦截之后", build > canceledCheck);
    }

    // ── 通知 ──────────────────────────────

    @Test
    public void notification_hasChannelAndStopAction() throws Exception {
        String src = service();
        assertTrue("Android 8+ 通知必须有渠道", src.contains("NotificationChannel"));
        assertTrue("进度通知要能看到停止入口（ACTION_CANCEL）",
                src.contains("ACTION_CANCEL") && src.contains("addAction("));
        assertTrue("通知标题/文案一律走 strings.xml", src.contains("getString(R.string."));
    }

    @Test
    public void service_neverTouchesUiDirectly() throws Exception {
        String src = service();
        assertTrue("服务里不许直接改 UI（必须靠广播回主线程）",
                !src.contains("setStatusText") && !src.contains(".setText("));
        assertTrue("跨进程状态变化用广播通知界面", src.contains("sendBroadcast"));
    }
}
