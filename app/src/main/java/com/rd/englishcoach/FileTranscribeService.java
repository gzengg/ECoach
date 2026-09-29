package com.rd.englishcoach;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 本地音频/视频文件转录的前台服务：多选文件串行识别，可后台继续。
 *
 * <p><b>为什么必须是前台服务：</b>一小时文件按 RTF 0.5 也要跑半小时，
 * 普通后台线程会被系统随时冻结/杀掉，用户切出去就白跑。前台服务类型用
 * {@code dataSync}（API 29 起就有；本地文件 I/O + 可能联网识别），
 * 不用 {@code mediaProcessing}：后者 API 35 才存在，在 34 上拿不到该类型。</p>
 *
 * <p><b>为什么与实时监听互斥：</b>两边都要加载离线识别模型（native 常驻
 * 上百 MB ~ 1GB），同时跑等于把模型载两份，容易被系统整体杀掉。
 * UI 侧先检查 {@link CaptureService#current}（监听服务已停 = 模型已释放）。</p>
 *
 * <p><b>为什么串行（一次一个文件）：</b>ASR 已用 4 线程跑满核，并发只会互相抢核、
 * 放大峰值内存；串行还能让「进度 / 取消 / 断点」的语义保持单一。</p>
 *
 * <p>进度与结果都写 {@link FileTranscriptStore}（每段就存、节流 2 秒），
 * 所以进程被杀后还能「继续」——源文件 URI 权限与全部已完成的分段都在。</p>
 */
public class FileTranscribeService extends Service {

    private static final String TAG = "FileTranscribeService";

    public static final String ACTION_START = "com.rd.englishcoach.FILE_START";
    /** 通知栏「停止」= 停止整队（当前文件的已完成分段保留，尚未开始的记录直接删掉）。 */
    public static final String ACTION_CANCEL = "com.rd.englishcoach.FILE_CANCEL";

    public static final String EXTRA_URIS = "uris";
    public static final String EXTRA_NAMES = "names";
    public static final String EXTRA_SIZES = "sizes";
    /** 从「历史 → 文件」点「继续」时只续转这一条。 */
    public static final String EXTRA_RESUME_ID = "resume_id";

    private static final String CH_ID = "file_transcribe";
    /** 与 CaptureService 的 1 区分开，避免两条通知互相覆盖。 */
    private static final int NOTIF_ID = 2;
    /** 进度广播节流：状态行按秒刷新足够，别按块刷（一小时约 120 块）。 */
    private static final long PROGRESS_NOTIFY_MS = 1000;

    /** 是否有队列在跑（主界面入口卡片据此显示状态）。 */
    public static volatile boolean running;

    static final class Task {
        final String uri;
        final String name;
        final long size;
        /** 对应 {@link FileTranscriptStore} 里的记录 id（入队时就建好，续转时为原 id）。 */
        String entryId;
        /** 非空 = 续转已存在的记录。 */
        final String resumeId;

        Task(String uri, String name, long size, String resumeId) {
            this.uri = uri;
            this.name = name;
            this.size = size;
            this.resumeId = resumeId;
        }
    }

    private final ArrayDeque<Task> queue = new ArrayDeque<>();
    private final Object lock = new Object();
    private volatile Task current;
    private volatile boolean canceled;

    private FileTranscriptStore store;
    private ExecutorService exec;
    private Handler main;
    private AsrChain chain;
    private int doneCount;
    private int failCount;
    private long lastNotifyMs;

    @Override
    public void onCreate() {
        super.onCreate();
        store = new FileTranscriptStore(this);
        main = new Handler(Looper.getMainLooper());
        exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "file-transcribe");
            t.setPriority(Thread.NORM_PRIORITY - 1);   // 别和 UI 抢前端线程
            return t;
        });
        createChannel();
        chain = new AsrChain(this);
        // 上次进程被杀留下的「正在处理」其实已经断了，改成「已中断」（可继续）
        for (FileTranscriptStore.Entry e : store.getAll()) {
            if (FileTranscriptStore.STATUS_RUNNING.equals(e.status)
                    || FileTranscriptStore.STATUS_PENDING.equals(e.status)) {
                e.status = FileTranscriptStore.STATUS_INTERRUPTED;
                store.saveNow(e);
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIF_ID, buildNotification(null, 0),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);

        if (intent == null || ACTION_CANCEL.equals(intent.getAction())) {
            canceled = true;
            synchronized (lock) {
                queue.clear();
            }
            Log.i(TAG, "收到取消：停止整队");
            // 队列没在跑时没人会调 finishQueue（worker 已退出）→ 自己收尾，
            // 否则前台通知会一直挂着，用户以为还在转
            if (!running) {
                cleanupPending();
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            }
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(intent.getAction())) return START_NOT_STICKY;

        List<Task> tasks = parseTasks(intent);
        if (tasks.isEmpty()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        List<Task> fresh = new ArrayList<>();
        synchronized (lock) {
            for (Task t : tasks) {
                if (t.resumeId != null) queue.addFirst(t);
                else {
                    queue.addLast(t);
                    fresh.add(t);   // 待会补一条「排队中」记录，让用户立刻看到队列
                }
            }
        }
        for (Task t : fresh) t.entryId = markPending(t);
        notifyProgress(false);

        // 已经在跑就不用再起一条 worker：它会把新入队的文件接着转完
        if (!running) {
            running = true;
            exec.execute(this::runQueue);
        }
        return START_NOT_STICKY;
    }

    private List<Task> parseTasks(Intent intent) {
        List<Task> out = new ArrayList<>();
        String resumeId = intent.getStringExtra(EXTRA_RESUME_ID);
        if (resumeId != null) {
            FileTranscriptStore.Entry e = store.find(resumeId);
            if (e == null) {
                Log.w(TAG, "要续转的记录已不存在: " + resumeId);
                return out;
            }
            Task t = new Task(e.sourceUri, e.fileName, e.sizeBytes, e.id);
            t.entryId = e.id;
            out.add(t);
            return out;
        }
        ArrayList<String> uris = intent.getStringArrayListExtra(EXTRA_URIS);
        if (uris == null) return out;
        ArrayList<String> names = intent.getStringArrayListExtra(EXTRA_NAMES);
        List<Long> sizes = parseSizes(intent);
        // 同一个文件连点两次会给两条任务，去重免得它们写同一条记录
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < uris.size(); i++) {
            String uri = uris.get(i);
            if (uri == null || !seen.add(uri)) continue;
            String name = names != null && i < names.size() ? names.get(i) : uri;
            out.add(new Task(uri, name, sizes.get(i), null));
        }
        return out;
    }

    private static List<Long> parseSizes(Intent intent) {
        ArrayList<String> raw = intent.getStringArrayListExtra(EXTRA_SIZES);
        ArrayList<Long> out = new ArrayList<>();
        int n = intent.getStringArrayListExtra(EXTRA_URIS) == null ? 0
                : intent.getStringArrayListExtra(EXTRA_URIS).size();
        for (int i = 0; i < n; i++) {
            long v = 0;
            if (raw != null && i < raw.size()) {
                try {
                    v = Long.parseLong(raw.get(i));
                } catch (Exception ignored) {
                    v = 0;
                }
            }
            out.add(v);
        }
        return out;
    }

    /** 入队的文件先在历史里落一条「排队中」，返回其 id。 */
    private String markPending(Task t) {
        FileTranscriptStore.Entry e =
                new FileTranscriptStore.Entry(newId(), t.name, t.uri, 0, t.size);
        e.status = FileTranscriptStore.STATUS_PENDING;
        store.saveNow(e);
        return e.id;
    }

    private static String newId() {
        return System.currentTimeMillis() + "-" + (int) (Math.random() * 100000);
    }

    // ── 队列执行 ──────────────────────────

    private void runQueue() {
        while (true) {
            Task t;
            synchronized (lock) {
                t = canceled ? null : queue.poll();
                current = t;
            }
            if (t == null) break;
            process(t);
            if (canceled) break;
        }
        finishQueue();
    }

    private void process(Task task) {
        FileTranscriptStore.Entry found = task.entryId != null ? store.find(task.entryId) : null;
        final FileTranscriptStore.Entry live = found != null ? found
                : new FileTranscriptStore.Entry(newId(), task.name, task.uri, 0, task.size);
        // 续转：已有时长/分段都要留着，只把状态推回「进行中」
        final boolean resumed = !live.segments.isEmpty() && live.progressMs > 0;
        live.status = FileTranscriptStore.STATUS_RUNNING;
        live.error = null;
        if (!resumed) live.srtAvailable = true;
        store.saveNow(live);
        notifyProgress(false);

        MediaAudioReader reader = null;
        try {
            reader = new MediaAudioReader(this, Uri.parse(live.sourceUri));
            if (reader.durationMs() > 0) live.durationMs = reader.durationMs();
            store.save(live);
            if (resumed) reader.seekTo(live.progressMs);

            FileAsrPipeline.Result r = FileAsrPipeline.run(reader, chain,
                    new FileAsrPipeline.Sink() {
                @Override
                public void onStart(long originMs) {
                    if (!resumed) return;
                    // 续转落点由容器决定（一般回到前一个关键帧）：落点之后的分段一律丢掉重认，
                    // 否则同一段文字会在结果里出现两次。落点之前的保持不动。
                    int keep = live.segments.size();
                    for (int i = 0; i < live.segments.size(); i++) {
                        if (live.segments.get(i).startMs >= originMs) {
                            keep = i;
                            break;
                        }
                    }
                    while (live.segments.size() > keep) {
                        live.segments.remove(live.segments.size() - 1);
                    }
                    live.progressMs = originMs;
                    store.saveNow(live);
                }

                @Override
                public void onSegment(long startMs, long endMs, String text, boolean offline) {
                    live.segments.add(new FileTranscriptStore.Segment(startMs, endMs, text));
                    live.progressMs = endMs;
                    // 只要有一块走了在线（拿不到时间轴），整条记录就不提供字幕导出
                    live.srtAvailable = live.srtAvailable && offline;
                    live.engine = getString(offline ? R.string.engine_offline : R.string.engine_online);
                    store.save(live);   // 节流落盘：崩溃最多丢 2 秒
                    notifyProgress(false);
                }

                @Override
                public void onProgress(long positionMs, long durationMs) {
                    if (positionMs > live.progressMs) live.progressMs = positionMs;
                    notifyProgress(false);
                }

                @Override
                public boolean isCanceled() {
                    return canceled;
                }
            });

            if (canceled) {
                live.status = FileTranscriptStore.STATUS_CANCELED;
            } else {
                live.status = FileTranscriptStore.STATUS_DONE;
                live.progressMs = Math.max(r.recognizedMs, live.durationMs);
            }
            if (r.failedBlocks > 0) {
                Log.w(TAG, live.fileName + "：有 " + r.failedBlocks + " 块识别失败（其余结果已保留）");
            }
            doneCount++;
        } catch (MediaAudioReader.MediaException e) {
            live.status = FileTranscriptStore.STATUS_FAILED;
            live.error = getString(MediaAudioReader.errorRes(e.code));
            failCount++;
            Log.w(TAG, "解码失败: " + live.fileName, e);
        } catch (Exception e) {
            if (canceled) {
                live.status = FileTranscriptStore.STATUS_CANCELED;
            } else {
                live.status = FileTranscriptStore.STATUS_FAILED;
                live.error = e.getMessage() != null ? e.getMessage()
                        : getString(R.string.msg_asr_no_engine);
                failCount++;
            }
            Log.w(TAG, "识别失败: " + live.fileName, e);
        } finally {
            if (reader != null) reader.close();
            store.saveNow(live);
            broadcast(true);
        }
    }

    private void finishQueue() {
        synchronized (lock) {
            queue.clear();
            current = null;
        }
        running = false;
        if (canceled) cleanupPending();
        broadcast(true);
        main.post(() -> postDoneNotification(doneCount, failCount));
    }

    /** 用户主动停止时，还没开始的记录直接删掉（空记录只会脏了历史）。 */
    private void cleanupPending() {
        for (FileTranscriptStore.Entry e : store.getAll()) {
            if (FileTranscriptStore.STATUS_PENDING.equals(e.status)) store.delete(e.id);
        }
    }

    /**
     * Android 15（API 35）起前台服务有 6 小时上限，超时会回调这里。
     * 把中间态改成「已中断」（可继续），别让历史里一直挂着「转录中」。
     */
    @Override
    public void onTimeout(int startId, int fgsType) {
        current = null;
        running = false;
        for (FileTranscriptStore.Entry e : store.getAll()) {
            if (FileTranscriptStore.STATUS_RUNNING.equals(e.status)
                    || FileTranscriptStore.STATUS_PENDING.equals(e.status)) {
                e.status = FileTranscriptStore.STATUS_INTERRUPTED;
                store.saveNow(e);
            }
        }
        broadcast(true);
        Log.w(TAG, "前台服务超时，已标记为「已中断」");
        stopSelf();
    }

    @Override
    public void onDestroy() {
        canceled = true;
        running = false;
        if (chain != null) {
            chain.release();   // 释放离线识别模型占的 native 内存
            chain = null;
        }
        if (exec != null) exec.shutdownNow();
        if (store != null) store.flush();
        Log.i(TAG, "onDestroy");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ── 通知 ──────────────────────────────

    private void createChannel() {
        NotificationChannel ch = new NotificationChannel(CH_ID,
                getString(R.string.file_notif_channel), NotificationManager.IMPORTANCE_LOW);
        ch.setDescription(getString(R.string.file_notif_channel_desc));
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    /** 更新前台通知 + 广播状态；按 {@link #PROGRESS_NOTIFY_MS} 节流。 */
    private void notifyProgress(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastNotifyMs < PROGRESS_NOTIFY_MS) return;
        lastNotifyMs = now;
        broadcast(false);
        main.post(() -> getSystemService(NotificationManager.class)
                .notify(NOTIF_ID, buildNotification(currentEntry(), queueSize())));
    }

    private FileTranscriptStore.Entry currentEntry() {
        Task t = current;
        return t == null || t.entryId == null ? null : store.find(t.entryId);
    }

    private int queueSize() {
        synchronized (lock) {
            return queue.size();
        }
    }

    private Notification buildNotification(FileTranscriptStore.Entry e, int queued) {
        Intent stop = new Intent(this, FileTranscribeService.class).setAction(ACTION_CANCEL);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent openPi = openHistoryIntent();

        String title = e != null
                ? getString(R.string.file_notif_title, e.fileName)
                : getString(R.string.file_notif_waiting, Math.max(queued, 1));
        Notification.Builder b = new Notification.Builder(this, CH_ID)
                .setSmallIcon(R.drawable.ic_stat_mic)
                .setContentTitle(title)
                .setContentText(e != null
                        ? getString(R.string.file_notif_chars, e.charCount()) : "")
                .setContentIntent(openPi)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null,
                        getString(R.string.file_notif_action_stop), stopPi).build());
        if (e != null && e.durationMs > 0) {
            b.setProgress(100, Math.round(e.progress() * 100), false);
        } else {
            b.setProgress(0, 0, true);
        }
        return b.build();
    }

    /** 队列跑完：换成可划走的「已完成」通知，并脱离前台状态。 */
    private void postDoneNotification(int done, int failed) {
        if (done == 0 && failed == 0) {
            stopSelf();
            return;
        }
        if (canceled) {
            // 用户自己按的「停止」：再弹一条「转录完成」只会让人困惑
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return;
        }
        String text = failed > 0
                ? getString(R.string.file_notif_failed_text, done, failed)
                : getString(R.string.file_notif_done_text, done);
        Notification n = new Notification.Builder(this, CH_ID)
                .setSmallIcon(R.drawable.ic_stat_mic)
                .setContentTitle(getString(R.string.file_notif_done_title))
                .setContentText(text)
                .setContentIntent(openHistoryIntent())
                .setAutoCancel(true)
                .build();
        getSystemService(NotificationManager.class).notify(NOTIF_ID, n);
        stopForeground(STOP_FOREGROUND_DETACH);   // 保留完成通知，但不再占前台服务
        stopSelf();
    }

    private PendingIntent openHistoryIntent() {
        Intent open = new Intent(this, MainActivity.class)
                .putExtra(MainActivity.EXTRA_TAB, BottomBar.TAB_HISTORY)
                .putExtra(HistoryPage.EXTRA_FILE_TAB, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, 2, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    // ── 与 UI 通信 ────────────────────────

    /**
     * 广播进度：主界面用它刷新入口卡片状态行，历史页用它刷新列表
     * （服务与 Activity 同进程，用包内广播即可）。
     */
    private void broadcast(boolean taskDone) {
        FileTranscriptStore.Entry e = currentEntry();
        String text = null;
        if (e != null) {
            text = getString(R.string.file_status_running, e.fileName,
                    Math.round(e.progress() * 100));
        } else if (!taskDone) {
            int q = queueSize();
            if (q > 0) text = getString(R.string.file_status_pending, q);
        }
        Context ctx = getApplicationContext();
        ctx.sendBroadcast(ServiceEvents.buildFileProgressBroadcast(
                ctx, running, text, doneCount, failCount, taskDone));
    }

    // ── 对外启动入口（UI 只走这三个） ──────

    /** 入队若干文件；uri / name / size 三个列表一一对应。 */
    public static void enqueue(Context ctx, List<String> uris, List<String> names, List<Long> sizes) {
        Intent i = new Intent(ctx, FileTranscribeService.class).setAction(ACTION_START)
                .putStringArrayListExtra(EXTRA_URIS, new ArrayList<>(uris))
                .putStringArrayListExtra(EXTRA_NAMES, new ArrayList<>(names));
        ArrayList<String> sz = new ArrayList<>(sizes.size());
        for (Long s : sizes) sz.add(String.valueOf(s == null ? 0L : s));
        i.putStringArrayListExtra(EXTRA_SIZES, sz);
        ctx.startForegroundService(i);
    }

    /** 继续一条未完成的记录。 */
    public static void resume(Context ctx, String entryId) {
        Intent i = new Intent(ctx, FileTranscribeService.class).setAction(ACTION_START)
                .putExtra(EXTRA_RESUME_ID, entryId);
        ctx.startForegroundService(i);
    }

    /** 停止整队（与通知栏「停止」行为一致）。 */
    public static void cancel(Context ctx) {
        Intent i = new Intent(ctx, FileTranscribeService.class).setAction(ACTION_CANCEL);
        ctx.startForegroundService(i);
    }
}
