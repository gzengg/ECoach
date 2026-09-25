package com.rd.englishcoach;

import android.app.Activity;
import android.content.Intent;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型页：离线模型全生命周期 + 运行模式选择。
 *
 * <p>由旧设置页「模型」Tab 原样迁来（{@code ModelManager}/{@code ModelCatalog} 零改动），
 * 两处变化：</p>
 * <ul>
 *   <li>下载源编辑从页内表单移到顶栏入口的 Bottom Sheet（页内少一块卡片）；
 *       「顺手保存」的逻辑收敛到 sheet 的保存按钮，下载一律用已保存值；</li>
 *   <li>删除确认 / 下载失败详情从 AlertDialog 改为 Bottom Sheet
 *       （失败详情是「每个源各自原因」的多行文本，必须看得全）。</li>
 * </ul>
 */
final class ModelsPage implements ModelManager.Listener {

    private static final int REQ_IMPORT = 1001;

    private final MainActivity act;
    private final Prefs prefs;
    private final ModelManager models;
    private final BottomSheetPanel sheet;

    private final TextView tvModelSummary;
    private final LinearLayout asrChips;
    private final LinearLayout modelList;
    private final View btnModeAuto, btnModeOnline, btnModeOffline;
    /** 模型 id → 该行的进度条，避免每次回调都重新 find 一遍。 */
    private final Map<String, ProgressBar> bars = new HashMap<>();
    /** 本地导入的目标模型（选文件是异步的，得记住点的是哪一行）。 */
    private ModelCatalog.Spec pendingImport;

    ModelsPage(MainActivity act, View root) {
        this.act = act;
        this.prefs = new Prefs(act);
        this.models = new ModelManager(act);
        this.sheet = new BottomSheetPanel(act);

        tvModelSummary = root.findViewById(R.id.tvModelSummary);
        asrChips = root.findViewById(R.id.asrModelChips);
        modelList = root.findViewById(R.id.modelList);

        btnModeAuto = root.findViewById(R.id.btnModeAuto);
        btnModeOnline = root.findViewById(R.id.btnModeOnline);
        btnModeOffline = root.findViewById(R.id.btnModeOffline);
        btnModeAuto.setOnClickListener(v -> setMode(Prefs.MODE_AUTO));
        btnModeOnline.setOnClickListener(v -> setMode(Prefs.MODE_ONLINE));
        btnModeOffline.setOnClickListener(v -> setMode(Prefs.MODE_OFFLINE));

        buildModelList();
        buildAsrPicker();
    }

    /** Tab 切进本页时刷新（安装状态可能被外部改动过）。 */
    void refresh() {
        applyModeStyle();
        refreshModelSummary();
        buildAsrPicker();
    }

    /** 自动/仅离线模式下识别模型缺失 → 「模型」Tab 亮 warn 角标。 */
    boolean asrOfflineMissing() {
        if (prefs.engineMode() == Prefs.MODE_ONLINE) return false;
        for (ModelCatalog.Spec s : ModelCatalog.all()) {
            if (s.kind == ModelCatalog.Kind.ASR
                    && models.stateOf(s) == ModelManager.State.INSTALLED) return false;
        }
        return true;
    }

    // ── 顶栏「下载源」：Bottom Sheet 编辑 ─────────────────

    void showSourceSheet() {
        LinearLayout box = verticalBox();

        TextView title = new TextView(act);
        title.setText(R.string.models_source_title);
        title.setTextSize(15);
        title.setTextColor(act.getColor(R.color.text_primary));
        box.addView(title);

        EditText input = new EditText(act);
        input.setText(prefs.modelBaseUrl());
        input.setTextSize(13);
        input.setBackgroundResource(R.drawable.bg_input);
        input.setPadding(act.dp(12), act.dp(8), act.dp(12), act.dp(8));
        LinearLayout.LayoutParams ip = wrapWrap();
        ip.topMargin = act.dp(8);
        box.addView(input, ip);

        TextView save = textButton(R.string.models_source_save, R.color.accent_solid);
        save.setOnClickListener(v -> {
            // sheet 里点保存才生效；下载一律用已保存值
            prefs.putModelBaseUrl(input.getText().toString().trim());
            toast(act.getString(R.string.models_source_saved));
            sheet.dismiss();
        });
        box.addView(save, actionRow());

        TextView restore = textButton(R.string.models_source_restore, R.color.text_secondary);
        restore.setOnClickListener(v -> {
            prefs.putModelBaseUrl(Prefs.DEF_MODEL_BASE_URL);
            input.setText(Prefs.DEF_MODEL_BASE_URL);
            toast(act.getString(R.string.models_source_restored));
        });
        box.addView(restore, actionRow());
        sheet.show(box);
    }

    // ── ModelManager.Listener（回调都在主线程） ────────────

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
                    .setText(act.getString(R.string.model_state_downloading_pct, pct));
        }
    }

    /**
     * 安装/下载失败：用 Bottom Sheet 而不是 Toast。
     * 失败详情是「每个源分别因为什么挂的」多行文本，Toast 根本看不全。
     */
    @Override
    public void onError(ModelCatalog.Spec spec, String message) {
        View row = findRow(spec);
        if (row != null) bindRow(row, spec);

        LinearLayout box = verticalBox();
        TextView title = new TextView(act);
        title.setText(act.getString(R.string.model_download_failed, act.getString(spec.labelRes)));
        title.setTextSize(15);
        title.setTextColor(act.getColor(R.color.text_primary));
        box.addView(title);
        TextView msg = new TextView(act);
        msg.setText(message);
        msg.setTextSize(13);
        msg.setTextColor(act.getColor(R.color.text_secondary));
        msg.setPadding(0, act.dp(8), 0, 0);
        box.addView(msg);
        sheet.show(box);
    }

    // ── 本地导入（结果由 MainActivity 转发） ────────────────

    void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_IMPORT || resultCode != Activity.RESULT_OK || data == null) return;
        if (data.getData() == null || pendingImport == null) return;
        models.installFromFile(pendingImport, data.getData(), this);
        pendingImport = null;
    }

    // ── 识别模型选择（只列已装的） ────────────────────────

    private void buildAsrPicker() {
        asrChips.removeAllViews();

        List<ModelCatalog.Spec> installed = new ArrayList<>();
        for (ModelCatalog.Spec s : ModelCatalog.all()) {
            if (s.kind == ModelCatalog.Kind.ASR
                    && models.stateOf(s) == ModelManager.State.INSTALLED) {
                installed.add(s);
            }
        }
        if (installed.isEmpty()) {
            TextView hint = new TextView(act);
            hint.setText(R.string.models_pick_none);
            hint.setTextSize(12f);
            hint.setTextColor(act.getColor(R.color.text_tertiary));
            asrChips.addView(hint);
            return;
        }
        LayoutInflater inflater = LayoutInflater.from(act);
        String current = prefs.offlineAsrModelId();
        for (ModelCatalog.Spec spec : installed) {
            TextView chip = (TextView) inflater.inflate(R.layout.item_chip, asrChips, false);
            chip.setText(spec.labelRes);
            // 没显式选过时，实际生效的是推荐档/第一个已装档，这里如实标出来
            boolean selected = spec.id.equals(current)
                    || (current.isEmpty() && spec == installed.get(0));
            styleChip(chip, selected);
            chip.setOnClickListener(v -> {
                prefs.putOfflineAsrModelId(spec.id);
                buildAsrPicker();
            });
            asrChips.addView(chip);
        }
    }

    // ── 模型列表 ─────────────────────────────────────────

    private void buildModelList() {
        modelList.removeAllViews();
        bars.clear();

        LayoutInflater inflater = LayoutInflater.from(act);
        for (ModelCatalog.Spec spec : ModelCatalog.all()) {
            View row = inflater.inflate(R.layout.item_model, modelList, false);
            row.setTag(spec.id);

            ((TextView) row.findViewById(R.id.tvModelName)).setText(spec.labelRes);
            ProgressBar bar = row.findViewById(R.id.modelProgress);
            bars.put(spec.id, bar);

            row.findViewById(R.id.btnModelDownload).setOnClickListener(v -> download(spec));
            row.findViewById(R.id.btnModelImport).setOnClickListener(v -> startImport(spec));
            row.findViewById(R.id.btnModelDelete).setOnClickListener(v -> confirmDelete(spec));

            bindRow(row, spec);
            modelList.addView(row);
        }
    }

    /** 把 manager 里的状态刷到一行上（安装状态、体积、依赖、按钮可用性）。 */
    private void bindRow(View row, ModelCatalog.Spec spec) {
        TextView tvState = row.findViewById(R.id.tvModelState);
        TextView tvMeta = row.findViewById(R.id.tvModelMeta);
        ProgressBar bar = row.findViewById(R.id.modelProgress);

        ModelManager.State state = models.stateOf(spec);
        String size = ModelManager.formatSize(spec.sizeBytes);
        String license = act.getString(spec.licenseRes);
        String dep = models.missingDependency(spec);

        if (state == ModelManager.State.INSTALLED) {
            tvState.setText(R.string.model_state_installed);
            tvState.setTextColor(act.getColor(R.color.success));
        } else if (state == ModelManager.State.DOWNLOADING) {
            tvState.setText(R.string.model_state_downloading);
            tvState.setTextColor(act.getColor(R.color.accent_solid));
        } else {
            tvState.setText(R.string.model_state_not_installed);
            tvState.setTextColor(act.getColor(R.color.text_tertiary));
        }

        String meta = act.getString(R.string.model_meta, size, license)
                + " · " + act.getString(R.string.model_expected_file, spec.relativePath);
        if (dep != null) {
            meta += " · " + act.getString(R.string.models_dep_tag, depLabel(dep));
        }
        tvMeta.setText(meta);

        bar.setVisibility(state == ModelManager.State.DOWNLOADING ? View.VISIBLE : View.GONE);

        // 已装 → 只能删；未装 → 可下载/导入（动作按状态动态显隐）
        boolean installed = state == ModelManager.State.INSTALLED;
        row.findViewById(R.id.btnModelDownload).setVisibility(installed ? View.GONE : View.VISIBLE);
        row.findViewById(R.id.btnModelImport).setVisibility(installed ? View.GONE : View.VISIBLE);
        row.findViewById(R.id.btnModelDelete).setVisibility(installed ? View.VISIBLE : View.GONE);
    }

    private void refreshModelSummary() {
        tvModelSummary.setText(act.getString(R.string.models_summary,
                ModelManager.formatSize(models.installedBytes()),
                ModelManager.formatSize(models.usableBytes())));
    }

    private String depLabel(String depId) {
        ModelCatalog.Spec dep = ModelCatalog.byId(depId);
        return dep != null ? act.getString(dep.labelRes) : depId;
    }

    private View findRow(ModelCatalog.Spec spec) {
        for (int i = 0; i < modelList.getChildCount(); i++) {
            View row = modelList.getChildAt(i);
            if (spec.id.equals(row.getTag())) return row;
        }
        return null;
    }

    // ── 模式与动作 ──────────────────────────────────────

    private void setMode(int mode) {
        prefs.putEngineMode(mode);
        applyModeStyle();
    }

    /** 选中的模式用 pill_glass + accent 文字，其余用 bg_chip + 次级文字（与悬浮窗 Tab 同做法）。 */
    private void applyModeStyle() {
        int mode = prefs.engineMode();
        styleChip((TextView) btnModeAuto, mode == Prefs.MODE_AUTO);
        styleChip((TextView) btnModeOnline, mode == Prefs.MODE_ONLINE);
        styleChip((TextView) btnModeOffline, mode == Prefs.MODE_OFFLINE);
    }

    private void styleChip(TextView chip, boolean selected) {
        chip.setBackgroundResource(selected ? R.drawable.pill_glass : R.drawable.bg_chip);
        chip.setTextColor(act.getColor(selected ? R.color.accent_solid : R.color.text_secondary));
    }

    private void download(ModelCatalog.Spec spec) {
        // 下载源在顶栏 sheet 里编辑（保存即生效），下载一律用已保存值
        models.install(spec, prefs.modelBaseUrl(), this);
    }

    private void startImport(ModelCatalog.Spec spec) {
        pendingImport = spec;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            act.startActivityForResult(
                    Intent.createChooser(i, act.getString(R.string.model_import_title)), REQ_IMPORT);
        } catch (Exception e) {
            // 设备上没有文件选择器时别崩，给一句可读提示
            toast(act.getString(R.string.model_err_import, e.toString()));
        }
    }

    /** 删除确认：Bottom Sheet（替代原 AlertDialog）。 */
    private void confirmDelete(ModelCatalog.Spec spec) {
        LinearLayout box = verticalBox();
        TextView msg = new TextView(act);
        msg.setText(act.getString(R.string.model_delete_confirm, act.getString(spec.labelRes)));
        msg.setTextSize(14);
        msg.setTextColor(act.getColor(R.color.text_primary));
        box.addView(msg);

        LinearLayout actions = new LinearLayout(act);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END);
        LinearLayout.LayoutParams lp = wrapWrap();
        lp.topMargin = act.dp(12);
        TextView cancel = textButton(R.string.dialog_cancel, R.color.text_secondary);
        cancel.setOnClickListener(v -> sheet.dismiss());
        actions.addView(cancel);
        TextView del = textButton(R.string.model_action_delete, R.color.danger);
        del.setOnClickListener(v -> {
            sheet.dismiss();
            models.delete(spec);
            View row = findRow(spec);
            if (row != null) bindRow(row, spec);
            refreshModelSummary();
            buildAsrPicker();
            toast(act.getString(R.string.model_delete_ok, act.getString(spec.labelRes)));
        });
        actions.addView(del);
        box.addView(actions, lp);
        sheet.show(box);
    }

    // ── Sheet 内容小工具 ─────────────────────────────────

    private LinearLayout verticalBox() {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        return box;
    }

    private LinearLayout.LayoutParams wrapWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams actionRow() {
        LinearLayout.LayoutParams lp = wrapWrap();
        lp.gravity = Gravity.END;
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
