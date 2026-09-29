package com.rd.englishcoach;

import android.content.Intent;

/**
 * 服务起停广播约定。
 * <p>
 * 服务在四个时机发广播：
 * <ul>
 *   <li>{@code startCapture()} 末尾 — running=true</li>
 *   <li>{@code onDestroy()} — running=false, reason=stopped</li>
 *   <li>{@code ProjectionCallback.onStop()} — running=false, reason=projection_killed</li>
 *   <li>{@code ACTION_STOP}（通知栏）— 先藏窗口再 stopSelf，随后 onDestroy 也会广播</li>
 * </ul>
 * <p>
 * {@link android.os.Build.VERSION_CODES#TIRAMISU API 33+} 注册时必须带
 * {@code RECEIVER_NOT_EXPORTED}（已在 MainActivity 中处理）。
 */
public final class ServiceEvents {

    private ServiceEvents() {}

    public static final String ACTION_STATE_CHANGED = "com.rd.englishcoach.STATE_CHANGED";
    /** 历史有新记录（转录/取词/答案回填）→ 主界面「历史」Tab 亮角标。 */
    public static final String ACTION_HISTORY_CHANGED = "com.rd.englishcoach.HISTORY_CHANGED";
    /** 文件转录进度 / 状态变化（主界面入口卡片 + 历史页列表）。 */
    public static final String ACTION_FILE_PROGRESS = "com.rd.englishcoach.FILE_PROGRESS";
    public static final String EXTRA_RUNNING = "running";
    public static final String EXTRA_REASON  = "reason";
    /** 入口卡片状态行文案（已本地化，可空 = 没有在跑的任务）。 */
    public static final String EXTRA_TEXT = "text";
    public static final String EXTRA_DONE = "done";
    public static final String EXTRA_FAILED = "failed";
    /** 是否刚有一个文件结束（历史列表据此重建，不必每秒重建）。 */
    public static final String EXTRA_TASK_DONE = "task_done";

    // reason 常量
    public static final String REASON_STARTED          = "started";
    public static final String REASON_STOPPED          = "stopped";
    public static final String REASON_PROJECTION_KILLED = "projection_killed";
    public static final String REASON_NOTIFICATION_STOP = "notification_stop";

    /** 构造一条服务状态广播。 */
    public static Intent buildStateBroadcast(android.content.Context ctx, boolean running, String reason) {
        return new Intent(ACTION_STATE_CHANGED)
                .putExtra(EXTRA_RUNNING, running)
                .putExtra(EXTRA_REASON, reason)
                .setPackage(ctx.getPackageName());
    }

    /** 构造一条「历史有新记录」广播。 */
    public static Intent buildHistoryBroadcast(android.content.Context ctx) {
        return new Intent(ACTION_HISTORY_CHANGED).setPackage(ctx.getPackageName());
    }

    /** 构造一条文件转录进度广播。 */
    public static Intent buildFileProgressBroadcast(android.content.Context ctx, boolean running,
            String text, int done, int failed, boolean taskDone) {
        return new Intent(ACTION_FILE_PROGRESS)
                .putExtra(EXTRA_RUNNING, running)
                .putExtra(EXTRA_TEXT, text)
                .putExtra(EXTRA_DONE, done)
                .putExtra(EXTRA_FAILED, failed)
                .putExtra(EXTRA_TASK_DONE, taskDone)
                .setPackage(ctx.getPackageName());
    }
}
