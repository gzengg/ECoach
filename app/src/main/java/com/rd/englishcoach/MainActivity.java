package com.rd.englishcoach;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

/**
 * 主界面 v4.1：单 Activity + 四个页面 View 显隐切换（监听 / 历史 / 模型 / 设置），
 * 底部导航栏导航。容器模式照抄 {@code FloatingPanel.switchToTab}——同一时刻只显示一个页面。
 *
 * <p><b>页面拆分（一页一件事）：</b>监听控制留在本类（含权限引导全流程）；
 * 历史 / 模型 / 设置分别委托给 {@link HistoryPage} / {@link ModelsPage} / {@link SettingsPage}，
 * 各页面互不知道彼此存在。</p>
 *
 * <p><b>导航随上下文动态变化：</b>顶栏动作随页面切换（历史页=清空全部、模型页=下载源、
 * 其余隐藏）；角标：监听中 → 「监听」Tab 呼吸点，历史有新记录（{@code ACTION_HISTORY_CHANGED}）
 * → 「历史」Tab 点，识别模型缺失 → 「模型」Tab warn 点。</p>
 *
 * <p><b>操作按钮动态显隐：</b>「停止」「清空上下文」只在监听中显示；
 * 未配 API Key 时显示去「设置」Tab 的引导提示。</p>
 */
public class MainActivity extends Activity {

    private static final int REQ_RECORD  = 100;
    private static final int REQ_OVERLAY = 101;
    private static final int REQ_NOTIFY  = 102;
    private static final int REQ_PROJECTION = 103;

    private TextView tvStatus;
    private View dotStatus;
    private Button btnStart;
    private View btnStop, btnNewChat, tvListenHint;

    private BottomBar bottomBar;
    private TextView tvTopTitle, btnTopAction;
    private HistoryPage historyPage;
    private ModelsPage modelsPage;
    private SettingsPage settingsPage;
    /** 顶栏动作当前归属页面（btnTopAction 点击时分发）。 */
    private int topAction = BottomBar.TAB_LISTEN;
    /** 历史有未看过的新记录（ACTION_HISTORY_CHANGED 置位，进历史页清除）。 */
    private boolean historyDirty = false;

    private Prefs prefs;
    private BroadcastReceiver serviceReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = new Prefs(this);

        tvStatus  = findViewById(R.id.tvStatus);
        dotStatus = findViewById(R.id.dotStatus);
        btnStart  = findViewById(R.id.btnStart);
        btnStop   = findViewById(R.id.btnStop);
        btnNewChat = findViewById(R.id.btnNewChat);
        tvListenHint = findViewById(R.id.tvListenHint);

        bottomBar = findViewById(R.id.bottomBar);
        tvTopTitle = findViewById(R.id.tvTopTitle);
        btnTopAction = findViewById(R.id.btnTopAction);

        historyPage = new HistoryPage(this, findViewById(R.id.pageHistory));
        modelsPage = new ModelsPage(this, findViewById(R.id.pageModels));
        settingsPage = new SettingsPage(this, findViewById(R.id.pageSettings));

        // 主 CTA：走权限检查 → 投屏授权 → 起服务
        btnStart.setOnClickListener(v -> checkAndStart());
        btnStop.setOnClickListener(v -> {
            stopService(new Intent(this, CaptureService.class));
            updateStatus();
        });
        btnNewChat.setOnClickListener(v -> {
            if (CaptureService.current != null) {
                CaptureService.current.clearHistory();
                tvStatus.setText(R.string.status_chat_cleared);
            } else {
                tvStatus.setText(R.string.status_service_off);
            }
        });
        // 未配 API Key 的引导提示 → 跳「设置」Tab
        tvListenHint.setOnClickListener(v -> switchTab(BottomBar.TAB_SETTINGS));
        // 顶栏上下文动作：历史页=清空全部，模型页=下载源
        btnTopAction.setOnClickListener(v -> {
            if (topAction == BottomBar.TAB_HISTORY) {
                historyPage.confirmClearAll();
            } else if (topAction == BottomBar.TAB_MODELS) {
                modelsPage.showSourceSheet();
            }
        });

        bottomBar.setListener(this::switchTab);

        // 处理重新授权请求
        if (getIntent().getBooleanExtra("reconsent", false)) {
            getIntent().removeExtra("reconsent");
            requestProjection();
        }

        // 实时监听服务状态广播 + 历史变更（角标）
        serviceReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                if (ServiceEvents.ACTION_STATE_CHANGED.equals(intent.getAction())) {
                    boolean running = intent.getBooleanExtra(ServiceEvents.EXTRA_RUNNING, false);
                    updateStatus(running);
                } else if (ServiceEvents.ACTION_HISTORY_CHANGED.equals(intent.getAction())) {
                    // 不在历史页才亮角标；在历史页则直接刷新列表
                    if (bottomBar.selected() == BottomBar.TAB_HISTORY) {
                        historyPage.refresh();
                    } else {
                        historyDirty = true;
                        bottomBar.setBadge(BottomBar.TAB_HISTORY, true);
                    }
                }
            }
        };

        switchTab(BottomBar.TAB_LISTEN);
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter(ServiceEvents.ACTION_STATE_CHANGED);
        filter.addAction(ServiceEvents.ACTION_HISTORY_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(serviceReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(serviceReceiver, filter);
        }
        updateStatus(CaptureService.current != null); // 兜底：防止广播丢失
        historyPage.refresh();
        modelsPage.refresh();
        settingsPage.refresh();
    }

    @Override
    protected void onPause() {
        if (serviceReceiver != null) {
            try { unregisterReceiver(serviceReceiver); } catch (Exception ignored) {}
        }
        super.onPause();
    }

    // ── Tab 切换（导航随页面上下文变化） ──────────────────

    void switchTab(int tab) {
        findViewById(R.id.pageListen).setVisibility(
                tab == BottomBar.TAB_LISTEN ? View.VISIBLE : View.GONE);
        findViewById(R.id.pageHistory).setVisibility(
                tab == BottomBar.TAB_HISTORY ? View.VISIBLE : View.GONE);
        findViewById(R.id.pageModels).setVisibility(
                tab == BottomBar.TAB_MODELS ? View.VISIBLE : View.GONE);
        findViewById(R.id.pageSettings).setVisibility(
                tab == BottomBar.TAB_SETTINGS ? View.VISIBLE : View.GONE);
        bottomBar.select(tab);

        // 顶栏标题 + 上下文动作随页面变化
        tvTopTitle.setText(tab == BottomBar.TAB_LISTEN ? R.string.tab_listen
                : tab == BottomBar.TAB_HISTORY ? R.string.tab_history
                : tab == BottomBar.TAB_MODELS ? R.string.tab_models : R.string.tab_settings);
        topAction = tab;
        btnTopAction.setVisibility(tab == BottomBar.TAB_HISTORY
                || tab == BottomBar.TAB_MODELS ? View.VISIBLE : View.GONE);
        btnTopAction.setText(tab == BottomBar.TAB_HISTORY
                ? R.string.history_clear_all : R.string.models_source_title);
        btnTopAction.setTextColor(getColor(
                tab == BottomBar.TAB_HISTORY ? R.color.danger : R.color.text_secondary));

        if (tab == BottomBar.TAB_HISTORY) {
            historyPage.refresh();
            historyDirty = false; // 看过即清角标
        } else if (tab == BottomBar.TAB_MODELS) {
            modelsPage.refresh();
        } else if (tab == BottomBar.TAB_SETTINGS) {
            settingsPage.refresh();
        }
        syncBadges();
    }

    /** 角标联动：监听中 / 历史有新记录 / 识别模型缺失（不在对应页才亮）。 */
    private void syncBadges() {
        bottomBar.setBadge(BottomBar.TAB_LISTEN, CaptureService.current != null);
        bottomBar.setBadge(BottomBar.TAB_HISTORY,
                historyDirty && bottomBar.selected() != BottomBar.TAB_HISTORY);
        bottomBar.setBadge(BottomBar.TAB_MODELS,
                bottomBar.selected() != BottomBar.TAB_MODELS && modelsPage.asrOfflineMissing());
    }

    // ── 权限检查 → 启动流程 ──────────────────

    private void checkAndStart() {
        // 1) 录音权限
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_RECORD);
            return;
        }
        // 2) 通知权限 (Android 13+)
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
            return;
        }
        // 3) 悬浮窗权限
        if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission();
            return;
        }
        // 4) 投屏授权
        requestProjection();
    }

    private void requestOverlayPermission() {
        try {
            startActivityForResult(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())), REQ_OVERLAY);
        } catch (Exception e) {
            // 少数 ROM 没有这个设置页
            showOverlayBlockedDialog();
        }
    }

    /**
     * ColorOS / 一加等系统会对侧载应用锁死「显示在其他应用上层」，
     * 系统设置页里的开关点不动。这里给出可落地的绕行办法。
     */
    private void showOverlayBlockedDialog() {
        String cmd = overlayAdbCommand();
        new AlertDialog.Builder(this)
                .setTitle(R.string.overlay_blocked_title)
                .setMessage(getString(R.string.overlay_blocked_msg, getPackageName()))
                .setPositiveButton(R.string.overlay_retry,
                        (d, w) -> requestOverlayPermission())
                .setNeutralButton(R.string.overlay_copy_cmd, (d, w) -> {
                    copyText(cmd);
                    tvStatus.setText(R.string.overlay_cmd_copied);
                })
                .setNegativeButton(R.string.overlay_close, null)
                .show();
    }

    private String overlayAdbCommand() {
        return "adb shell appops set " + getPackageName() + " SYSTEM_ALERT_WINDOW allow";
    }

    private void requestProjection() {
        MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION);
    }

    // ── 权限结果 ────────────────────────────

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQ_RECORD || requestCode == REQ_NOTIFY) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                checkAndStart(); // 从头再来，检查下一项
            } else {
                tvStatus.setText(R.string.status_permission_missing);
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_OVERLAY) {
            // 从系统设置页回来：还是没拿到权限，多半是被 ROM 锁了
            if (Settings.canDrawOverlays(this)) {
                checkAndStart();
            } else {
                showOverlayBlockedDialog();
            }
        } else if (requestCode == REQ_PROJECTION) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                Intent i = new Intent(this, CaptureService.class)
                        .setAction(CaptureService.ACTION_START)
                        .putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
                        .putExtra(CaptureService.EXTRA_RESULT_DATA, data);
                startForegroundService(i);
                tvStatus.setText(R.string.status_started);
                if (dotStatus != null) dotStatus.setBackgroundResource(R.drawable.dot_active);
            } else {
                tvStatus.setText(R.string.status_projection_denied);
                if (dotStatus != null) dotStatus.setBackgroundResource(R.drawable.dot_idle);
            }
        } else {
            modelsPage.onActivityResult(requestCode, resultCode, data); // 本地导入选完文件回来
        }
    }

    // ── 状态 ──────────────────────────────

    private void updateStatus(boolean running) {
        tvStatus.setText(running
                ? getString(R.string.status_running)
                : getString(R.string.status_stopped));
        if (dotStatus != null) {
            dotStatus.setBackgroundResource(
                    running ? R.drawable.dot_active : R.drawable.dot_idle);
        }
        // 操作按钮按状态显隐：停止 / 清空上下文只在监听中可用
        btnStop.setVisibility(running ? View.VISIBLE : View.GONE);
        btnNewChat.setVisibility(running ? View.VISIBLE : View.GONE);
        // 未配 API Key → 引导去「设置」Tab（三套 Key 全空才算未配；离线识别可用时也一样提示，因为朗读/问答还需要）
        boolean noKey = prefs.apiKey().isEmpty()
                && prefs.asrApiKey().isEmpty() && prefs.ttsApiKey().isEmpty();
        tvListenHint.setVisibility(noKey ? View.VISIBLE : View.GONE);
        syncBadges();
    }

    /** 兼容旧调用点（无参）。 */
    private void updateStatus() {
        updateStatus(CaptureService.current != null);
    }

    // ── 页面委托（HistoryPage / HistorySheet 回调用） ────────

    void setStatusText(String text) {
        tvStatus.setText(text);
    }

    void copyText(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("ECoach", text));
        setStatusText(getString(R.string.status_copied));
    }

    /** 历史页能否朗读（服务在跑且朗读链就绪）。 */
    boolean canSpeak() {
        return CaptureService.current != null && CaptureService.current.speech() != null;
    }

    /** 朗读一段历史文本；同一 key 再点 = 停止（与悬浮窗同一约定）。 */
    void speak(String key, String text) {
        if (!canSpeak()) return;
        SpeechPlayer sp = CaptureService.current.speech();
        if (sp.isSpeaking()) {
            sp.stop();
        } else {
            sp.speak(key, text, Translator.speakLang(text));
        }
    }

    int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ── 键盘收起 与 保存后的状态刷新 ──────────

    /**
     * 点空白处收起键盘（命中输入框则保留）。
     *
     * <p>为什么在 Activity 层拦：内容区各页都是 ScrollView / 可点容器，监听挂在某个 View 上时
     * 子 View 会先把事件吃掉，根本收不到「点空白」那一下。{@code dispatchTouchEvent} 能看到全部事件。</p>
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            View content = findViewById(R.id.content);
            if (content == null || !hitsAnyEditText(content, ev)) hideKeyboard();
        }
        return super.dispatchTouchEvent(ev);
    }

    /** 触摸点是否落在某个可见输入框里（递归：输入框可能被行容器包着）。 */
    private static boolean hitsAnyEditText(View v, MotionEvent ev) {
        if (v instanceof EditText && v.getVisibility() == View.VISIBLE && hits(v, ev)) return true;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                if (hitsAnyEditText(g.getChildAt(i), ev)) return true;
            }
        }
        return false;
    }

    private static boolean hits(View v, MotionEvent ev) {
        Rect r = new Rect();
        return v.getGlobalVisibleRect(r)
                && r.contains((int) ev.getRawX(), (int) ev.getRawY());
    }

    /** 收起键盘（设置页输入框与历史搜索框共用）。 */
    void hideKeyboard() {
        View focus = getCurrentFocus();
        IBinder token = focus != null
                ? focus.getWindowToken()
                : getWindow().getDecorView().getWindowToken();
        if (focus != null) focus.clearFocus();
        InputMethodManager imm =
                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null && token != null) imm.hideSoftInputFromWindow(token, 0);
    }

    /**
     * 设置页保存后调用：让「API Key 检测状态」立刻生效。
     *
     * <p>真机踩过：填完 Key 保存后，监听页仍挂着「未配 API Key」的引导，用户以为没保存成功。</p>
     */
    void onConfigSaved() {
        updateStatus();
    }

    // ── 进程退出兜底 ──────────────────────

    @Override
    protected void onDestroy() {
        // 只有 Activity 真正关闭（用户按返回、任务被清）时才停服务；
        // 跳设置页 / 悬浮窗全屏弹 Activity 时 isFinishing()=false，服务照跑。
        if (isFinishing() && CaptureService.current != null) {
            stopService(new Intent(this, CaptureService.class));
        }
        super.onDestroy();
    }
}
