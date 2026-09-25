package com.rd.englishcoach;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.method.PasswordTransformationMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 设置页（v4）：合并了「模型」——一个页面两个 Tab（设置 / 模型）。
 *
 * <p><b>为什么合并：</b>「设置」和「模型」在体验上是一件事，两个入口要来回跳。
 * 两个 Tab 同一时刻只显示一个，所以各自仍能一屏放得下——设置页有「≤640dp 一屏」的硬约束
 * （历史上滚到底会把顶部输入框切成半截，像乱码）。</p>
 *
 * <p><b>为什么音色选择放在「模型」Tab：</b>设置 Tab 的高度余量很小，而音色（在线 mimo 的 9 个）
 * 与离线模型都属于「用哪个引擎/音色」这一类，放一起更顺。</p>
 */
public class SettingsActivity extends Activity implements ModelManager.Listener {

    private static final int REQ_IMPORT = 1001;

    // 设置 Tab
    private EditText etBaseUrl, etApiKey, etAsrModel, etChatModel, etSysPrompt, etTtsModel;
    private TextView tvFontPreview, tvWidthPreview, btnToggleKey;

    // 模型 Tab
    private EditText etModelSource;
    private TextView tvModelSummary, btnTabSettings, btnTabModels;
    private View settingsTab, modelsTab;
    private ModelManager models;
    /** 模型 id → 该行的进度条，避免每次回调都重新 find 一遍。 */
    private final Map<String, ProgressBar> bars = new HashMap<>();
    /** 本地导入的目标模型（选文件是异步的，得记住点的是哪一行）。 */
    private ModelCatalog.Spec pendingImport;

    private Prefs prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        prefs = new Prefs(this);
        models = new ModelManager(this);

        bindSettingsTab();
        bindModelsTab();
        loadAll();
        buildModelList();
        showTab(true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyModeStyle();
        refreshModelSummary();
    }

    // ── 设置 Tab ──────────────────────────────────────────

    private void bindSettingsTab() {
        etBaseUrl   = findViewById(R.id.etBaseUrl);
        etApiKey    = findViewById(R.id.etApiKey);
        etAsrModel  = findViewById(R.id.etAsrModel);
        etChatModel = findViewById(R.id.etChatModel);
        etSysPrompt = findViewById(R.id.etSysPrompt);
        etTtsModel = findViewById(R.id.etTtsModel);

        tvFontPreview  = findViewById(R.id.tvFontValue);
        tvWidthPreview = findViewById(R.id.tvWidthValue);
        btnToggleKey   = findViewById(R.id.btnToggleKey);

        btnToggleKey.setOnClickListener(v -> toggleKeyVisible());

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
            loadAll();
        });
    }

    private void loadAll() {
        etBaseUrl.setText(prefs.baseUrl());
        etApiKey.setText(prefs.apiKey());
        etAsrModel.setText(prefs.asrModel());
        etChatModel.setText(prefs.chatModel());
        etSysPrompt.setText(prefs.sysPrompt());
        etTtsModel.setText(prefs.ttsModel());
        tvFontPreview.setText(prefs.fontSp() + "sp");
        tvWidthPreview.setText(prefs.widthDp() + "dp");
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
        finish();
    }

    private void applyToPanel() {
        if (CaptureService.current != null && CaptureService.current.panel != null) {
            CaptureService.current.panel.applyFontSize(prefs.fontSp());
            CaptureService.current.panel.applyWidth(prefs.widthDp());
        }
    }

    // ── Tab 切换 ──────────────────────────────────────────

    private void bindModelsTab() {
        settingsTab = findViewById(R.id.settingsTab);
        modelsTab = findViewById(R.id.modelsTab);
        btnTabSettings = findViewById(R.id.btnTabSettings);
        btnTabModels = findViewById(R.id.btnTabModels);

        btnTabSettings.setOnClickListener(v -> showTab(true));
        btnTabModels.setOnClickListener(v -> showTab(false));

        etModelSource = findViewById(R.id.etModelSource);
        etModelSource.setText(prefs.modelBaseUrl());
        findViewById(R.id.btnSaveSource).setOnClickListener(v -> {
            prefs.putModelBaseUrl(etModelSource.getText().toString().trim());
            toast(getString(R.string.models_source_saved));
        });
        findViewById(R.id.btnRestoreSource).setOnClickListener(v ->
                applySource(Prefs.DEF_MODEL_BASE_URL, getString(R.string.models_source_restored)));

        findViewById(R.id.btnModeAuto).setOnClickListener(v -> setMode(Prefs.MODE_AUTO));
        findViewById(R.id.btnModeOnline).setOnClickListener(v -> setMode(Prefs.MODE_ONLINE));
        findViewById(R.id.btnModeOffline).setOnClickListener(v -> setMode(Prefs.MODE_OFFLINE));

        tvModelSummary = findViewById(R.id.tvModelSummary);
        buildVoicePickers();
        buildAsrPicker();
    }

    /** 只显示一个 Tab：两块内容各自一屏放得下，也让高度估算能跳过隐藏的那块。 */
    private void showTab(boolean settings) {
        settingsTab.setVisibility(settings ? View.VISIBLE : View.GONE);
        modelsTab.setVisibility(settings ? View.GONE : View.VISIBLE);
        styleChip(btnTabSettings, settings);
        styleChip(btnTabModels, !settings);
    }

    private void applySource(String value, String toastText) {
        prefs.putModelBaseUrl(value);
        etModelSource.setText(value);
        toast(toastText);
    }

    // ── 朗读音色（在线 mimo） ──────────────────────────────

    private void buildVoicePickers() {
        fillChips(findViewById(R.id.voiceEnChips), Prefs.TTS_VOICES_EN,
                prefs.ttsVoiceEnglish(), prefs::putTtsVoiceEnglish);
        fillChips(findViewById(R.id.voiceZhChips), Prefs.TTS_VOICES_ZH,
                prefs.ttsVoiceChinese(), prefs::putTtsVoiceChinese);
    }

    private void fillChips(LinearLayout row, String[] values, String current, Consumer<String> onPick) {
        row.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        for (String v : values) {
            TextView chip = (TextView) inflater.inflate(R.layout.item_chip, row, false);
            chip.setText(v);
            styleChip(chip, v.equals(current));
            chip.setOnClickListener(x -> {
                onPick.accept(v);
                buildVoicePickers();   // 两个语言都要重绘：选中态变了
            });
            row.addView(chip);
        }
    }

    // ── 离线识别模型选择（只列已装的） ──────────────────────

    private void buildAsrPicker() {
        LinearLayout row = findViewById(R.id.asrModelChips);
        row.removeAllViews();

        List<ModelCatalog.Spec> installed = new ArrayList<>();
        for (ModelCatalog.Spec s : ModelCatalog.all()) {
            if (s.kind == ModelCatalog.Kind.ASR && models.stateOf(s) == ModelManager.State.INSTALLED) {
                installed.add(s);
            }
        }
        if (installed.isEmpty()) {
            TextView hint = new TextView(this);
            hint.setText(R.string.models_pick_none);
            hint.setTextSize(12f);
            hint.setTextColor(getColor(R.color.text_tertiary));
            row.addView(hint);
            return;
        }
        LayoutInflater inflater = LayoutInflater.from(this);
        String current = prefs.offlineAsrModelId();
        for (ModelCatalog.Spec spec : installed) {
            TextView chip = (TextView) inflater.inflate(R.layout.item_chip, row, false);
            chip.setText(spec.labelRes);
            // 没显式选过时，实际生效的是推荐档/第一个已装档，这里如实标出来
            boolean selected = spec.id.equals(current)
                    || (current.isEmpty() && spec == installed.get(0));
            styleChip(chip, selected);
            chip.setOnClickListener(v -> {
                prefs.putOfflineAsrModelId(spec.id);
                buildAsrPicker();
            });
            row.addView(chip);
        }
    }

    // ── 模型列表 ──────────────────────────────────────────

    private void buildModelList() {
        LinearLayout list = findViewById(R.id.modelList);
        list.removeAllViews();
        bars.clear();

        LayoutInflater inflater = LayoutInflater.from(this);
        for (ModelCatalog.Spec spec : ModelCatalog.all()) {
            View row = inflater.inflate(R.layout.item_model, list, false);
            row.setTag(spec.id);

            ((TextView) row.findViewById(R.id.tvModelName)).setText(spec.labelRes);
            ProgressBar bar = row.findViewById(R.id.modelProgress);
            bars.put(spec.id, bar);

            row.findViewById(R.id.btnModelDownload).setOnClickListener(v -> download(spec));
            row.findViewById(R.id.btnModelImport).setOnClickListener(v -> startImport(spec));
            row.findViewById(R.id.btnModelDelete).setOnClickListener(v -> confirmDelete(spec, row));

            bindRow(row, spec);
            list.addView(row);
        }
    }

    /** 把 manager 里的状态刷到一行上（安装状态、体积、依赖、按钮可用性）。 */
    private void bindRow(View row, ModelCatalog.Spec spec) {
        TextView tvState = row.findViewById(R.id.tvModelState);
        TextView tvMeta = row.findViewById(R.id.tvModelMeta);
        ProgressBar bar = row.findViewById(R.id.modelProgress);

        ModelManager.State state = models.stateOf(spec);
        String size = ModelManager.formatSize(spec.sizeBytes);
        String license = getString(spec.licenseRes);
        String dep = models.missingDependency(spec);

        if (state == ModelManager.State.INSTALLED) {
            tvState.setText(R.string.model_state_installed);
            tvState.setTextColor(getColor(R.color.success));
        } else if (state == ModelManager.State.DOWNLOADING) {
            tvState.setText(R.string.model_state_downloading);
            tvState.setTextColor(getColor(R.color.accent_solid));
        } else {
            tvState.setText(R.string.model_state_not_installed);
            tvState.setTextColor(getColor(R.color.text_tertiary));
        }

        String meta = getString(R.string.model_meta, size, license)
                + " · " + getString(R.string.model_expected_file, spec.relativePath);
        if (dep != null) {
            meta += " · " + getString(R.string.models_dep_tag, depLabel(dep));
        }
        tvMeta.setText(meta);

        bar.setVisibility(state == ModelManager.State.DOWNLOADING ? View.VISIBLE : View.GONE);

        // 已装 → 只能删；未装 → 可下载/导入
        boolean installed = state == ModelManager.State.INSTALLED;
        row.findViewById(R.id.btnModelDownload).setVisibility(installed ? View.GONE : View.VISIBLE);
        row.findViewById(R.id.btnModelImport).setVisibility(installed ? View.GONE : View.VISIBLE);
        row.findViewById(R.id.btnModelDelete).setVisibility(installed ? View.VISIBLE : View.GONE);
    }

    private void refreshModelSummary() {
        tvModelSummary.setText(getString(R.string.models_summary,
                ModelManager.formatSize(models.installedBytes()),
                ModelManager.formatSize(models.usableBytes())));
    }

    private String depLabel(String depId) {
        ModelCatalog.Spec dep = ModelCatalog.byId(depId);
        return dep != null ? getString(dep.labelRes) : depId;
    }

    // ── 模式与动作 ────────────────────────────────────────

    private void setMode(int mode) {
        prefs.putEngineMode(mode);
        applyModeStyle();
    }

    /** 选中的模式用 pill_glass + accent 文字，其余用 bg_chip + 次级文字（与悬浮窗 Tab 同做法）。 */
    private void applyModeStyle() {
        int mode = prefs.engineMode();
        styleChip(findViewById(R.id.btnModeAuto), mode == Prefs.MODE_AUTO);
        styleChip(findViewById(R.id.btnModeOnline), mode == Prefs.MODE_ONLINE);
        styleChip(findViewById(R.id.btnModeOffline), mode == Prefs.MODE_OFFLINE);
    }

    private void styleChip(TextView chip, boolean selected) {
        chip.setBackgroundResource(selected ? R.drawable.pill_glass : R.drawable.bg_chip);
        chip.setTextColor(getColor(selected ? R.color.accent_solid : R.color.text_secondary));
    }

    private void download(ModelCatalog.Spec spec) {
        // 以「输入框里的当前内容」为准，并顺手保存：
        // 之前用的是已保存的值，用户填了新源没点保存 → 实际用旧值去下，报 404 完全对不上号（实测踩过）。
        String source = etModelSource.getText().toString().trim();
        prefs.putModelBaseUrl(source);
        models.install(spec, source, this);
    }

    private void startImport(ModelCatalog.Spec spec) {
        pendingImport = spec;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(Intent.createChooser(i, getString(R.string.model_import_title)), REQ_IMPORT);
        } catch (Exception e) {
            // 设备上没有文件选择器时别崩，给一句可读提示
            toast(getString(R.string.model_err_import, e.toString()));
        }
    }

    private void confirmDelete(ModelCatalog.Spec spec, View row) {
        new AlertDialog.Builder(this)
                .setMessage(getString(R.string.model_delete_confirm, getString(spec.labelRes)))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.model_action_delete, (d, w) -> {
                    models.delete(spec);
                    bindRow(row, spec);
                    refreshModelSummary();
                    buildAsrPicker();
                    toast(getString(R.string.model_delete_ok, getString(spec.labelRes)));
                })
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_IMPORT || resultCode != RESULT_OK || data == null) return;
        if (data.getData() == null || pendingImport == null) return;
        models.installFromFile(pendingImport, data.getData(), this);
        pendingImport = null;
    }

    // ── ModelManager.Listener（回调都在主线程） ──────────────

    @Override
    public void onStateChanged(ModelCatalog.Spec spec) {
        View row = findRow(spec);
        if (row != null) bindRow(row, spec);
        refreshModelSummary();
        buildAsrPicker();   // 装/删模型后，可选列表也变了
    }

    @Override
    public void onProgress(ModelCatalog.Spec spec, long done, long total) {
        ProgressBar bar = bars.get(spec.id);
        if (bar == null) return;
        if (total <= 0) {
            bar.setIndeterminate(true);
            return;
        }
        bar.setIndeterminate(false);
        int pct = (int) Math.min(100, done * 100 / total);
        bar.setProgress(pct);
        View row = findRow(spec);
        if (row != null) {
            ((TextView) row.findViewById(R.id.tvModelState))
                    .setText(getString(R.string.model_state_downloading_pct, pct));
        }
    }

    /**
     * 安装/下载失败：用对话框而不是 Toast。
     * 失败详情是「每个源分别因为什么挂的」多行文本，Toast 根本看不全——
     * 用户只能看到"下载失败"，排查不了。
     */
    @Override
    public void onError(ModelCatalog.Spec spec, String message) {
        View row = findRow(spec);
        if (row != null) bindRow(row, spec);
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.model_download_failed, getString(spec.labelRes)))
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private View findRow(ModelCatalog.Spec spec) {
        LinearLayout list = findViewById(R.id.modelList);
        for (int i = 0; i < list.getChildCount(); i++) {
            View row = list.getChildAt(i);
            if (spec.id.equals(row.getTag())) return row;
        }
        return null;
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
