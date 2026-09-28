package com.rd.englishcoach;

import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 设置页：只留与「模型」无关的两组 —— 系统提示词 + 悬浮窗外观。
 *
 * <p>v4.3：三套在线接口配置与朗读音色<b>整体搬到「模型」Tab</b>（见 {@link OnlineSection}）。
 * 原因：接口地址 / Key / 模型 / 音色都是「用哪个模型」这件事的参数，堆在设置页会让
 * 提示词与外观被挤到屏幕外，用户找不到；搬走后设置页一屏两组，一眼看完。</p>
 */
final class SettingsPage {

    private final MainActivity act;
    private final Prefs prefs;

    private final EditText etSysPrompt;
    private final TextView tvFontValue, tvWidthValue;

    SettingsPage(MainActivity act, View root) {
        this.act = act;
        this.prefs = new Prefs(act);

        etSysPrompt  = root.findViewById(R.id.etSysPrompt);
        tvFontValue  = root.findViewById(R.id.tvFontValue);
        tvWidthValue = root.findViewById(R.id.tvWidthValue);

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

        root.findViewById(R.id.btnSave).setOnClickListener(v -> save());
        root.findViewById(R.id.btnReset).setOnClickListener(v -> { prefs.resetAll(); loadAll(); });

        loadAll();
    }

    /** Tab 切进本页时同步一遍（「重置」可能把提示词改回默认）。 */
    void refresh() { loadAll(); }

    private void loadAll() {
        etSysPrompt.setText(prefs.sysPrompt());
        tvFontValue.setText(prefs.fontSp() + "sp");
        tvWidthValue.setText(prefs.widthDp() + "dp");
    }

    private void save() {
        prefs.putSysPrompt(val(etSysPrompt));
        // 收起键盘（否则键盘挡住下半页），并让「未配 API Key」引导立刻按最新配置刷新
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

    private String val(EditText e) { return e.getText().toString().trim(); }
}
