package com.rd.englishcoach;

import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
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
 * 历史页：转录 + 取词记录的浏览 / 检索 / 操作。
 *
 * <p>由旧版「转录历史」AlertDialog 迁来（同一套数据层 {@link HistoryStore}，零改动）：</p>
 * <ul>
 *   <li>类型分 Tab（转录 / 取词）+ 计数，切 Tab 原地重渲染；</li>
 *   <li>搜索框过滤（对原文与答案做包含匹配，不区分大小写）；</li>
 *   <li>空状态两种：首启无记录（引导去监听）/ 搜索无结果（一键清除搜索）；</li>
 *   <li>点条目 → Bottom Sheet 看全文；长按 → PopupMenu（朗读 / 复制 / 删除）。</li>
 * </ul>
 *
 * <p>⚠️ 删除必须用「原数组索引」{@code deleteAt(idx)}：列表是倒序渲染且按类型过滤过，
 * 用行号删会删错条目。</p>
 */
final class HistoryPage {

    private final MainActivity act;
    private final HistoryStore store;
    private final BottomSheetPanel sheet;

    private final EditText etSearch;
    private final TextView btnTranscript, btnGrab;
    private final LinearLayout list;
    private final View emptyView;
    private final TextView tvEmptyTitle, tvEmptyMsg, btnEmptyAction;

    private boolean showGrab = false;

    HistoryPage(MainActivity act, View root) {
        this.act = act;
        this.store = new HistoryStore(act);
        this.sheet = new BottomSheetPanel(act);

        etSearch = root.findViewById(R.id.etHistorySearch);
        btnTranscript = root.findViewById(R.id.btnHistTranscript);
        btnGrab = root.findViewById(R.id.btnHistGrab);
        list = root.findViewById(R.id.historyList);
        emptyView = root.findViewById(R.id.historyEmpty);
        tvEmptyTitle = root.findViewById(R.id.tvEmptyTitle);
        tvEmptyMsg = root.findViewById(R.id.tvEmptyMsg);
        btnEmptyAction = root.findViewById(R.id.btnEmptyAction);

        btnTranscript.setOnClickListener(v -> { showGrab = false; render(); });
        btnGrab.setOnClickListener(v -> { showGrab = true; render(); });
        // 输入即过滤；不需要点搜索按钮
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { render(); }
        });
    }

    /** Tab 切进本页时刷新（可能刚产生过新记录）。 */
    void refresh() { render(); }

    /** 顶栏「清空全部」：Bottom Sheet 二次确认（替代原 AlertDialog 的 neutral 按钮）。 */
    void confirmClearAll() {
        int n = store.size();
        if (n == 0) { render(); return; }

        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView msg = new TextView(act);
        msg.setText(act.getString(R.string.history_clear_confirm, n));
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
            store.clear();
            act.setStatusText(act.getString(R.string.status_history_cleared));
            render();
        });
        actions.addView(clear);
        box.addView(actions, lp);
        sheet.show(box);
    }

    // ── 渲染 ──────────────────────────────

    private void render() {
        List<HistoryStore.Entry> all = store.getAll();
        String q = etSearch.getText().toString().trim().toLowerCase(Locale.getDefault());

        // Tab 计数始终展示全量（不含搜索过滤）
        styleTab(btnTranscript, act.getString(R.string.history_tab_transcript,
                HistoryStore.countByType(all, HistoryStore.TYPE_TRANSCRIPT)), !showGrab);
        styleTab(btnGrab, act.getString(R.string.history_tab_grab,
                HistoryStore.countByType(all, HistoryStore.TYPE_GRAB)), showGrab);

        list.removeAllViews();
        boolean any = false;
        for (int i = all.size() - 1; i >= 0; i--) {
            final int idx = i;
            HistoryStore.Entry e = all.get(i);
            if (e.isGrab() != showGrab) continue;
            if (!q.isEmpty() && !matches(e, q)) continue;
            any = true;
            list.addView(buildRow(e, idx, all.size() > 0 && i > 0));
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

    /** 搜索匹配：原文或答案包含关键字（不区分大小写）。 */
    private boolean matches(HistoryStore.Entry e, String qLower) {
        return contains(e.transcript, qLower) || contains(e.answer, qLower);
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

    // ── 条目行 ────────────────────────────

    /** 一条记录：时间 + 摘要；点 → 全文 sheet；长按 → 操作菜单。 */
    private View buildRow(HistoryStore.Entry e, int idx, boolean showSep) {
        String time = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                .format(new Date(e.timestamp));
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

        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.VERTICAL);
        row.addView(tv);
        if (showSep) {
            View sep = new View(act);
            sep.setBackgroundColor(act.getColor(R.color.stroke_soft));
            row.addView(sep, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1));
        }
        return row;
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

    private void styleTab(TextView tab, String text, boolean active) {
        tab.setText(text);
        tab.setTextColor(act.getColor(active
                ? R.color.accent_solid : R.color.text_secondary));
    }
}
