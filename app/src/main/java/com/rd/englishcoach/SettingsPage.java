package com.rd.englishcoach;

import android.text.method.PasswordTransformationMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.function.Consumer;

/**
 * 设置页：接口 / 提示词 / 悬浮窗外观 / 朗读音色。
 *
 * <p>由旧 SettingsActivity 的「设置」Tab 原样迁来。变化：</p>
 * <ul>
 *   <li>音色选择从「模型」Tab 迁回这里（它是在线 TTS 的属性），改为行式入口
 *       + Bottom Sheet 横向芯片选择（sheet 内容横向可滚，芯片不会被挤压换行）；</li>
 *   <li>「保存」不再 finish()（四 Tab 后设置只是其中一个页面，退出去会把整个应用带走），
 *       改为应用 + Toast；保存后顺带收起键盘，并通知主界面刷新 API Key 检测状态。</li>
 * </ul>
 */
final class SettingsPage {

    private final MainActivity act;
    private final Prefs prefs;
    private final BottomSheetPanel sheet;

    private final EditText etBaseUrl, etApiKey, etAsrModel, etChatModel, etSysPrompt, etTtsModel;
    private final TextView tvFontValue, tvWidthValue, btnToggleKey, tvVoiceEn, tvVoiceZh;

    SettingsPage(MainActivity act, View root) {
        this.act = act;
        this.prefs = new Prefs(act);
        this.sheet = new BottomSheetPanel(act);

        etBaseUrl   = root.findViewById(R.id.etBaseUrl);
        etApiKey    = root.findViewById(R.id.etApiKey);
        etAsrModel  = root.findViewById(R.id.etAsrModel);
        etChatModel = root.findViewById(R.id.etChatModel);
        etTtsModel  = root.findViewById(R.id.etTtsModel);
        etSysPrompt = root.findViewById(R.id.etSysPrompt);
        tvFontValue = root.findViewById(R.id.tvFontValue);
        tvWidthValue = root.findViewById(R.id.tvWidthValue);
        btnToggleKey = root.findViewById(R.id.btnToggleKey);
        tvVoiceEn   = root.findViewById(R.id.tvVoiceEn);
        tvVoiceZh   = root.findViewById(R.id.tvVoiceZh);

        btnToggleKey.setOnClickListener(v -> toggleKeyVisible());
        root.findViewById(R.id.btnFontMinus).setOnClickListener(v -> {
            prefs.putFontSp(prefs.fontSp() - 1);
            tvFontValue.setText(prefs.fontSp() + "sp");
            applyToPanel();
        });
        root.findViewById(R.id.btnFontPlus).setOnClickListener(v -> {
            prefs.putFontSp(prefs.fontSp() + 1);
            tvFontValue.setText(prefs.fontSp() + "sp");
            applyToPanel();
        });
        root.findViewById(R.id.btnWidthMinus).setOnClickListener(v -> {
            prefs.putWidthDp(prefs.widthDp() - 10);
            tvWidthValue.setText(prefs.widthDp() + "dp");
            applyToPanel();
        });
        root.findViewById(R.id.btnWidthPlus).setOnClickListener(v -> {
            prefs.putWidthDp(prefs.widthDp() + 10);
            tvWidthValue.setText(prefs.widthDp() + "dp");
            applyToPanel();
        });
        root.findViewById(R.id.btnVoiceEn).setOnClickListener(v -> showVoiceSheet(true));
        root.findViewById(R.id.btnVoiceZh).setOnClickListener(v -> showVoiceSheet(false));

        root.findViewById(R.id.btnSave).setOnClickListener(v -> save());
        root.findViewById(R.id.btnReset).setOnClickListener(v -> { prefs.resetAll(); loadAll(); });

        loadAll();
    }

    /** Tab 切进本页时同步一遍（外部可能改过配置）。 */
    void refresh() { loadAll(); }

    private void loadAll() {
        etBaseUrl.setText(prefs.baseUrl());
        etApiKey.setText(prefs.apiKey());
        etAsrModel.setText(prefs.asrModel());
        etChatModel.setText(prefs.chatModel());
        etSysPrompt.setText(prefs.sysPrompt());
        etTtsModel.setText(prefs.ttsModel());
        tvFontValue.setText(prefs.fontSp() + "sp");
        tvWidthValue.setText(prefs.widthDp() + "dp");
        tvVoiceEn.setText(prefs.ttsVoiceEnglish());
        tvVoiceZh.setText(prefs.ttsVoiceChinese());
    }

    /** API Key 默认打码成一排圆点；点「显示」可以明文核对，免得把圆点当成乱码。 */
    private void toggleKeyVisible() {
        boolean masked = etApiKey.getTransformationMethod() instanceof PasswordTransformationMethod;
        etApiKey.setTransformationMethod(masked ? null : new PasswordTransformationMethod());
        btnToggleKey.setText(masked ? R.string.set_hide_key : R.string.set_show_key);
        etApiKey.setSelection(etApiKey.getText().length());
    }

    private void save() {
        prefs.putBaseUrl(etBaseUrl.getText().toString().trim());
        prefs.putApiKey(etApiKey.getText().toString().trim());
        prefs.putAsrModel(etAsrModel.getText().toString().trim());
        prefs.putChatModel(etChatModel.getText().toString().trim());
        prefs.putSysPrompt(etSysPrompt.getText().toString().trim());
        prefs.putTtsModel(etTtsModel.getText().toString().trim());
        // 收起键盘（否则键盘挡住下半页，看不到保存结果），并让 API Key 检测状态立刻生效
        act.hideKeyboard();
        act.onConfigSaved();
        Toast.makeText(act, act.getString(R.string.set_saved), Toast.LENGTH_SHORT).show();
    }

    /** 字号/宽度立即作用到悬浮窗（服务在跑时）。 */
    private void applyToPanel() {
        if (CaptureService.current != null && CaptureService.current.panel != null) {
            CaptureService.current.panel.applyFontSize(prefs.fontSp());
            CaptureService.current.panel.applyWidth(prefs.widthDp());
        }
    }

    // ── 朗读音色：Bottom Sheet 横向芯片选择 ────────────────

    /** 音色选择 sheet：横向滚动芯片行，选中态由代码切背景（与悬浮窗 Tab 同做法）。 */
    private void showVoiceSheet(boolean english) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(act);
        title.setText(english ? R.string.set_tts_voice_en : R.string.set_tts_voice_zh);
        title.setTextSize(15);
        title.setTextColor(act.getColor(R.color.text_primary));
        box.addView(title);

        String[] values = english ? Prefs.TTS_VOICES_EN : Prefs.TTS_VOICES_ZH;
        String current = english ? prefs.ttsVoiceEnglish() : prefs.ttsVoiceChinese();
        Consumer<String> onPick = english ? prefs::putTtsVoiceEnglish : prefs::putTtsVoiceChinese;

        // 芯片行必须横向滚动，不能挤压换行（真机踩过：5 个芯片等分被压成两行）
        HorizontalScrollView scroll = new HorizontalScrollView(act);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, act.dp(8), 0, 0);
        for (String v : values) {
            TextView chip = (TextView) LayoutInflater.from(act)
                    .inflate(R.layout.item_chip, row, false);
            chip.setText(v);
            boolean on = v.equals(current);
            chip.setBackgroundResource(on ? R.drawable.pill_glass : R.drawable.bg_chip);
            chip.setTextColor(act.getColor(on ? R.color.accent_solid : R.color.text_secondary));
            chip.setOnClickListener(x -> {
                onPick.accept(v);
                sheet.dismiss();
                tvVoiceEn.setText(prefs.ttsVoiceEnglish());
                tvVoiceZh.setText(prefs.ttsVoiceChinese());
            });
            row.addView(chip);
        }
        scroll.addView(row);
        box.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        sheet.show(box);
    }
}
