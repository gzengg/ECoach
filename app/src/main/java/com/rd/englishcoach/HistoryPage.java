package com.rd.englishcoach;

import android.content.Intent;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 历史页：转录 + 取词 + 文件转录记录的浏览 / 检索 / 操作。
 *
 * <p>三种记录分 Tab（转录 / 取词 / 文件）+ 计数，切 Tab 原地重渲染：
 * 前两者同一个 {@link HistoryStore}（一段一句话），第三者 {@link FileTranscriptStore}
 * （一小时文件上千段、带时间轴，体量与结构都不同，所以分开存）。</p>
 *
 * <p>搜索框过滤（对原文与答案做包含匹配，不区分大小写）；
 * 点条目 → Bottom Sheet 看全文；长按 → PopupMenu（朗读 / 复制 / 删除）。</p>
 *
 * <p>⚠️ 删除必须用「原数组索引」{@code deleteAt(idx)}：列表是倒序渲染且按类型过滤过，
 * 用行号删会删错条目。文件记录走 id 删除，不受这个限制。</p>
 */
final class HistoryPage {

    /** 通知栏点「已完成」进来时要求直接落在文件 Tab。 */
    static final String EXTRA_FILE_TAB = "file_tab";

    private static final int TAB_TRANSCRIPT = 0;
    private static final int TAB_GRAB = 1;
    private static final int TAB_FILES = 2;

    private final MainActivity act;
    private final HistoryStore store;
    private final FileTranscriptStore fileStore;
    private final BottomSheetPanel sheet;

    private final EditText etSearch;
    private final TextView btnTranscript, btnGrab, btnFiles;
    private final LinearLayout list;
    private final View emptyView;
    private final TextView tvEmptyTitle, tvEmptyMsg, btnEmptyAction;

    private int tab = TAB_TRANSCRIPT;

    HistoryPage(MainActivity act, View root) {
        this.act = act;
        this.store = new HistoryStore(act);
        this.fileStore = new FileTranscriptStore(act);
        this.sheet = new BottomSheetPanel(act);

        etSearch = root.findViewById(R.id.etHistorySearch);
        btnTranscript = root.findViewById(R.id.btnHistTranscript);
        btnGrab = root.findViewById(R.id.btnHistGrab);
        btnFiles = root.findViewById(R.id.btnHistFiles);
        list = root.findViewById(R.id.historyList);
        emptyView = root.findViewById(R.id.historyEmpty);
        tvEmptyTitle = root.findViewById(R.id.tvEmptyTitle);
        tvEmptyMsg = root.findViewById(R.id.tvEmptyMsg);
        btnEmptyAction = root.findViewById(R.id.btnEmptyAction);

        btnTranscript.setOnClickListener(v -> { tab = TAB_TRANSCRIPT; render(); });
        btnGrab.setOnClickListener(v -> { tab = TAB_GRAB; render(); });
        btnFiles.setOnClickListener(v -> { tab = TAB_FILES; render(); });
        // 输入即过滤；不需要点搜索按钮
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { render(); }
        });
    }

    /** Tab 切进本页时刷新（可能刚产生过新记录）。 */
    void refresh() { render(); }

    /** 切到文件 Tab（通知栏点进来 / 转录完成后跳转用）。 */
    void selectFileTab() {
        tab = TAB_FILES;
        render();
    }

    int currentTab() {
        return tab;
    }

    /** 顶栏「清空全部」：Bottom Sheet 二次确认（替代原 AlertDialog 的 neutral 按钮）。 */
    void confirmClearAll() {
        int n = tab == TAB_FILES ? fileStore.size() : store.size();
        if (n == 0) { render(); return; }

        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView msg = new TextView(act);
        msg.setText(act.getString(tab == TAB_FILES
                ? R.string.file_clear_confirm : R.string.history_clear_confirm, n));
        msg.setTextSize(14);
        msg.setTextColor(act.getColor(R.color.text_primary));
        box.addView(msg);

        LinearLayout actions = new LinearLayout(act);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = act.dp(12);
        TextView cancel = new TextView(act);
        cancel.setText(R.string.dialog_cancel);
        cancel.setTextSize(13);
        cancel.setTextColor(act.getColor(R.color.text_secondary));
        cancel.setBackgroundResource(R.drawable.bg_chip);
        int pad = act.dp(12);
        cancel.setPadding(pad, act.dp(8), pad, act.dp(8));
        cancel.setClickable(true);
        cancel.setOnClickListener(v -> sheet.dismiss());
        actions.addView(cancel);
        TextView clear = new TextView(act);
        int h = act.dp(8);
        clear.setText(R.string.history_clear_all);
        clear.setTextSize(13);
        clear.setTextColor(act.getColor(R.color.danger));
        clear.setBackgroundResource(R.drawable.bg_chip);
        clear.setPadding(act.dp(12), h, act.dp(12), h);
        clear.setClickable(true);
        clear.setOnClickListener(v -> {
            sheet.dismiss();
            if (tab == TAB_FILES) fileStore.clear();
            else store.clear();
            act.setStatusText(act.getString(R.string.status_history_cleared));
            render();
        });
        actions.addView(clear);
        box.addView(actions, lp);
        sheet.show(box);
    }

    // ── 渲染 ──────────────────────────────

    private void render() {
        if (tab == TAB_FILES) {
            renderFiles();
            return;
        }
        List<HistoryStore.Entry> all = store.getAll();
        String q = query();

        // Tab 计数始终展示全量（不含搜索过滤）
        styleTab(btnTranscript, act.getString(R.string.history_tab_transcript,
                HistoryStore.countByType(all, HistoryStore.TYPE_TRANSCRIPT)), tab == TAB_TRANSCRIPT);
        styleTab(btnGrab, act.getString(R.string.history_tab_grab,
                HistoryStore.countByType(all, HistoryStore.TYPE_GRAB)), tab == TAB_GRAB);
        styleTab(btnFiles, act.getString(R.string.history_tab_files, fileStore.size()),
                false);

        boolean wantGrab = tab == TAB_GRAB;
        list.removeAllViews();
        boolean any = false;
        for (int i = all.size() - 1; i >= 0; i--) {
            final int idx = i;
            HistoryStore.Entry e = all.get(i);
            if (e.isGrab() != wantGrab) continue;
            if (!q.isEmpty() && !matches(e, q)) continue;
            any = true;
            list.addView(buildRow(e, idx, i > 0));
        }

        // ── 空状态：三种来源，引导各不相同 ──
        if (all.isEmpty()) {
            showEmpty(act.getString(R.string.history_empty_title),
                    act.getString(R.string.history_empty_guide),
                    act.getString(R.string.history_empty_cta),
                    v -> act.switchTab(BottomBar.TAB_LISTEN));
        } else if (!any && !q.isEmpty()) {
            showEmpty(act.getString(R.string.history_search_empty_title, q),
                    act.getString(R.string.history_empty_guide),
                    act.getString(R.string.history_search_clear),
                    v -> etSearch.setText(""));
        } else if (!any) {
            // 有记录但当前类型/过滤下没有
            showEmpty(act.getString(R.string.history_empty), "", null, null);
        } else {
            emptyView.setVisibility(View.GONE);
        }
    }

    /** 文件 Tab：列表项是「文件名 + 状态 + 时长/字数」，转录中也能看到进度。 */
    private void renderFiles() {
        List<FileTranscriptStore.Entry> all = fileStore.getRecent();
        String q = query();

        styleTab(btnTranscript, act.getString(R.string.history_tab_transcript,
                HistoryStore.countByType(store.getAll(), HistoryStore.TYPE_TRANSCRIPT)), false);
        styleTab(btnGrab, act.getString(R.string.history_tab_grab,
                HistoryStore.countByType(store.getAll(), HistoryStore.TYPE_GRAB)), false);
        styleTab(btnFiles, act.getString(R.string.history_tab_files, all.size()), true);

        list.removeAllViews();
        boolean any = false;
        for (FileTranscriptStore.Entry e : all) {
            if (!q.isEmpty() && !matchesFile(e, q)) continue;
            any = true;
            list.addView(buildFileRow(e, all.indexOf(e) < all.size() - 1));
        }

        if (all.isEmpty()) {
            showEmpty(act.getString(R.string.file_empty),
                    act.getString(R.string.file_entry_desc),
                    act.getString(R.string.file_entry_btn),
                    v -> act.pickFiles());
        } else if (!any) {
            showEmpty(act.getString(R.string.history_search_empty_title, q),
                    act.getString(R.string.history_empty_guide),
                    act.getString(R.string.history_search_clear),
                    v -> etSearch.setText(""));
        } else {
            emptyView.setVisibility(View.GONE);
        }
    }

    private String query() {
        return etSearch.getText().toString().trim().toLowerCase(Locale.getDefault());
    }

    /** 搜索匹配：原文或答案包含关键字（不区分大小写）。 */
    private boolean matches(HistoryStore.Entry e, String qLower) {
        return contains(e.transcript, qLower) || contains(e.answer, qLower);
    }

    /** 文件记录按文件名或全文匹配（长文件全文很大，但只在有搜索词时才走到这里）。 */
    private boolean matchesFile(FileTranscriptStore.Entry e, String qLower) {
        return contains(e.fileName, qLower) || contains(e.fullText(), qLower);
    }

    private static boolean contains(String s, String qLower) {
        return s != null && s.toLowerCase(Locale.getDefault()).contains(qLower);
    }

    private void showEmpty(String title, String msg, String cta, View.OnClickListener onCta) {
        tvEmptyTitle.setText(title);
        tvEmptyMsg.setText(msg);
        tvEmptyMsg.setVisibility(msg.isEmpty() ? View.GONE : View.VISIBLE);
        if (cta != null) {
            btnEmptyAction.setText(cta);
            btnEmptyAction.setVisibility(View.VISIBLE);
            btnEmptyAction.setOnClickListener(onCta);
        } else {
            btnEmptyAction.setVisibility(View.GONE);
        }
        emptyView.setVisibility(View.VISIBLE);
    }

    // ── 条目行：听力转录 / 取词 ────────────

    /** 一条记录：时间 + 摘要；点 → 全文 sheet；长按 → 操作菜单。 */
    private View buildRow(HistoryStore.Entry e, int idx, boolean showSep) {
        String time = stamp(e.timestamp);
        String body = entryBody(e);

        TextView tv = new TextView(act);
        tv.setText(time + "\n" + summarize(e));
        tv.setTextSize(14);
        tv.setTextColor(act.getColor(R.color.text_primary));
        int pad = act.dp(16);
        tv.setPadding(0, pad / 2, 0, pad / 2);
        tv.setClickable(true);
        tv.setFocusable(true);
        tv.setOnClickListener(v -> showDetail(e, body, idx));
        tv.setOnLongClickListener(v -> { showMenu(v, e, idx); return true; });

        return withSeparator(tv, showSep);
    }

    /** 一条文件记录：时间 + 文件名 + 状态/时长/字数。 */
    private View buildFileRow(FileTranscriptStore.Entry e, boolean showSep) {
        TextView tv = new TextView(act);
        tv.setText(stamp(e.createdAt) + "\n" + e.fileName + "\n" + fileSummary(e));
        tv.setTextSize(14);
        tv.setTextColor(act.getColor(R.color.text_primary));
        int pad = act.dp(16);
        tv.setPadding(0, pad / 2, 0, pad / 2);
        tv.setClickable(true);
        tv.setFocusable(true);
        tv.setOnClickListener(v -> showFileDetail(e));
        tv.setOnLongClickListener(v -> { showFileMenu(v, e); return true; });

        return withSeparator(tv, showSep);
    }

    private View withSeparator(View row, boolean showSep) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(row);
        if (showSep) {
            View sep = new View(act);
            sep.setBackgroundColor(act.getColor(R.color.stroke_soft));
            box.addView(sep, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1));
        }
        return box;
    }

    private static String stamp(long ms) {
        return new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(new Date(ms));
    }

    /** 文件记录的一行摘要：状态 + 时长 · 字数（时长未知时省略）。 */
    private String fileSummary(FileTranscriptStore.Entry e) {
        String state = fileStateText(e);
        String dur = formatDuration(e.durationMs);
        String meta = e.charCount() > 0
                ? act.getString(R.string.file_item_meta, dur.isEmpty() ? state : dur, e.charCount())
                : state;
        if (!dur.isEmpty() && e.charCount() > 0) meta = state + " · " + meta;
        return meta;
    }

    private String fileStateText(FileTranscriptStore.Entry e) {
        String s = e.status;
        if (FileTranscriptStore.STATUS_RUNNING.equals(s)) {
            return act.getString(R.string.file_state_running, Math.round(e.progress() * 100));
        }
        if (FileTranscriptStore.STATUS_PENDING.equals(s)) {
            return act.getString(R.string.file_state_pending);
        }
        if (FileTranscriptStore.STATUS_FAILED.equals(s)) {
            return e.error != null && !e.error.isEmpty()
                    ? act.getString(R.string.file_state_failed) + "：" + e.error
                    : act.getString(R.string.file_state_failed);
        }
        if (FileTranscriptStore.STATUS_CANCELED.equals(s)) {
            return act.getString(R.string.file_state_canceled);
        }
        if (FileTranscriptStore.STATUS_INTERRUPTED.equals(s)) {
            return act.getString(R.string.file_state_interrupted);
        }
        return act.getString(R.string.file_state_done);
    }

    /** 毫秒 → 「12:34」/「1:02:03」；未知（0）返回空串。 */
    static String formatDuration(long ms) {
        if (ms <= 0) return "";
        long total = ms / 1000;
        long h = total / 3600;
        long m = (total % 3600) / 60;
        long s = total % 60;
        return h > 0 ? String.format(Locale.getDefault(), "%d:%02d:%02d", h, m, s)
                : String.format(Locale.getDefault(), "%d:%02d", m, s);
    }

    /** 全文（复制 / sheet 用）：取词 = 原文 → 译文；转录 = 原文 + 答。 */
    private String entryBody(HistoryStore.Entry e) {
        StringBuilder sb = new StringBuilder();
        sb.append(e.transcript);
        if (e.isGrab()) {
            if (e.answer != null && !e.answer.isEmpty()) sb.append(" → ").append(e.answer);
        } else if (e.answer != null) {
            sb.append("\n").append(act.getString(R.string.history_answer_prefix)).append(e.answer);
        }
        return sb.toString();
    }

    /** 列表摘要：最多两行，避免长记录把列表撑爆。 */
    private String summarize(HistoryStore.Entry e) {
        String body = entryBody(e).replace("\n", " ");
        return body.length() <= 80 ? body : body.substring(0, 80) + "…";
    }

    // ── 操作菜单 ──────────────────────────

    /** 长按操作菜单：朗读（服务在跑才可用）/ 复制 / 删除。 */
    private void showMenu(View anchor, HistoryStore.Entry e, int idx) {
        PopupMenu menu = new PopupMenu(act, anchor, Gravity.END);
        String body = entryBody(e);
        if (act.canSpeak()) menu.getMenu().add(R.string.menu_speak);
        menu.getMenu().add(R.string.menu_copy);
        menu.getMenu().add(R.string.menu_delete);
        menu.setOnMenuItemClickListener(item -> {
            CharSequence title = item.getTitle();
            if (act.getString(R.string.menu_speak).contentEquals(title)) {
                act.speak("history:" + e.timestamp, body);
            } else if (act.getString(R.string.menu_copy).contentEquals(title)) {
                act.copyText(body);
            } else {
                store.deleteAt(idx);
                act.setStatusText(act.getString(R.string.status_deleted));
                render(); // 原地刷新，保留当前 Tab 与搜索词
            }
            return true;
        });
        menu.show();
    }

    /** 文件记录长按：复制 / 删除（朗读与继续放详情页，避免菜单过长）。 */
    private void showFileMenu(View anchor, FileTranscriptStore.Entry e) {
        PopupMenu menu = new PopupMenu(act, anchor, Gravity.END);
        menu.getMenu().add(R.string.file_action_copy);
        menu.getMenu().add(R.string.file_action_share);
        if (canResume(e)) menu.getMenu().add(R.string.file_action_resume);
        menu.getMenu().add(R.string.file_action_delete);
        menu.setOnMenuItemClickListener(item -> {
            CharSequence title = item.getTitle();
            if (act.getString(R.string.file_action_copy).contentEquals(title)) {
                act.copyText(e.fullText());
            } else if (act.getString(R.string.file_action_share).contentEquals(title)) {
                act.shareText(e.fullText(), e.fileName);
            } else if (act.getString(R.string.file_action_resume).contentEquals(title)) {
                resumeTranscribe(e);
            } else {
                fileStore.delete(e.id);
                act.setStatusText(act.getString(R.string.status_deleted));
                render();
            }
            return true;
        });
        menu.show();
    }

    // ── 详情 Sheet ────────────────────────

    /** 点条目：Bottom Sheet 展示全文 + 复制 / 删除。 */
    private void showDetail(HistoryStore.Entry e, String body, int idx) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);

        TextView text = new TextView(act);
        text.setText(body);
        text.setTextSize(14);
        text.setTextColor(act.getColor(R.color.text_primary));
        text.setTextIsSelectable(true);
        box.addView(text);

        LinearLayout actions = new LinearLayout(act);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = act.dp(12);
        TextView btnCopy = actionButton(R.string.menu_copy, act.getColor(R.color.success));
        btnCopy.setOnClickListener(v -> act.copyText(body));
        actions.addView(btnCopy);
        TextView btnDelete = actionButton(R.string.menu_delete, act.getColor(R.color.danger));
        btnDelete.setOnClickListener(v -> {
            sheet.dismiss();
            store.deleteAt(idx);
            act.setStatusText(act.getString(R.string.status_deleted));
            render();
        });
        actions.addView(btnDelete);
        box.addView(actions, lp);

        sheet.show(box);
    }

    /** 文件记录详情：摘要 + 全文 + 复制 / 朗读 / 继续 / 删除。 */
    private void showFileDetail(FileTranscriptStore.Entry e) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);

        TextView head = new TextView(act);
        head.setText(e.fileName + "\n" + fileSummary(e));
        head.setTextSize(13);
        head.setTextColor(act.getColor(R.color.text_secondary));
        box.addView(head);

        String text = e.fullText();
        if (text.isEmpty()) text = act.getString(R.string.file_empty_text);
        TextView tv = new TextView(act);
        tv.setText(text);
        tv.setTextSize(14);
        tv.setTextColor(act.getColor(R.color.text_primary));
        tv.setTextIsSelectable(true);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = act.dp(12);
        box.addView(tv, tlp);

        // 操作分三行：动作多，一行在窄屏上会挤成一团
        LinearLayout row1 = actionRow();
        TextView btnCopy = actionButton(R.string.file_action_copy, act.getColor(R.color.success));
        btnCopy.setOnClickListener(v -> act.copyText(e.fullText()));
        row1.addView(btnCopy);
        TextView btnShare = actionButton(R.string.file_action_share, act.getColor(R.color.text_primary));
        btnShare.setOnClickListener(v -> act.shareText(e.fullText(), e.fileName));
        row1.addView(btnShare);
        if (act.canSpeak() && !e.fullText().isEmpty()) {
            TextView btnSpeak = actionButton(R.string.file_action_speak,
                    act.getColor(R.color.text_primary));
            btnSpeak.setOnClickListener(v -> act.speak("file:" + e.id, e.fullText()));
            row1.addView(btnSpeak);
        }
        box.addView(row1);

        // 导出：字幕需要逐段时间轴，在线接口拿不到（srtAvailable=false）→ 先提示原因
        LinearLayout row2 = actionRow();
        TextView btnTxt = actionButton(R.string.file_action_export_txt,
                act.getColor(R.color.text_primary));
        btnTxt.setOnClickListener(v -> exportTxt(e));
        row2.addView(btnTxt);
        TextView btnSrt = actionButton(R.string.file_action_export_srt,
                act.getColor(R.color.text_primary));
        btnSrt.setOnClickListener(v -> exportSrt(e));
        row2.addView(btnSrt);
        box.addView(row2);

        LinearLayout row3 = actionRow();
        if (canResume(e)) {
            TextView btnResume = actionButton(R.string.file_action_resume,
                    act.getColor(R.color.accent_solid));
            btnResume.setOnClickListener(v -> resumeTranscribe(e));
            row3.addView(btnResume);
        }
        TextView btnDelete = actionButton(R.string.file_action_delete, act.getColor(R.color.danger));
        btnDelete.setOnClickListener(v -> {
            sheet.dismiss();
            fileStore.delete(e.id);
            act.setStatusText(act.getString(R.string.status_deleted));
            render();
        });
        row3.addView(btnDelete);
        box.addView(row3);

        sheet.show(box);
    }

    /** 导出文本（服务在跑时不拦：只读已完成的分段，不影响识别）。 */
    private void exportTxt(FileTranscriptStore.Entry e) {
        String text = TranscriptExport.toTxt(e.segments);
        if (text.isEmpty()) {
            act.setStatusText(act.getString(R.string.file_empty_text));
            return;
        }
        act.exportTranscript(text, e.fileName, false);
    }

    /** 导出字幕：没时间轴就不生成文件，直接说清为什么（在线只给纯文本）。 */
    private void exportSrt(FileTranscriptStore.Entry e) {
        if (!e.srtAvailable) {
            act.setStatusText(act.getString(R.string.file_srt_offline_only));
            return;
        }
        String srt = TranscriptExport.toSrt(e.segments);
        if (srt.isEmpty()) {
            act.setStatusText(act.getString(R.string.file_empty_text));
            return;
        }
        act.exportTranscript(srt, e.fileName, true);
    }

    /** 能不能「继续」：中间态且当前没有任务在跑（RUNNING 可能是上次被杀留下的）。 */
    private static boolean canResume(FileTranscriptStore.Entry e) {
        return FileTranscriptStore.isResumable(e.status) && !FileTranscribeService.running;
    }

    private LinearLayout actionRow() {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.END);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = act.dp(12);
        row.setLayoutParams(lp);
        return row;
    }

    private void resumeTranscribe(FileTranscriptStore.Entry e) {
        if (!act.fileTranscribeAllowed()) return;
        sheet.dismiss();
        FileTranscribeService.resume(act, e.id);
        act.setStatusText(act.getString(R.string.file_resumed));
        render();
    }

    private TextView actionButton(int textRes, int color) {
        TextView tv = new TextView(act);
        tv.setText(textRes);
        tv.setTextSize(13);
        tv.setTextColor(color);
        int pad = act.dp(12);
        tv.setPadding(pad, act.dp(8), pad, act.dp(8));
        tv.setClickable(true);
        tv.setBackgroundResource(R.drawable.bg_chip);
        return tv;
    }

    private void styleTab(TextView tabView, String text, boolean active) {
        tabView.setText(text);
        tabView.setTextColor(act.getColor(active
                ? R.color.accent_solid : R.color.text_secondary));
    }
}
