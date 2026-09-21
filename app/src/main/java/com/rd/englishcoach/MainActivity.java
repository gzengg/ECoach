package com.rd.englishcoach;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.view.View;

import java.util.List;

public class MainActivity extends Activity {

    private static final int REQ_RECORD  = 100;
    private static final int REQ_OVERLAY = 101;
    private static final int REQ_NOTIFY  = 102;
    private static final int REQ_PROJECTION = 103;

    private TextView tvStatus;
    private View dotStatus;
    private Button btnStart;
    private View btnStop, btnSettings, btnNewChat, btnHistory;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus   = findViewById(R.id.tvStatus);
        dotStatus  = findViewById(R.id.dotStatus);
        btnStart   = findViewById(R.id.btnStart);
        btnStop    = findViewById(R.id.btnStop);
        btnSettings= findViewById(R.id.btnSettings);
        btnNewChat = findViewById(R.id.btnNewChat);
        btnHistory = findViewById(R.id.btnHistory);

        btnStart.setOnClickListener(v -> checkAndStart());
        btnStop.setOnClickListener(v -> {
            stopService(new Intent(this, CaptureService.class));
            updateStatus();
        });
        btnSettings.setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        btnHistory.setOnClickListener(v -> showTranscriptHistory());
        btnNewChat.setOnClickListener(v -> {
            if (CaptureService.current != null) {
                CaptureService.current.clearHistory();
                tvStatus.setText(R.string.status_chat_cleared);
            } else {
                tvStatus.setText(R.string.status_service_off);
            }
        });

        // 处理重新授权请求
        if (getIntent().getBooleanExtra("reconsent", false)) {
            getIntent().removeExtra("reconsent");
            requestProjection();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
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
                    copyToClipboard(cmd);
                    tvStatus.setText(R.string.overlay_cmd_copied);
                })
                .setNegativeButton(R.string.overlay_close, null)
                .show();
    }

    private String overlayAdbCommand() {
        return "adb shell appops set " + getPackageName() + " SYSTEM_ALERT_WINDOW allow";
    }

    private void copyToClipboard(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("ECoach", text));
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
        }
    }

    // ── 转录历史 ────────────────────────

    private void showTranscriptHistory() {
        HistoryStore store = new HistoryStore(this);
        List<HistoryStore.Entry> entries = store.getAll();
        if (entries.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.history_title)
                    .setMessage(R.string.history_empty)
                    .setPositiveButton(R.string.dialog_ok, null)
                    .show();
            return;
        }

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, 0);

        for (int i = entries.size() - 1; i >= 0; i--) {
            final int idx = i;
            HistoryStore.Entry e = entries.get(i);

            // 转录文字
            TextView tv = new TextView(this);
            String time = new java.text.SimpleDateFormat("MM-dd HH:mm",
                    java.util.Locale.getDefault()).format(new java.util.Date(e.timestamp));
            StringBuilder sb = new StringBuilder();
            sb.append("[ ").append(time).append(" ] ").append(e.transcript);
            if (e.answer != null) {
                sb.append("\n").append(getString(R.string.history_answer_prefix)).append(e.answer);
            }
            tv.setText(sb.toString());
            tv.setTextSize(14);
            tv.setTextColor(0xFFF2F5FA);
            tv.setTextIsSelectable(true);
            layout.addView(tv);

            // 操作按钮行：复制 + 删除
            LinearLayout btnRow = new LinearLayout(this);
            btnRow.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams btnRowLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            btnRowLp.topMargin = pad / 4;
            btnRowLp.bottomMargin = pad / 4;

            TextView btnCopy = new TextView(this);
            btnCopy.setText(R.string.menu_copy);
            btnCopy.setTextSize(12);
            btnCopy.setTextColor(0xFF34D399);
            btnCopy.setPadding(0, 0, pad, 0);
            btnCopy.setOnClickListener(v -> {
                copyToClipboard(e.transcript
                        + (e.answer != null ? "\n" + getString(R.string.history_answer_prefix) + e.answer : ""));
                tvStatus.setText(R.string.status_copied);
            });
            btnRow.addView(btnCopy);

            TextView btnDelete = new TextView(this);
            btnDelete.setText(R.string.menu_delete);
            btnDelete.setTextSize(12);
            btnDelete.setTextColor(0xFFF87171);
            btnDelete.setPadding(0, 0, pad, 0);
            btnDelete.setOnClickListener(v -> {
                store.deleteAt(idx);
                tvStatus.setText(R.string.status_deleted);
                showTranscriptHistory(); // 刷新列表
            });
            btnRow.addView(btnDelete);

            layout.addView(btnRow, btnRowLp);

            if (i > 0) {
                TextView sep = new TextView(this);
                sep.setText("");
                sep.setMinimumHeight(1);
                sep.setBackgroundColor(0x33FFFFFF);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1);
                lp.topMargin = pad / 4;
                lp.bottomMargin = pad / 4;
                layout.addView(sep, lp);
            }
        }

        ScrollView scroll = new ScrollView(this);
        scroll.addView(layout);

        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.history_title_count, entries.size()))
                .setView(scroll)
                .setPositiveButton(R.string.dialog_ok, null)
                .setNeutralButton(R.string.history_clear_all, (d, w) -> {
                    store.clear();
                    tvStatus.setText(R.string.status_history_cleared);
                })
                .show();
    }

    // ── 状态 ──────────────────────────────

    private void updateStatus() {
        boolean running = CaptureService.current != null;
        tvStatus.setText(running
                ? getString(R.string.status_running)
                : getString(R.string.status_stopped));
        if (dotStatus != null) {
            dotStatus.setBackgroundResource(
                    running ? R.drawable.dot_active : R.drawable.dot_idle);
        }
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
