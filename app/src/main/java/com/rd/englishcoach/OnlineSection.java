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
 * 模型页的「在线模型」区块：三套接口（问答 / 识别 / 朗读）各自独立配置 + 朗读音色。
 *
 * <p>由旧设置页的接口分组原样迁来（<b>纯搬家</b>，字段语义不变），搬迁原因：接口配置是
 * 「模型」这件事的一部分，堆在设置页会把提示词/外观挤到看不见；设置页只留与模型无关的两组。</p>
 *
 * <p>三套接口用 {@link ModelDiscovery.Kind} 做泛型区分，字段/协议/默认值都走一组访问器，
 * 不再各写三份（旧版三份几乎逐字重复，改一处漏两处）。</p>
 *
 * <p><b>网络动作都在后台线程、回主线程改 UI</b>，且一律用<b>输入框当前值</b>而不是已保存值——
 * 用户改了没保存时，测的必须是他填的那个（旧版踩过「测的和填的不一致」）。</p>
 */
final class OnlineSection {

    private final MainActivity act;
    private final Prefs prefs;
    private final BottomSheetPanel sheet;

    private final EditText etBaseUrl, etApiKey, etChatModel;
    private final EditText etAsrBaseUrl, etAsrKey, etAsrModel;
    private final EditText etTtsBaseUrl, etTtsKey, etTtsModel;
    private final TextView btnToggleKey, btnToggleAsrKey, btnToggleTtsKey;
    private final LinearLayout chatProtocolChips, asrProtocolChips, ttsProtocolChips;
    private final TextView tvProbeChat, tvProbeAsr, tvProbeTts;
    private final TextView tvVoiceEn, tvVoiceZh;

    /** 上次「检测音色」的结果（null = 用协议默认列表）。 */
    private List<String> detectedVoices;

    OnlineSection(MainActivity act, View root) {
        this.act = act;
        this.prefs = new Prefs(act);
        this.sheet = new BottomSheetPanel(act);

        etBaseUrl = root.findViewById(R.id.etBaseUrl);
        etApiKey = root.findViewById(R.id.etApiKey);
        etChatModel = root.findViewById(R.id.etChatModel);
        btnToggleKey = root.findViewById(R.id.btnToggleKey);
        etAsrBaseUrl = root.findViewById(R.id.etAsrBaseUrl);
        etAsrKey = root.findViewById(R.id.etAsrKey);
        etAsrModel = root.findViewById(R.id.etAsrModel);
        btnToggleAsrKey = root.findViewById(R.id.btnToggleAsrKey);
        etTtsBaseUrl = root.findViewById(R.id.etTtsBaseUrl);
        etTtsKey = root.findViewById(R.id.etTtsKey);
        etTtsModel = root.findViewById(R.id.etTtsModel);
        btnToggleTtsKey = root.findViewById(R.id.btnToggleTtsKey);
        chatProtocolChips = root.findViewById(R.id.chatProtocolChips);
        asrProtocolChips = root.findViewById(R.id.asrProtocolChips);
        ttsProtocolChips = root.findViewById(R.id.ttsProtocolChips);
        tvProbeChat = root.findViewById(R.id.tvProbeChat);
        tvProbeAsr = root.findViewById(R.id.tvProbeAsr);
        tvProbeTts = root.findViewById(R.id.tvProbeTts);
        tvVoiceEn = root.findViewById(R.id.tvVoiceEn);
        tvVoiceZh = root.findViewById(R.id.tvVoiceZh);

        btnToggleKey.setOnClickListener(v -> toggleKeyVisible(etApiKey, btnToggleKey));
        btnToggleAsrKey.setOnClickListener(v -> toggleKeyVisible(etAsrKey, btnToggleAsrKey));
        btnToggleTtsKey.setOnClickListener(v -> toggleKeyVisible(etTtsKey, btnToggleTtsKey));

        root.findViewById(R.id.btnPickChat).setOnClickListener(
                v -> showModelPicker(ModelDiscovery.Kind.CHAT, etChatModel));
        root.findViewById(R.id.btnPickAsr).setOnClickListener(
                v -> showModelPicker(ModelDiscovery.Kind.ASR, etAsrModel));
        root.findViewById(R.id.btnPickTts).setOnClickListener(
                v -> showModelPicker(ModelDiscovery.Kind.TTS, etTtsModel));

        root.findViewById(R.id.btnProviderChat).setOnClickListener(
                v -> showProviderSheet(ModelDiscovery.Kind.CHAT));
        root.findViewById(R.id.btnProviderAsr).setOnClickListener(
                v -> showProviderSheet(ModelDiscovery.Kind.ASR));
        root.findViewById(R.id.btnProviderTts).setOnClickListener(
                v -> showProviderSheet(ModelDiscovery.Kind.TTS));

        root.findViewById(R.id.btnProbeChat).setOnClickListener(
                v -> probeOne(ModelDiscovery.Kind.CHAT));
        root.findViewById(R.id.btnProbeAsr).setOnClickListener(
                v -> probeOne(ModelDiscovery.Kind.ASR));
        root.findViewById(R.id.btnProbeTts).setOnClickListener(
                v -> probeOne(ModelDiscovery.Kind.TTS));

        root.findViewById(R.id.btnVoiceEn).setOnClickListener(v -> showVoiceSheet(true));
        root.findViewById(R.id.btnVoiceZh).setOnClickListener(v -> showVoiceSheet(false));
        root.findViewById(R.id.btnDetectVoices).setOnClickListener(v -> detectVoices());

        for (ModelDiscovery.Kind kind : ModelDiscovery.Kind.values()) fillProtocolChips(kind);
        loadAll();
    }

    /** Tab 切进本页 / onResume 时同步一遍（外部可能改过配置）。 */
    void refresh() { loadAll(); }

    /** 「保存」：把在线接口的地址 / Key / 模型落盘（协议与服务商是点了即生效的）。 */
    void save() {
        prefs.putChatBaseUrl(val(etBaseUrl));
        prefs.putApiKey(val(etApiKey));
        prefs.putChatModel(val(etChatModel));
        prefs.putAsrBaseUrl(val(etAsrBaseUrl));
        prefs.putAsrApiKey(val(etAsrKey));
        prefs.putAsrModel(val(etAsrModel));
        prefs.putTtsBaseUrl(val(etTtsBaseUrl));
        prefs.putTtsApiKey(val(etTtsKey));
        prefs.putTtsModel(val(etTtsModel));
        // 收起键盘（否则键盘挡住下半页），并让「未配 API Key」引导立刻消失
        act.hideKeyboard();
        act.onConfigSaved();
        toast(act.getString(R.string.set_saved));
    }

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
        tvVoiceEn.setText(prefs.ttsVoiceEnglish());
        tvVoiceZh.setText(prefs.ttsVoiceChinese());
    }

    // ── 三套接口的通用访问器（避免写三份） ────────────────

    private EditText baseField(ModelDiscovery.Kind k) {
        return k == ModelDiscovery.Kind.ASR ? etAsrBaseUrl
             : k == ModelDiscovery.Kind.TTS ? etTtsBaseUrl : etBaseUrl;
    }

    private EditText keyField(ModelDiscovery.Kind k) {
        return k == ModelDiscovery.Kind.ASR ? etAsrKey
             : k == ModelDiscovery.Kind.TTS ? etTtsKey : etApiKey;
    }

    private EditText modelField(ModelDiscovery.Kind k) {
        return k == ModelDiscovery.Kind.ASR ? etAsrModel
             : k == ModelDiscovery.Kind.TTS ? etTtsModel : etChatModel;
    }

    private LinearLayout chipRow(ModelDiscovery.Kind k) {
        return k == ModelDiscovery.Kind.ASR ? asrProtocolChips
             : k == ModelDiscovery.Kind.TTS ? ttsProtocolChips : chatProtocolChips;
    }

    private TextView probeResult(ModelDiscovery.Kind k) {
        return k == ModelDiscovery.Kind.ASR ? tvProbeAsr
             : k == ModelDiscovery.Kind.TTS ? tvProbeTts : tvProbeChat;
    }

    private static String[] protocols(ModelDiscovery.Kind k) {
        return k == ModelDiscovery.Kind.ASR ? AsrProtocols.ALL
             : k == ModelDiscovery.Kind.TTS ? TtsProtocols.ALL : ChatProtocols.ALL;
    }

    private String protocol(ModelDiscovery.Kind k) {
        return k == ModelDiscovery.Kind.ASR ? prefs.asrProtocol()
             : k == ModelDiscovery.Kind.TTS ? prefs.ttsProtocol() : prefs.chatProtocol();
    }

    private void putProtocol(ModelDiscovery.Kind k, String p) {
        if (k == ModelDiscovery.Kind.ASR) prefs.putAsrProtocol(p);
        else if (k == ModelDiscovery.Kind.TTS) prefs.putTtsProtocol(p);
        else prefs.putChatProtocol(p);
    }

    private static String defBaseUrl(ModelDiscovery.Kind k, String p) {
        return k == ModelDiscovery.Kind.ASR ? Prefs.defaultAsrBaseUrl(p)
             : k == ModelDiscovery.Kind.TTS ? Prefs.defaultTtsBaseUrl(p)
             : Prefs.defaultChatBaseUrl(p);
    }

    private static String defModel(ModelDiscovery.Kind k, String p) {
        return k == ModelDiscovery.Kind.ASR ? Prefs.defaultAsrModel(p)
             : k == ModelDiscovery.Kind.TTS ? Prefs.defaultTtsModel(p)
             : Prefs.defaultChatModel(p);
    }

    /**
     * 换协议：地址/模型只有还是「旧协议的默认值」（说明用户没自定义过）才跟着换，其余不动。
     * 不跟的话会出现「换了协议但地址还是旧前缀」→ 连通性检测 404。
     */
    private void setProtocol(ModelDiscovery.Kind k, String p) {
        String old = protocol(k);
        if (p.equals(old)) return;
        putProtocol(k, p);
        autoFill(baseField(k), defBaseUrl(k, old), defBaseUrl(k, p));
        autoFill(modelField(k), defModel(k, old), defModel(k, p));
        if (k == ModelDiscovery.Kind.TTS) shiftVoices(old, p);
        detectedVoices = null;   // 换协议后音色候选回退到该协议的默认列表
        fillProtocolChips(k);
    }

    private void autoFill(EditText field, String oldDefault, String newDefault) {
        String cur = val(field);
        if (cur.isEmpty() || cur.equals(oldDefault)) field.setText(newDefault);
    }

    /** 音色是协议私有的：mimo 的「冰糖」传给 MiniMax 就是无效 voice_id（表现为没声音）。 */
    private void shiftVoices(String oldProtocol, String newProtocol) {
        if (prefs.ttsVoiceChinese().equals(Prefs.defaultTtsVoice(oldProtocol, false))) {
            prefs.putTtsVoiceChinese(Prefs.defaultTtsVoice(newProtocol, false));
        }
        if (prefs.ttsVoiceEnglish().equals(Prefs.defaultTtsVoice(oldProtocol, true))) {
            prefs.putTtsVoiceEnglish(Prefs.defaultTtsVoice(newProtocol, true));
        }
        tvVoiceEn.setText(prefs.ttsVoiceEnglish());
        tvVoiceZh.setText(prefs.ttsVoiceChinese());
    }

    /** 协议芯片：选中态用 pill_glass + accent，其余 bg_chip（与模式/音色芯片同做法）。 */
    private void fillProtocolChips(ModelDiscovery.Kind k) {
        LinearLayout row = chipRow(k);
        String current = protocol(k);
        row.removeAllViews();
        for (String p : protocols(k)) {
            TextView chip = (TextView) LayoutInflater.from(act)
                    .inflate(R.layout.item_chip, row, false);
            chip.setText(protocolLabel(p));
            boolean on = p.equals(current);
            chip.setBackgroundResource(on ? R.drawable.pill_glass : R.drawable.bg_chip);
            chip.setTextColor(act.getColor(on ? R.color.accent_solid : R.color.text_secondary));
            chip.setOnClickListener(v -> setProtocol(k, p));
            row.addView(chip);
        }
    }

    private String protocolLabel(String p) {
        switch (p) {
            case ChatProtocols.OPENAI_CHAT:      return act.getString(R.string.proto_openai_chat);
            case ChatProtocols.OPENAI_RESPONSES: return act.getString(R.string.proto_openai_responses);
            case ChatProtocols.ANTHROPIC:        return act.getString(R.string.proto_anthropic);
            case ChatProtocols.GEMINI:           return act.getString(R.string.proto_gemini);
            case AsrProtocols.DASHSCOPE:         return act.getString(R.string.proto_dashscope);
            case AsrProtocols.CHAT_AUDIO:        return act.getString(R.string.proto_chat_audio);
            case AsrProtocols.TRANSCRIPTIONS:    return act.getString(R.string.proto_transcriptions);
            case TtsProtocols.CHAT_TTS:          return act.getString(R.string.proto_chat_tts);
            case TtsProtocols.SPEECH:            return act.getString(R.string.proto_speech);
            case TtsProtocols.MINIMAX_T2A:       return act.getString(R.string.proto_minimax_t2a);
            case TtsProtocols.ARK_TTS:           return act.getString(R.string.proto_ark_tts);
            default:                             return p;
        }
    }

    /** API Key 默认打码成一排圆点；点「显示」可以明文核对，免得把圆点当成乱码。 */
    private void toggleKeyVisible(EditText field, TextView btn) {
        boolean masked = field.getTransformationMethod() instanceof PasswordTransformationMethod;
        field.setTransformationMethod(masked ? null : new PasswordTransformationMethod());
        btn.setText(masked ? R.string.set_hide_key : R.string.set_show_key);
        field.setSelection(field.getText().length());
    }

    // ── 常用服务商（一键填协议 + 地址 + 默认模型） ──────────────

    private void showProviderSheet(ModelDiscovery.Kind kind) {
        LinearLayout box = verticalBox();

        TextView title = new TextView(act);
        title.setText(act.getString(R.string.set_provider_title, kindLabel(kind)));
        title.setTextSize(15);
        title.setTextColor(act.getColor(R.color.text_primary));
        box.addView(title);

        TextView hint = new TextView(act);
        hint.setText(R.string.set_provider_hint);
        hint.setTextSize(12);
        hint.setTextColor(act.getColor(R.color.text_tertiary));
        hint.setPadding(0, act.dp(4), 0, act.dp(8));
        box.addView(hint);

        for (Providers.Entry e : Providers.of(kind)) {
            String label = act.getString(e.nameRes) + " · " + protocolLabel(e.protocol);
            box.addView(pickRow(label, e.baseUrl, () -> {
                putProtocolIfChanged(kind, e.protocol);
                baseField(kind).setText(e.baseUrl);
                EditText model = modelField(kind);
                if (val(model).isEmpty()) model.setText(e.defaultModel());
                sheet.dismiss();
            }));
        }
        sheet.show(box);
    }

    /** 选服务商时协议也一起换（芯片要重画、TTS 音色要跟着切）。 */
    private void putProtocolIfChanged(ModelDiscovery.Kind kind, String p) {
        if (!p.equals(protocol(kind))) setProtocol(kind, p);
    }

    // ── 检测连通性（只测当前这一个接口，用输入框当前值） ────────────

    private void probeOne(ModelDiscovery.Kind kind) {
        final String url = val(baseField(kind));
        final String model = val(modelField(kind));
        final String key = orElse(val(keyField(kind)), val(etApiKey));
        final String proto = protocol(kind);

        TextView result = probeResult(kind);
        result.setVisibility(View.VISIBLE);
        result.setTextColor(act.getColor(R.color.text_secondary));
        result.setText(R.string.set_probe_running);

        new Thread(() -> {
            String err = kind == ModelDiscovery.Kind.ASR
                    ? ModelDiscovery.probeAsr(proto, url, key, model)
                    : kind == ModelDiscovery.Kind.TTS
                      ? ModelDiscovery.probeTts(proto, url, key, model)
                      : ModelDiscovery.probeChat(proto, url, key, model);
            act.runOnUiThread(() -> {
                result.setTextColor(act.getColor(
                        err == null ? R.color.success : R.color.danger));
                result.setText(err == null ? act.getString(R.string.set_probe_ok) : err);
            });
        }, "probe").start();
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

        final String baseUrl = val(baseField(kind));
        final String key = orElse(val(keyField(kind)), val(etApiKey));
        final String proto = protocol(kind);
        new Thread(() -> {
            final ModelDiscovery.Listing listing = ModelDiscovery.fetch(proto, baseUrl, key);
            act.runOnUiThread(() -> {
                if (!sheet.showing()) return;
                final List<ModelDiscovery.ModelInfo> picked =
                        ModelDiscovery.byKind(listing.models, kind);
                if (listing.ok() && !picked.isEmpty()) {
                    status.setVisibility(View.GONE);
                    renderModels(list, picked, "", target);
                    search.addTextChangedListener(new TextWatcher() {
                        @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                        @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
                        @Override public void afterTextChanged(Editable s) {
                            renderModels(list, picked, s.toString(), target);
                        }
                    });
                    return;
                }
                // 拉不到 / 这一类目下没有模型：给出真实原因（HTTP 码 + URL + 响应片段）+ 内置候选
                status.setTextColor(act.getColor(R.color.danger));
                status.setText(listing.error != null
                        ? listing.error : act.getString(R.string.set_pick_empty));
                list.removeAllViews();
                addGroupLabel(list, act.getString(R.string.set_pick_builtin));
                renderIds(list, Providers.fallbackModels(kind), target);
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
            list.addView(pickRow(m.id, m.name.equals(m.id) ? null : m.name,
                    () -> { target.setText(m.id); sheet.dismiss(); }));
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

    private void renderIds(LinearLayout list, List<String> ids, EditText target) {
        for (String id : ids) {
            list.addView(pickRow(id, null, () -> { target.setText(id); sheet.dismiss(); }));
        }
    }

    private void addGroupLabel(LinearLayout list, String text) {
        TextView tv = new TextView(act);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setTextColor(act.getColor(R.color.text_tertiary));
        tv.setPadding(0, act.dp(12), 0, act.dp(4));
        list.addView(tv);
    }

    /** 可点选的一行（首行主文字 + 可选副文字）。 */
    private View pickRow(String text, String subtitle, Runnable onClick) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackgroundResource(R.drawable.card_row);
        row.setPadding(act.dp(12), act.dp(8), act.dp(12), act.dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = act.dp(4);
        row.setLayoutParams(lp);
        row.setClickable(true);

        TextView tv = new TextView(act);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setTextColor(act.getColor(R.color.text_primary));
        row.addView(tv);

        if (subtitle != null && !subtitle.isEmpty()) {
            TextView sub = new TextView(act);
            sub.setText(subtitle);
            sub.setTextSize(11);
            sub.setTextColor(act.getColor(R.color.text_tertiary));
            row.addView(sub);
        }
        row.setOnClickListener(v -> onClick.run());
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
        // 只有 mimo（chat-tts）会把可用音色回显在报错里；其他协议用内置默认音色列表
        if (!TtsProtocols.CHAT_TTS.equals(prefs.ttsProtocol())) {
            toast(act.getString(R.string.set_detect_voices_fail));
            return;
        }
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
        LinearLayout box = verticalBox();

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
     * 该语言下的候选音色：还没检测过就用协议默认列表；检测过则按已知中/英文声拆分，
     * 未知音色两侧都列（用户自选）。检测不到时手填入口兜底。
     */
    private String[] voicesFor(boolean english) {
        if (detectedVoices != null && !detectedVoices.isEmpty()) {
            List<String> known = Arrays.asList(english ? Prefs.TTS_VOICES_EN : Prefs.TTS_VOICES_ZH);
            List<String> other = Arrays.asList(english ? Prefs.TTS_VOICES_ZH : Prefs.TTS_VOICES_EN);
            List<String> out = new ArrayList<>();
            for (String v : detectedVoices) {
                if (known.contains(v) || !other.contains(v)) out.add(v);
            }
            return out.toArray(new String[0]);
        }
        String[] proto = TtsProtocols.defaultVoices(prefs.ttsProtocol());
        if (proto != null) return proto;
        return english ? Prefs.TTS_VOICES_EN : Prefs.TTS_VOICES_ZH;
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
