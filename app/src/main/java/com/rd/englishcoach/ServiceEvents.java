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
    public static final String EXTRA_RUNNING = "running";
    public static final String EXTRA_REASON  = "reason";

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
}
