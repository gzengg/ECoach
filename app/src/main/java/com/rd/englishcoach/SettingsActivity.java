package com.rd.englishcoach;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

/**
 * 设置页：Base URL / Key / 模型 / 字号 / 窗口宽度 / 提示词。
 * 改完即时生效（CaptureService 会在下次操作时读取最新值）。
 */
public class SettingsActivity extends Activity {

    private EditText etBaseUrl, etApiKey, etAsrModel, etChatModel, etSysPrompt;
    private TextView tvFontPreview, tvWidthPreview;
    private Prefs prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        prefs = new Prefs(this);

        etBaseUrl   = findViewById(R.id.etBaseUrl);
        etApiKey    = findViewById(R.id.etApiKey);
        etAsrModel  = findViewById(R.id.etAsrModel);
        etChatModel = findViewById(R.id.etChatModel);
        etSysPrompt = findViewById(R.id.etSysPrompt);

        etBaseUrl.setText(prefs.baseUrl());
        etApiKey.setText(prefs.apiKey());
        etAsrModel.setText(prefs.asrModel());
        etChatModel.setText(prefs.chatModel());
        etSysPrompt.setText(prefs.sysPrompt());

        tvFontPreview  = findViewById(R.id.tvFontValue);
        tvWidthPreview = findViewById(R.id.tvWidthValue);

        tvFontPreview.setText(prefs.fontSp() + "sp");
        tvWidthPreview.setText(prefs.widthDp() + "dp");

        findViewById(R.id.btnFontMinus).setOnClickListener(v -> {
            prefs.putFontSp(prefs.fontSp() - 1);
            tvFontPreview.setText(prefs.fontSp() + "sp");
            applyToPanel();
        });
        findViewById(R.id.btnFontPlus).setOnClickListener(v -> {
            prefs.putFontSp(prefs.fontSp() + 1);
            tvFontPreview.setText(prefs.fontSp() + "sp");
            applyToPanel();
        });
        findViewById(R.id.btnWidthMinus).setOnClickListener(v -> {
            prefs.putWidthDp(prefs.widthDp() - 10);
            tvWidthPreview.setText(prefs.widthDp() + "dp");
            applyToPanel();
        });
        findViewById(R.id.btnWidthPlus).setOnClickListener(v -> {
            prefs.putWidthDp(prefs.widthDp() + 10);
            tvWidthPreview.setText(prefs.widthDp() + "dp");
            applyToPanel();
        });

        Button btnSave = findViewById(R.id.btnSave);
        btnSave.setOnClickListener(v -> save());

        Button btnReset = findViewById(R.id.btnReset);
        btnReset.setOnClickListener(v -> {
            prefs.resetAll();
            etBaseUrl.setText(prefs.baseUrl());
            etApiKey.setText(prefs.apiKey());
            etAsrModel.setText(prefs.asrModel());
            etChatModel.setText(prefs.chatModel());
            etSysPrompt.setText(prefs.sysPrompt());
            tvFontPreview.setText(prefs.fontSp() + "sp");
            tvWidthPreview.setText(prefs.widthDp() + "dp");
        });
    }

    private void save() {
        prefs.putBaseUrl(etBaseUrl.getText().toString().trim());
        prefs.putApiKey(etApiKey.getText().toString().trim());
        prefs.putAsrModel(etAsrModel.getText().toString().trim());
        prefs.putChatModel(etChatModel.getText().toString().trim());
        prefs.putSysPrompt(etSysPrompt.getText().toString().trim());
        finish();
    }

    private void applyToPanel() {
        if (CaptureService.current != null && CaptureService.current.panel != null) {
            CaptureService.current.panel.applyFontSize(prefs.fontSp());
            CaptureService.current.panel.applyWidth(prefs.widthDp());
        }
    }
}
