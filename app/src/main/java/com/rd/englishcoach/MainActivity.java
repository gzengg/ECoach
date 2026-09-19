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
import android.widget.TextView;

public class MainActivity extends Activity {

    private static final int REQ_RECORD  = 100;
    private static final int REQ_OVERLAY = 101;
    private static final int REQ_NOTIFY  = 102;
    private static final int REQ_PROJECTION = 103;

    private TextView tvStatus;
    private Button btnStart, btnStop, btnSettings;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus   = findViewById(R.id.tvStatus);
        btnStart   = findViewById(R.id.btnStart);
        btnStop    = findViewById(R.id.btnStop);
        btnSettings= findViewById(R.id.btnSettings);

        btnStart.setOnClickListener(v -> checkAndStart());
        btnStop.setOnClickListener(v -> {
            Intent i = new Intent(this, CaptureService.class)
                    .setAction(CaptureService.ACTION_STOP);
            startService(i);
            updateStatus();
        });
        btnSettings.setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));

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
