package com.rd.englishcoach;

import android.text.Editable;
import android.text.TextWatcher;
import android.text.method.PasswordTransformationMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * 设置页：三套接口（问答 / 识别 / 朗读）+ 系统提示词 + 悬浮窗外观 + 朗读音色。
 *
 * <p>v4.2：接口拆成三套独立配置（Base URL / 接口 URL + Key + 模型），可分别指向不同服务商；
 * 识别与朗读的 Key 留空时跟随问答 Key。模型行可「选择」——从 {@code GET {base}/models}
 * 拉到的模型目录里按类目点选，同时保留手填。另加「检测连通性」（三类各测一次）与
 * 「检测音色」（探测当前 TTS 模型可选音色）。</p>
 *
 * <p>网络动作（拉模型列表 / 探测 / 音色）都在后台线程，回主线程更新 UI；
 * 一律用<b>输入框当前值</b>而不是已保存值（否则用户改了没保存会「测的和填的不一致」）。</p>
 */
final class SettingsPage {

    private final MainActivity act;
    private final Prefs prefs;
    private final BottomSheetPanel sheet;

    // 问答
    private final EditText etBaseUrl, etApiKey, etChatModel;
    private final TextView btnToggleKey;
    // 识别
    private final EditText etAsrBaseUrl, etAsrKey, etAsrModel;
    private final TextView btnToggleAsrKey;
    // 朗读
    private final EditText etTtsBaseUrl, etTtsKey, etTtsModel;
    private final TextView btnToggleTtsKey;

    private final EditText etSysPrompt;
    private final TextView tvFontValue, tvWidthValue, tvVoiceEn, tvVoiceZh;

    /** 上次「检测音色」的结果（null = 用 Prefs 里的默认列表）。 */
    private List<String> detectedVoices;

    SettingsPage(MainActivity act, View root) {
        this.act = act;
        this.prefs = new Prefs(act);
        this.sheet = new BottomSheetPanel(act);

        etBaseUrl      = root.findViewById(R.id.etBaseUrl);
        etApiKey       = root.findViewById(R.id.etApiKey);
        etChatModel    = root.findViewById(R.id.etChatModel);
        btnToggleKey   = root.findViewById(R.id.btnToggleKey);
        etAsrBaseUrl   = root.findViewById(R.id.etAsrBaseUrl);
        etAsrKey       = root.findViewById(R.id.etAsrKey);
        etAsrModel     = root.findViewById(R.id.etAsrModel);
        btnToggleAsrKey = root.findViewById(R.id.btnToggleAsrKey);
        etTtsBaseUrl   = root.findViewById(R.id.etTtsBaseUrl);
        etTtsKey       = root.findViewById(R.id.etTtsKey);
        etTtsModel     = root.findViewById(R.id.etTtsModel);
        btnToggleTtsKey = root.findViewById(R.id.btnToggleTtsKey);
        etSysPrompt    = root.findViewById(R.id.etSysPrompt);
        tvFontValue    = root.findViewById(R.id.tvFontValue);
        tvWidthValue   = root.findViewById(R.id.tvWidthValue);
        tvVoiceEn      = root.findViewById(R.id.tvVoiceEn);
        tvVoiceZh      = root.findViewById(R.id.tvVoiceZh);

        btnToggleKey.setOnClickListener(v -> toggleKeyVisible(etApiKey, btnToggleKey));
        btnToggleAsrKey.setOnClickListener(v -> toggleKeyVisible(etAsrKey, btnToggleAsrKey));
        btnToggleTtsKey.setOnClickListener(v -> toggleKeyVisible(etTtsKey, btnToggleTtsKey));
        root.findViewById(R.id.btnPickChat).setOnClickListener(
                v -> showModelPicker(ModelDiscovery.Kind.CHAT, etChatModel));
        root.findViewById(R.id.btnPickAsr).setOnClickListener(
                v -> showModelPicker(ModelDiscovery.Kind.ASR, etAsrModel));
        root.findViewById(R.id.btnPickTts).setOnClickListener(
                v -> showModelPicker(ModelDiscovery.Kind.TTS, etTtsModel));
        root.findViewById(R.id.btnDetectConnectivity).setOnClickListener(v -> detectConnectivity());
        root.findViewById(R.id.btnDetectVoices).setOnClickListener(v -> detectVoices());

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
        etBaseUrl.setText(prefs.chatBaseUrl());
        etApiKey.setText(prefs.apiKey());
        etChatModel.setText(prefs.chatModel());
        etAsrBaseUrl.setText(prefs.asrBaseUrl());
        etAsrKey.setText(prefs.asrApiKeyRaw());   // 原始值：留空如实显示为空
        etAsrModel.setText(prefs.asrModel());
        etTtsBaseUrl.setText(prefs.ttsBaseUrl());
        etTtsKey.setText(prefs.ttsApiKeyRaw());
        etTtsModel.setText(prefs.ttsModel());
        etSysPrompt.setText(prefs.sysPrompt());
        tvFontValue.setText(prefs.fontSp() + "sp");
        tvWidthValue.setText(prefs.widthDp() + "dp");
        tvVoiceEn.setText(prefs.ttsVoiceEnglish());
        tvVoiceZh.setText(prefs.ttsVoiceChinese());
    }

    /** API Key 默认打码成一排圆点；点「显示」可以明文核对，免得把圆点当成乱码。 */
    private void toggleKeyVisible(EditText field, TextView btn) {
        boolean masked = field.getTransformationMethod() instanceof PasswordTransformationMethod;
        field.setTransformationMethod(masked ? null : new PasswordTransformationMethod());
        btn.setText(masked ? R.string.set_hide_key : R.string.set_show_key);
        field.setSelection(field.getText().length());
    }

    private void save() {
        prefs.putChatBaseUrl(val(etBaseUrl));
        prefs.putApiKey(val(etApiKey));
        prefs.putChatModel(val(etChatModel));
        prefs.putAsrBaseUrl(val(etAsrBaseUrl));
        prefs.putAsrApiKey(val(etAsrKey));
        prefs.putAsrModel(val(etAsrModel));
        prefs.putTtsBaseUrl(val(etTtsBaseUrl));
        prefs.putTtsApiKey(val(etTtsKey));
        prefs.putTtsModel(val(etTtsModel));
        prefs.putSysPrompt(val(etSysPrompt));
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

    // ── 检测连通性（三类各测一次，输入框当前值为准） ──────────────

    private void detectConnectivity() {
        // 先在 UI 线程取值：后台线程不能碰 View
        final String chatUrl = val(etBaseUrl);
        final String chatModel = val(etChatModel);
        final String asrUrl = val(etAsrBaseUrl);
        final String asrModel = val(etAsrModel);
        final String ttsUrl = val(etTtsBaseUrl);
        final String ttsModel = val(etTtsModel);
        final String chatKey = val(etApiKey);
        final String asrKey  = orElse(val(etAsrKey), chatKey);
        final String ttsKey  = orElse(val(etTtsKey), chatKey);

        LinearLayout box = verticalBox();
        TextView title = new TextView(act);
        title.setText(R.string.set_detect_connectivity);
        title.setTextSize(15);
        title.setTextColor(act.getColor(R.color.text_primary));
        box.addView(title);

        TextView result = new TextView(act);
        result.setText(R.string.set_probe_running);
        result.setTextSize(13);
        result.setTextColor(act.getColor(R.color.text_secondary));
        result.setPadding(0, act.dp(8), 0, 0);
        box.addView(result);
        sheet.show(box);

        new Thread(() -> {
            String chat = ModelDiscovery.probeChat(chatUrl, chatKey, chatModel);
            String asr  = ModelDiscovery.probeAsr(asrUrl, asrKey, asrModel);
            String tts  = ModelDiscovery.probeTts(ttsUrl, ttsKey, ttsModel);
            final String text = probeLine(act.getString(R.string.set_pick_kind_chat), chat)
                    + "\n\n" + probeLine(act.getString(R.string.set_pick_kind_asr), asr)
                    + "\n\n" + probeLine(act.getString(R.string.set_pick_kind_tts), tts);
            act.runOnUiThread(() -> { if (sheet.showing()) result.setText(text); });
        }, "probe").start();
    }

    private String probeLine(String label, String err) {
        return act.getString(R.string.set_probe_result, label,
                err == null ? act.getString(R.string.set_probe_ok) : err);
    }

    // ── 模型列表选择（问答 / 识别 / 朗读 分开） ──────────────

    private void showModelPicker(ModelDiscovery.Kind kind, EditText target) {
        LinearLayout box = verticalBox();

        TextView title = new TextView(act);
        title.setText(act.getString(R.string.set_pick_title, kindLabel(kind)));
        title.setTextSize(15);
        title.setTextColor(act.getColor(R.color.text_primary));
        box.addView(title);

        EditText search = new EditText(act);
        search.setHint(R.string.set_pick_search);
        search.setTextSize(13);
        search.setBackgroundResource(R.drawable.bg_input);
        search.setPadding(act.dp(12), act.dp(8), act.dp(12), act.dp(8));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sp.topMargin = act.dp(8);
        box.addView(search, sp);

        TextView status = new TextView(act);
        status.setText(R.string.set_pick_loading);
        status.setTextSize(13);
        status.setTextColor(act.getColor(R.color.text_secondary));
        status.setPadding(0, act.dp(12), 0, 0);
        box.addView(status);

        LinearLayout list = new LinearLayout(act);
        list.setOrientation(LinearLayout.VERTICAL);
        MaxHeightScrollView scroll = new MaxHeightScrollView(act);
        scroll.setMaxHeight(act.dp(360));
        scroll.addView(list);
        box.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        sheet.show(box);

        final String baseUrl = val(etBaseUrl);
        new Thread(() -> {
            final List<ModelDiscovery.ModelInfo> picked =
                    ModelDiscovery.byKind(ModelDiscovery.fetchModels(baseUrl), kind);
            act.runOnUiThread(() -> {
                if (!sheet.showing()) return;
                if (picked.isEmpty()) { status.setText(R.string.set_pick_empty); return; }
                status.setVisibility(View.GONE);
                renderModels(list, picked, "", target);
                search.addTextChangedListener(new TextWatcher() {
                    @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                    @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
                    @Override public void afterTextChanged(Editable s) {
                        renderModels(list, picked, s.toString(), target);
                    }
                });
            });
        }, "model-list").start();
    }

    private void renderModels(LinearLayout list, List<ModelDiscovery.ModelInfo> models,
                              String query, EditText target) {
        list.removeAllViews();
        String q = query == null ? "" : query.trim().toLowerCase();
        for (ModelDiscovery.ModelInfo m : models) {
            if (!q.isEmpty() && !m.id.toLowerCase().contains(q)
                    && !m.name.toLowerCase().contains(q)) continue;
            list.addView(modelRow(m, target));
        }
        if (list.getChildCount() == 0) {
            TextView none = new TextView(act);
            none.setText(R.string.set_pick_none);
            none.setTextSize(12);
            none.setTextColor(act.getColor(R.color.text_tertiary));
            none.setPadding(0, act.dp(12), 0, act.dp(12));
            list.addView(none);
        }
    }

    private View modelRow(ModelDiscovery.ModelInfo m, EditText target) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackgroundResource(R.drawable.card_row);
        row.setPadding(act.dp(12), act.dp(8), act.dp(12), act.dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = act.dp(4);
        row.setLayoutParams(lp);
        row.setClickable(true);

        TextView id = new TextView(act);
        id.setText(m.id);
        id.setTextSize(13);
        id.setTextColor(act.getColor(R.color.text_primary));
        row.addView(id);

        if (!m.name.equals(m.id)) {
            TextView name = new TextView(act);
            name.setText(m.name);
            name.setTextSize(11);
            name.setTextColor(act.getColor(R.color.text_tertiary));
            row.addView(name);
        }
        row.setOnClickListener(v -> { target.setText(m.id); sheet.dismiss(); });
        return row;
    }

    private String kindLabel(ModelDiscovery.Kind kind) {
        switch (kind) {
            case ASR: return act.getString(R.string.set_pick_kind_asr);
            case TTS: return act.getString(R.string.set_pick_kind_tts);
            default:  return act.getString(R.string.set_pick_kind_chat);
        }
    }

    // ── 检测音色（探测当前 TTS 模型可选音色） ──────────────

    private void detectVoices() {
        final String url = val(etTtsBaseUrl);
        final String model = val(etTtsModel);
        final String key = orElse(val(etTtsKey), val(etApiKey));
        new Thread(() -> {
            final List<String> voices = TtsClient.detectVoices(url, key, model);
            act.runOnUiThread(() -> {
                if (voices.isEmpty()) {
                    toast(act.getString(R.string.set_detect_voices_fail));
                } else {
                    detectedVoices = voices;
                    toast(act.getString(R.string.set_detect_voices_ok, voices.size()));
                }
            });
        }, "voice-probe").start();
    }

    // ── 朗读音色：Bottom Sheet 横向芯片选择 ────────────────

    /** 音色选择 sheet：横向滚动芯片行 + 手填入口（检测不到时可手动输入）。 */
    private void showVoiceSheet(boolean english) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(act);
        title.setText(english ? R.string.set_tts_voice_en : R.string.set_tts_voice_zh);
        title.setTextSize(15);
        title.setTextColor(act.getColor(R.color.text_primary));
        box.addView(title);

        String[] values = voicesFor(english);
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

        // 手填音色：检测不到列表 / 想用列表外的音色时用
        EditText manual = new EditText(act);
        manual.setHint(R.string.set_tts_voice_manual);
        manual.setText(current);
        manual.setTextSize(13);
        manual.setBackgroundResource(R.drawable.bg_input);
        manual.setPadding(act.dp(12), act.dp(8), act.dp(12), act.dp(8));
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        mp.topMargin = act.dp(8);
        box.addView(manual, mp);

        TextView confirm = textButton(R.string.set_save, R.color.accent_solid);
        confirm.setOnClickListener(v -> {
            String v2 = manual.getText().toString().trim();
            if (!v2.isEmpty()) onPick.accept(v2);
            sheet.dismiss();
            tvVoiceEn.setText(prefs.ttsVoiceEnglish());
            tvVoiceZh.setText(prefs.ttsVoiceChinese());
        });
        box.addView(confirm, actionRow());
        sheet.show(box);
    }

    /**
     * 该语言下的候选音色：还没检测过就用默认列表；检测过则按已知中/英文声拆分，
     * 未知音色两侧都列（用户自选）。检测不到时手填入口兜底。
     */
    private String[] voicesFor(boolean english) {
        if (detectedVoices == null || detectedVoices.isEmpty()) {
            return english ? Prefs.TTS_VOICES_EN : Prefs.TTS_VOICES_ZH;
        }
        List<String> known = Arrays.asList(english ? Prefs.TTS_VOICES_EN : Prefs.TTS_VOICES_ZH);
        List<String> other = Arrays.asList(english ? Prefs.TTS_VOICES_ZH : Prefs.TTS_VOICES_EN);
        List<String> out = new ArrayList<>();
        for (String v : detectedVoices) {
            if (known.contains(v) || !other.contains(v)) out.add(v);
        }
        return out.toArray(new String[0]);
    }

    // ── 小工具 ─────────────────────────────────────────

    private String val(EditText e) { return e.getText().toString().trim(); }

    private static String orElse(String v, String fallback) { return v.isEmpty() ? fallback : v; }

    private LinearLayout verticalBox() {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        return box;
    }

    private LinearLayout.LayoutParams actionRow() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = android.view.Gravity.END;
        lp.topMargin = act.dp(8);
        return lp;
    }

    private TextView textButton(int textRes, int colorRes) {
        TextView tv = new TextView(act);
        tv.setText(textRes);
        tv.setTextSize(13);
        tv.setTextColor(act.getColor(colorRes));
        tv.setBackgroundResource(R.drawable.bg_chip);
        int h = act.dp(8);
        tv.setPadding(act.dp(12), h, act.dp(12), h);
        tv.setClickable(true);
        return tv;
    }

    private void toast(String msg) {
        Toast.makeText(act, msg, Toast.LENGTH_SHORT).show();
    }
}
