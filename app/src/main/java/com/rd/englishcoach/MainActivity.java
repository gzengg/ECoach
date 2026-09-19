package com.rd.englishcoach;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.media.projection.MediaProjectionManager;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.app.AlertDialog;
import android.graphics.Typeface;

import java.util.List;

public class MainActivity extends Activity {

    private static final int REQ_RECORD  = 100;
    private static final int REQ_OVERLAY = 101;
    private static final int REQ_NOTIFY  = 102;
    private static final int REQ_PROJECTION = 103;

    private TextView tvStatus;
    private Button btnStart, btnStop, btnSettings, btnNewChat, btnHistory;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus   = findViewById(R.id.tvStatus);
        btnStart   = findViewById(R.id.btnStart);
        btnStop    = findViewById(R.id.btnStop);
        btnSettings= findViewById(R.id.btnSettings);
        btnNewChat = findViewById(R.id.btnNewChat);
        btnHistory = findViewById(R.id.btnHistory);

        btnStart.setOnClickListener(v -> checkAndStart());
        btnStop.setOnClickListener(v -> {
            Intent i = new Intent(this, CaptureService.class)
                    .setAction(CaptureService.ACTION_STOP);
            startService(i);
            updateStatus();
        });
        btnSettings.setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        btnHistory.setOnClickListener(v -> showTranscriptHistory());
        btnNewChat.setOnClickListener(v -> {
            if (CaptureService.current != null) {
                CaptureService.current.clearHistory();
                tvStatus.setText("已清空上下文，开始新对话");
            } else {
                tvStatus.setText("服务未运行");
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
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(i, REQ_OVERLAY);
            return;
        }
        // 4) 投屏授权
        requestProjection();
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
                tvStatus.setText("缺少必要权限，请在设置里手动授予");
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        android.util.Log.i("MainAct", "onActivityResult req=" + requestCode + " rc=" + resultCode + " data=" + (data != null ? "ok" : "null"));
        if (requestCode == REQ_OVERLAY) {
            checkAndStart();
        } else if (requestCode == REQ_PROJECTION) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                android.util.Log.i("MainAct", "Starting CaptureService");
                Intent i = new Intent(this, CaptureService.class)
                        .setAction(CaptureService.ACTION_START)
                        .putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
                        .putExtra(CaptureService.EXTRA_RESULT_DATA, data);
                startForegroundService(i);
                tvStatus.setText("已启动，悬浮窗应该出现了");
            } else {
                tvStatus.setText("投屏授权被拒绝");
            }
        }
    }

    // ── 转录历史 ────────────────────────

    private void showTranscriptHistory() {
        HistoryStore store = new HistoryStore(this);
        List<HistoryStore.Entry> entries = store.getAll();
        if (entries.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("转录历史")
                    .setMessage("暂无记录")
                    .setPositiveButton("确定", null)
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

            // 转录文字（白色）
            TextView tv = new TextView(this);
            String time = new java.text.SimpleDateFormat("MM-dd HH:mm",
                    java.util.Locale.getDefault()).format(new java.util.Date(e.timestamp));
            StringBuilder sb = new StringBuilder();
            sb.append("[ ").append(time).append(" ] ").append(e.transcript);
            if (e.answer != null) sb.append("\n答: ").append(e.answer);
            tv.setText(sb.toString());
            tv.setTextSize(14);
            tv.setTextColor(0xFFFFFFFF);
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
            btnCopy.setText("📋 复制");
            btnCopy.setTextSize(12);
            btnCopy.setTextColor(0xFF4CAF50);
            btnCopy.setPadding(0, 0, pad, 0);
            btnCopy.setOnClickListener(v -> {
                android.content.ClipboardManager cm = (android.content.ClipboardManager)
                        getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("transcript",
                            e.transcript + (e.answer != null ? "\n答: " + e.answer : "")));
                    tvStatus.setText("已复制到剪贴板");
                }
            });
            btnRow.addView(btnCopy);

            TextView btnDelete = new TextView(this);
            btnDelete.setText("🗑 删除");
            btnDelete.setTextSize(12);
            btnDelete.setTextColor(0xFFFF5252);
            btnDelete.setPadding(0, 0, pad, 0);
            btnDelete.setOnClickListener(v -> {
                store.deleteAt(idx);
                tvStatus.setText("已删除");
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
                .setTitle("转录历史 (" + entries.size() + " 条)")
                .setView(scroll)
                .setPositiveButton("确定", null)
                .setNeutralButton("清空全部", (d, w) -> {
                    store.clear();
                    tvStatus.setText("历史已清空");
                })
                .show();
    }

    // ── 状态 ──────────────────────────────

    private void updateStatus() {
        if (CaptureService.current != null) {
            tvStatus.setText("✅ 服务运行中\n\n"
                + "打开西柚英语放听力，用悬浮窗操作即可。\n"
                + "锁屏会停止投屏，需要重新授权。");
        } else {
            tvStatus.setText("服务未启动\n\n"
                + "点「开始监听」→ 系统弹窗点「开始录制」→ 悬浮窗出现 → 打开西柚英语");
        }
    }
}
