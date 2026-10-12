package com.mike.backtapwallet;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import io.github.libxposed.service.XposedService;

/**
 * 模块配置界面（布局 v2）。
 *
 * <p>结构：页头（标题 + 版本胶囊）→ 状态条（圆点 + 主状态 + 可展开详情）→
 * 「背部轻敲」（双击 / 三击，标题行带当前功能摘要）→「投屏增强」（输入法修复 / 自动解锁，
 * 自动解锁的凭据区随总开关联动显隐）→「通用」（触发 Tips）→ 页脚。</p>
 */
public final class MainActivity extends Activity implements ModuleApp.ServiceStateListener {
    private static final int COLOR_BG = Color.rgb(245, 246, 248);
    private static final int COLOR_CARD = Color.WHITE;
    private static final int COLOR_TEXT = Color.rgb(30, 30, 32);
    private static final int COLOR_SUBTEXT = Color.rgb(105, 105, 112);
    private static final int COLOR_SECTION = Color.rgb(138, 138, 147);
    private static final int COLOR_ACCENT = Color.rgb(22, 119, 255);
    private static final int COLOR_ACCENT_SOFT = Color.rgb(232, 240, 255);
    private static final int COLOR_OK = Color.rgb(20, 135, 70);
    private static final int COLOR_WARN = Color.rgb(196, 118, 0);
    private static final int COLOR_IDLE = Color.rgb(170, 170, 178);

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private SharedPreferences legacyPreferences;
    private SharedPreferences preferences;
    private XposedService xposedService;
    private boolean serviceReady;
    private boolean loadingUi;
    private String frameworkInfo = "LSPosed 服务未连接";

    private View hookStatusDot;
    private TextView hookStatusText;
    private TextView hookSummaryText;
    private TextView hookDetailText;
    private TextView hookDetailToggle;

    private Switch tipsSwitch;
    private Switch imeFixSwitch;
    private Switch unlockSwitch;

    private TextView imeFixStateText;

    private LinearLayout unlockConfigContainer;
    private RadioGroup unlockTypeGroup;
    private EditText unlockSecretInput;
    private TextView saveUnlockButton;
    private int unlockPatternId;
    private int unlockPinId;
    private int unlockPasswordId;

    private GestureViews doubleTapViews;
    private GestureViews tripleTapViews;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window window = getWindow();
        window.setStatusBarColor(COLOR_BG);
        window.setNavigationBarColor(COLOR_BG);
        window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(true);
        }

        legacyPreferences = getSharedPreferences(Config.PREFS_NAME, Context.MODE_PRIVATE);
        preferences = legacyPreferences;
        connectService(ModuleApp.getService(), false);
        setContentView(buildContentView());
    }

    @Override
    protected void onStart() {
        super.onStart();
        ModuleApp.addServiceStateListener(this, true);
    }

    @Override
    protected void onStop() {
        ModuleApp.removeServiceStateListener(this);
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAll();
    }

    @Override
    public void onServiceStateChanged(XposedService service) {
        runOnUiThread(() -> connectService(service, true));
    }

    private void connectService(XposedService service, boolean refreshUi) {
        xposedService = service;
        serviceReady = false;
        preferences = legacyPreferences;
        frameworkInfo = "LSPosed 服务未连接";

        if (service != null) {
            try {
                SharedPreferences remote = service.getRemotePreferences(Config.PREFS_NAME);
                preferences = remote;
                serviceReady = true;
                frameworkInfo = service.getFrameworkName() + " " + service.getFrameworkVersion()
                        + " · API " + service.getApiVersion();
            } catch (Throwable t) {
                serviceReady = false;
                preferences = legacyPreferences;
                frameworkInfo = "Modern Xposed 服务连接失败";
            }
        }

        if (refreshUi && hookStatusText != null) {
            refreshAll();
        }
    }

    // ------------------------------------------------------------------ 布局骨架

    private View buildContentView() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(COLOR_BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(20), dp(16), dp(28));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(
                    android.view.WindowInsets.Type.systemBars()
                            | android.view.WindowInsets.Type.displayCutout());
            view.setPadding(dp(16), dp(20) + bars.top, dp(16), dp(28) + bars.bottom);
            return android.view.WindowInsets.CONSUMED;
        });
        scrollView.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        root.addView(buildHeader());
        root.addView(buildStatusCard(), cardSpacing());

        root.addView(sectionLabel("背部轻敲"), sectionSpacing());
        doubleTapViews = buildGestureCard(
                "背部双击",
                Config.SETTING_BACK_DOUBLE,
                Config.PREF_DOUBLE_ACTION,
                Config.PREF_DOUBLE_DISPLAY
        );
        root.addView(doubleTapViews.card, cardSpacing());

        tripleTapViews = buildGestureCard(
                "背部三击",
                Config.SETTING_BACK_TRIPLE,
                Config.PREF_TRIPLE_ACTION,
                Config.PREF_TRIPLE_DISPLAY
        );
        root.addView(tripleTapViews.card, cardSpacing());

        root.addView(sectionLabel("投屏增强"), sectionSpacing());
        root.addView(buildMirrorImeCard(), cardSpacing());
        root.addView(buildMirrorUnlockCard(), cardSpacing());

        root.addView(sectionLabel("通用"), sectionSpacing());
        root.addView(buildTipsCard(), cardSpacing());

        root.addView(buildFooter(), footerSpacing());

        return scrollView;
    }

    private View buildHeader() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(text("BackTap Wallet", 25, COLOR_TEXT, true));
        TextView subtitle = text("澎湃 OS 背部轻敲 · 投屏增强", 13, COLOR_SUBTEXT, false);
        LinearLayout.LayoutParams subtitleLp = wrap();
        subtitleLp.topMargin = dp(3);
        titles.addView(subtitle, subtitleLp);
        row.addView(titles, new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        ));

        TextView versionChip = text("v" + BuildConfig.VERSION_NAME, 12, COLOR_ACCENT, true);
        versionChip.setPadding(dp(11), dp(5), dp(11), dp(5));
        versionChip.setBackground(pillBackground(COLOR_ACCENT_SOFT));
        row.addView(versionChip);

        return row;
    }

    private View buildStatusCard() {
        LinearLayout card = card();

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        hookStatusDot = new View(this);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(9), dp(9));
        dotLp.rightMargin = dp(9);
        row.addView(hookStatusDot, dotLp);

        hookStatusText = text("正在检测…", 16, COLOR_TEXT, true);
        row.addView(hookStatusText, new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        ));

        hookDetailToggle = text("详情", 13, COLOR_ACCENT, false);
        hookDetailToggle.setPadding(dp(10), dp(6), 0, dp(6));
        row.addView(hookDetailToggle);
        card.addView(row, matchWrap());

        hookSummaryText = text("", 13, COLOR_SUBTEXT, false);
        LinearLayout.LayoutParams summaryLp = wrap();
        summaryLp.topMargin = dp(7);
        card.addView(hookSummaryText, summaryLp);

        hookDetailText = text("", 12, COLOR_SUBTEXT, false);
        hookDetailText.setVisibility(View.GONE);
        LinearLayout.LayoutParams detailLp = wrap();
        detailLp.topMargin = dp(10);
        card.addView(hookDetailText, detailLp);

        hookDetailToggle.setOnClickListener(view -> {
            boolean expand = hookDetailText.getVisibility() != View.VISIBLE;
            hookDetailText.setVisibility(expand ? View.VISIBLE : View.GONE);
            hookDetailToggle.setText(expand ? "收起" : "详情");
        });

        return card;
    }

    private View buildMirrorImeCard() {
        LinearLayout card = card();
        card.addView(cardHeader("投屏输入法修复", imeFixSwitch = cardSwitch()), matchWrap());

        TextView description = text(
                "投屏协同时手机软键盘的行为由本开关决定：开启 = 修复官方 ROM 投屏不弹键盘的问题，"
                        + "投屏中点击输入框手机会正常弹出软键盘；关闭 = 模块完全停止干预、把控制权交还系统。"
                        + "关闭不会反向压制键盘，日常使用不受影响。即时生效，无需重启。",
                13,
                COLOR_SUBTEXT,
                false
        );
        LinearLayout.LayoutParams descLp = wrap();
        descLp.topMargin = dp(7);
        card.addView(description, descLp);

        imeFixStateText = text("", 12, COLOR_SUBTEXT, false);
        LinearLayout.LayoutParams stateLp = wrap();
        stateLp.topMargin = dp(7);
        card.addView(imeFixStateText, stateLp);
        imeFixStateText.setOnClickListener(view -> retryImeFixCommit());

        imeFixSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (loadingUi) {
                return;
            }
            if (!requireModernService()) {
                refreshMirrorCards();
                return;
            }
            if (commitImeFix(isChecked)) {
                toast(isChecked ? "投屏输入法修复已开启" : "投屏输入法修复已关闭");
            } else {
                toast("写入 Remote Preferences 失败，开关未保存");
                refreshMirrorCards();
            }
            mainHandler.postDelayed(this::refreshImeFixState, 700);
        });

        return card;
    }

    /**
     * 提交输入法开关。RemotePreferences 的 {@code commit()} 会先改本地快照再落库，
     * 落库失败时本地快照已被污染（UI 会显示未生效的值），因此这里重试一次并把结果如实返回。
     */
    private boolean commitImeFix(boolean value) {
        boolean ok = preferences.edit()
                .putBoolean(Config.PREF_MIRROR_IME_FIX_ENABLED, value)
                .commit();
        if (!ok) {
            ok = preferences.edit()
                    .putBoolean(Config.PREF_MIRROR_IME_FIX_ENABLED, value)
                    .commit();
        }
        return ok;
    }

    /** 按钮失焦时（点标题文字）重试下发一次，用于「开关未下发」的补救。 */
    private void retryImeFixCommit() {
        if (imeFixSwitch == null || !requireModernService()) {
            return;
        }
        boolean target = imeFixSwitch.isChecked();
        toast(commitImeFix(target) ? "已重新下发，等待系统框架回执" : "重新下发失败");
        mainHandler.postDelayed(this::refreshImeFixState, 700);
    }

    /**
     * 校验开关是否真的下发到 system_server。
     *
     * <p>判定依据是模块回写到 {@code Settings.System} 的生效值，而不是本地
     * RemotePreferences 快照——后者在落库失败时同样会被更新，无法作为依据。</p>
     */
    private void refreshImeFixState() {
        if (imeFixStateText == null || imeFixSwitch == null) {
            return;
        }
        String state;
        try {
            state = Settings.System.getString(getContentResolver(), Config.STATUS_IME_FIX_STATE);
        } catch (Throwable t) {
            state = null;
        }
        if (state == null) {
            imeFixStateText.setText("系统框架尚未回执，重启手机后再看这里");
            imeFixStateText.setTextColor(COLOR_SUBTEXT);
            return;
        }
        boolean effective = Config.STATE_ON.equals(state);
        boolean intended = imeFixSwitch.isChecked();
        if (effective == intended) {
            imeFixStateText.setText(effective
                    ? "系统框架已同步 · 修复生效（投屏中键盘可弹出）"
                    : "系统框架已同步 · 修复已关闭（已交还系统原生行为）");
            imeFixStateText.setTextColor(COLOR_OK);
        } else {
            imeFixStateText.setText("开关未下发到系统框架（实际"
                    + (effective ? "生效" : "停用") + "），点此重试");
            imeFixStateText.setTextColor(COLOR_WARN);
        }
        imeFixStateText.setPaintFlags(
                imeFixStateText.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
    }

    private View buildMirrorUnlockCard() {
        LinearLayout card = card();
        card.addView(cardHeader("投屏自动解锁", unlockSwitch = cardSwitch()), matchWrap());

        TextView description = text(
                "投屏连接后自动使用本机凭据解锁，无需在 PC 端或手机上重复输入。凭据仅保存在本机配置中。",
                13,
                COLOR_SUBTEXT,
                false
        );
        LinearLayout.LayoutParams descLp = wrap();
        descLp.topMargin = dp(7);
        card.addView(description, descLp);

        unlockConfigContainer = buildUnlockConfig();
        unlockConfigContainer.setVisibility(View.GONE);
        LinearLayout.LayoutParams configLp = matchWrap();
        configLp.topMargin = dp(4);
        card.addView(unlockConfigContainer, configLp);

        unlockSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (loadingUi) {
                return;
            }
            if (!requireModernService()) {
                refreshMirrorCards();
                return;
            }
            boolean ok = preferences.edit()
                    .putBoolean(Config.PREF_MIRROR_UNLOCK_ENABLED, isChecked)
                    .commit();
            if (ok) {
                if (unlockConfigContainer != null) {
                    unlockConfigContainer.setVisibility(isChecked ? View.VISIBLE : View.GONE);
                }
                toast(isChecked ? "投屏自动解锁已开启" : "投屏自动解锁已关闭");
            } else {
                toast("保存失败");
                refreshMirrorCards();
            }
        });

        return card;
    }

    private LinearLayout buildUnlockConfig() {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);

        TextView typeLabel = text("凭据类型", 13, COLOR_SUBTEXT, false);
        LinearLayout.LayoutParams typeLabelLp = wrap();
        typeLabelLp.topMargin = dp(14);
        container.addView(typeLabel, typeLabelLp);

        unlockTypeGroup = new RadioGroup(this);
        unlockTypeGroup.setOrientation(LinearLayout.HORIZONTAL);
        unlockPatternId = View.generateViewId();
        unlockPinId = View.generateViewId();
        unlockPasswordId = View.generateViewId();
        unlockTypeGroup.addView(radio("图案", unlockPatternId));
        unlockTypeGroup.addView(radio("PIN", unlockPinId));
        unlockTypeGroup.addView(radio("密码", unlockPasswordId));
        LinearLayout.LayoutParams typeGroupLp = matchWrap();
        typeGroupLp.topMargin = dp(2);
        container.addView(unlockTypeGroup, typeGroupLp);

        unlockSecretInput = new EditText(this);
        unlockSecretInput.setHint("图案填九宫格编号串，如 4756");
        unlockSecretInput.setTextSize(15);
        unlockSecretInput.setTextColor(COLOR_TEXT);
        unlockSecretInput.setHintTextColor(COLOR_SUBTEXT);
        unlockSecretInput.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        LinearLayout.LayoutParams inputLp = matchWrap();
        inputLp.topMargin = dp(8);
        container.addView(unlockSecretInput, inputLp);

        TextView patternHint = text(
                "图案按九宫格编号填写（左上为 1），不自动补中间点。",
                12,
                COLOR_SUBTEXT,
                false
        );
        LinearLayout.LayoutParams hintLp = wrap();
        hintLp.topMargin = dp(6);
        container.addView(patternHint, hintLp);

        saveUnlockButton = new TextView(this);
        saveUnlockButton.setText("保存凭据");
        saveUnlockButton.setTextSize(15);
        saveUnlockButton.setTextColor(Color.WHITE);
        saveUnlockButton.setTypeface(saveUnlockButton.getTypeface(), android.graphics.Typeface.BOLD);
        saveUnlockButton.setGravity(Gravity.CENTER);
        saveUnlockButton.setPadding(dp(16), dp(11), dp(16), dp(11));
        saveUnlockButton.setBackground(accentButtonBackground());
        LinearLayout.LayoutParams buttonLp = matchWrap();
        buttonLp.topMargin = dp(12);
        container.addView(saveUnlockButton, buttonLp);
        saveUnlockButton.setOnClickListener(view -> saveUnlockCredential());

        TextView scopeHint = text(
                "需 LSPosed 作用域额外勾选「SystemUI / 系统界面」并重启一次。",
                12,
                COLOR_SUBTEXT,
                false
        );
        LinearLayout.LayoutParams scopeLp = wrap();
        scopeLp.topMargin = dp(10);
        container.addView(scopeHint, scopeLp);

        unlockTypeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (loadingUi || checkedId == -1) {
                return;
            }
            if (!requireModernService()) {
                refreshMirrorCards();
                return;
            }
            String type = unlockTypeForId(checkedId);
            if (type != null) {
                preferences.edit().putString(Config.PREF_MIRROR_UNLOCK_TYPE, type).commit();
            }
        });

        return container;
    }

    @SuppressWarnings("deprecation")
    private View buildTipsCard() {
        LinearLayout card = card();
        card.addView(cardHeader("触发 Tips", tipsSwitch = cardSwitch()), matchWrap());

        TextView description = text(
                "敲击成功时在主屏提示敲击次数与实际执行的功能。",
                13,
                COLOR_SUBTEXT,
                false
        );
        LinearLayout.LayoutParams descLp = wrap();
        descLp.topMargin = dp(7);
        card.addView(description, descLp);

        tipsSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (loadingUi) {
                return;
            }
            if (!requireModernService()) {
                refreshTips();
                return;
            }
            boolean ok = preferences.edit().putBoolean(Config.PREF_SHOW_TIPS, isChecked).commit();
            if (ok) {
                toast(isChecked ? "Tips 已开启" : "Tips 已关闭");
            } else {
                toast("保存 Tips 设置失败");
                refreshTips();
            }
        });

        return card;
    }

    private View buildFooter() {
        TextView view = text(
                "Modern Xposed API 102 · Remote Preferences · Hot Reload",
                12,
                COLOR_SECTION,
                false
        );
        view.setGravity(Gravity.CENTER);
        return view;
    }

    // ------------------------------------------------------------------ 卡片内的复合控件

    private GestureViews buildGestureCard(
            String title,
            String settingKey,
            String actionPrefKey,
            String displayPrefKey
    ) {
        GestureViews views = new GestureViews();
        views.settingKey = settingKey;
        views.actionPrefKey = actionPrefKey;
        views.displayPrefKey = displayPrefKey;
        views.card = card();

        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        headerRow.addView(sectionTitle(title), new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        ));
        views.summaryText = text("", 13, COLOR_SUBTEXT, false);
        headerRow.addView(views.summaryText);
        views.card.addView(headerRow, matchWrap());

        TextView actionLabel = text("功能", 13, COLOR_SUBTEXT, false);
        LinearLayout.LayoutParams labelLp = wrap();
        labelLp.topMargin = dp(14);
        views.card.addView(actionLabel, labelLp);

        views.actionGroup = new RadioGroup(this);
        views.actionGroup.setOrientation(LinearLayout.VERTICAL);
        views.offId = View.generateViewId();
        views.paymentId = View.generateViewId();
        views.busId = View.generateViewId();
        views.walletId = View.generateViewId();
        views.actionGroup.addView(radio("关闭", views.offId));
        views.actionGroup.addView(radio("小米钱包刷卡（NFC）", views.walletId));
        views.actionGroup.addView(radio("支付宝付款码", views.paymentId));
        views.actionGroup.addView(radio("支付宝乘车码", views.busId));
        LinearLayout.LayoutParams groupLp = matchWrap();
        groupLp.topMargin = dp(2);
        views.card.addView(views.actionGroup, groupLp);

        views.warningText = text("", 12, COLOR_WARN, false);
        views.warningText.setVisibility(View.GONE);
        LinearLayout.LayoutParams warningLp = wrap();
        warningLp.topMargin = dp(6);
        views.card.addView(views.warningText, warningLp);

        views.displayLabel = text("显示位置（仅支付宝付款码 / 乘车码）", 13, COLOR_SUBTEXT, false);
        LinearLayout.LayoutParams displayLabelLp = wrap();
        displayLabelLp.topMargin = dp(14);
        views.card.addView(views.displayLabel, displayLabelLp);

        views.displayGroup = new RadioGroup(this);
        views.displayGroup.setOrientation(LinearLayout.HORIZONTAL);
        views.mainId = View.generateViewId();
        views.rearId = View.generateViewId();
        views.displayGroup.addView(radio("主屏", views.mainId));
        views.displayGroup.addView(radio("背屏", views.rearId));
        LinearLayout.LayoutParams displayGroupLp = matchWrap();
        displayGroupLp.topMargin = dp(2);
        views.card.addView(views.displayGroup, displayGroupLp);

        views.actionGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (loadingUi || checkedId == -1) {
                return;
            }
            String action = actionForId(views, checkedId);
            if (action != null) {
                requestGestureChange(views, action);
            }
        });

        views.displayGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (loadingUi || checkedId == -1) {
                return;
            }
            if (!requireModernService()) {
                refreshGesture(views);
                return;
            }
            String display = checkedId == views.mainId ? Config.DISPLAY_MAIN : Config.DISPLAY_REAR;
            boolean ok = preferences.edit().putString(views.displayPrefKey, display).commit();
            toast(ok ? "配置已生效" : "保存显示位置失败");
        });

        return views;
    }

    private LinearLayout cardHeader(String title, Switch toggle) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(sectionTitle(title), new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        ));
        row.addView(toggle);
        return row;
    }

    private Switch cardSwitch() {
        Switch view = new Switch(this);
        view.setText("");
        return view;
    }

    // ------------------------------------------------------------------ 业务逻辑

    private void requestGestureChange(GestureViews views, String action) {
        if (!requireModernService()) {
            refreshGesture(views);
            return;
        }
        if (!isCurrentHookLoaded()) {
            toast("Modern Hook 尚未加载，请确认已启用模块并重启手机一次");
            refreshGesture(views);
            return;
        }

        String currentSystem = Settings.System.getString(getContentResolver(), views.settingKey);
        String previousAction = readConfiguredAction(views, currentSystem, false);
        if (!preferences.edit().putString(views.actionPrefKey, action).commit()) {
            toast("保存功能配置失败");
            refreshGesture(views);
            return;
        }

        String expectedNative = nativeFunctionForAction(action);
        mainHandler.postDelayed(() -> {
            try {
                String actual = Settings.System.getString(getContentResolver(), views.settingKey);
                if (expectedNative.equals(actual)) {
                    toast("配置已生效");
                } else {
                    preferences.edit().putString(views.actionPrefKey, previousAction).commit();
                    toast("系统手势未同步，请查看 LSPosed 日志");
                }
            } catch (Throwable t) {
                toast("无法验证系统手势配置");
            }
            refreshGesture(views);
        }, 650);
    }

    private void saveUnlockCredential() {
        if (!requireModernService()) {
            return;
        }
        String secret = unlockSecretInput.getText().toString().trim();
        String type = unlockTypeForId(unlockTypeGroup.getCheckedRadioButtonId());
        if (type == null) {
            type = Config.UNLOCK_TYPE_PATTERN;
        }
        boolean ok = preferences.edit()
                .putString(Config.PREF_MIRROR_UNLOCK_SECRET, secret)
                .putString(Config.PREF_MIRROR_UNLOCK_TYPE, type)
                .commit();
        if (ok) {
            toast(secret.isEmpty() ? "凭据已清空" : "凭据已保存");
        } else {
            toast("保存凭据失败");
        }
    }

    private String unlockTypeForId(int id) {
        if (id == unlockPatternId) {
            return Config.UNLOCK_TYPE_PATTERN;
        }
        if (id == unlockPinId) {
            return Config.UNLOCK_TYPE_PIN;
        }
        if (id == unlockPasswordId) {
            return Config.UNLOCK_TYPE_PASSWORD;
        }
        return null;
    }

    private int idForUnlockType(String type) {
        if (Config.UNLOCK_TYPE_PIN.equals(type)) {
            return unlockPinId;
        }
        if (Config.UNLOCK_TYPE_PASSWORD.equals(type)) {
            return unlockPasswordId;
        }
        return unlockPatternId;
    }

    private boolean requireModernService() {
        if (serviceReady && xposedService != null) {
            return true;
        }
        toast("Modern Xposed 服务未连接，请确认 LSPosed 2.2.0 已启用模块");
        return false;
    }

    // ------------------------------------------------------------------ 刷新

    private void refreshAll() {
        refreshHookStatus();
        refreshTips();
        refreshMirrorCards();
        refreshImeFixState();
        if (doubleTapViews != null) {
            refreshGesture(doubleTapViews);
        }
        if (tripleTapViews != null) {
            refreshGesture(tripleTapViews);
        }
    }

    private void refreshTips() {
        if (tipsSwitch == null) {
            return;
        }
        loadingUi = true;
        try {
            tipsSwitch.setChecked(preferences.getBoolean(Config.PREF_SHOW_TIPS, false));
            tipsSwitch.setEnabled(serviceReady);
            tipsSwitch.setAlpha(serviceReady ? 1.0f : 0.5f);
        } finally {
            loadingUi = false;
        }
    }

    private void refreshMirrorCards() {
        loadingUi = true;
        try {
            if (imeFixSwitch != null) {
                imeFixSwitch.setChecked(
                        preferences.getBoolean(Config.PREF_MIRROR_IME_FIX_ENABLED, true));
                imeFixSwitch.setEnabled(serviceReady);
                imeFixSwitch.setAlpha(serviceReady ? 1.0f : 0.5f);
            }
            if (unlockSwitch != null) {
                unlockSwitch.setChecked(
                        preferences.getBoolean(Config.PREF_MIRROR_UNLOCK_ENABLED, false));
                unlockSwitch.setEnabled(serviceReady);
                unlockSwitch.setAlpha(serviceReady ? 1.0f : 0.5f);
            }

            boolean unlockEnabled = unlockSwitch != null && unlockSwitch.isChecked();
            if (unlockConfigContainer != null) {
                unlockConfigContainer.setVisibility(unlockEnabled ? View.VISIBLE : View.GONE);
            }

            if (unlockTypeGroup != null) {
                String type = preferences.getString(
                        Config.PREF_MIRROR_UNLOCK_TYPE, Config.UNLOCK_TYPE_PATTERN);
                unlockTypeGroup.check(idForUnlockType(type));
                setActionGroupEnabled(unlockTypeGroup, serviceReady && unlockEnabled);
            }
            if (unlockSecretInput != null) {
                String secret = preferences.getString(Config.PREF_MIRROR_UNLOCK_SECRET, "");
                if (!secret.equals(unlockSecretInput.getText().toString())) {
                    unlockSecretInput.setText(secret);
                }
                setEnabledState(unlockSecretInput, serviceReady && unlockEnabled);
            }
            if (saveUnlockButton != null) {
                setEnabledState(saveUnlockButton, serviceReady && unlockEnabled);
            }
        } finally {
            loadingUi = false;
        }
    }

    private boolean isCurrentHookLoaded() {
        try {
            String hookVersion = Settings.System.getString(getContentResolver(), Config.STATUS_HOOK_VERSION);
            int hookBootCount = Settings.System.getInt(
                    getContentResolver(), Config.STATUS_HOOK_BOOT_COUNT, Integer.MIN_VALUE);
            int currentBootCount = Settings.Global.getInt(
                    getContentResolver(), Settings.Global.BOOT_COUNT, Integer.MIN_VALUE);
            return BuildConfig.VERSION_NAME.equals(hookVersion) && hookBootCount == currentBootCount;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void refreshHookStatus() {
        if (hookStatusText == null) {
            return;
        }

        String hookVersion = Settings.System.getString(getContentResolver(), Config.STATUS_HOOK_VERSION);
        int hookBootCount = Settings.System.getInt(
                getContentResolver(), Config.STATUS_HOOK_BOOT_COUNT, Integer.MIN_VALUE);
        int currentBootCount = Settings.Global.getInt(
                getContentResolver(), Settings.Global.BOOT_COUNT, Integer.MIN_VALUE);

        String detail = frameworkInfo
                + "\nRemote Preferences：" + (serviceReady ? "正常" : "未连接")
                + "\n作用域：系统框架（背部轻敲）+ SystemUI（投屏自动解锁）"
                + "\n钱包入口：" + Config.WALLET_ACTIVITY
                + "\n模块版本：" + BuildConfig.VERSION_NAME;

        if (hookVersion != null && hookBootCount == currentBootCount) {
            if (BuildConfig.VERSION_NAME.equals(hookVersion)) {
                applyStatus("Modern Hook 已加载", COLOR_OK,
                        "system_server 正在运行 " + BuildConfig.VERSION_NAME + "，功能可用", detail);
            } else {
                applyStatus("需要重启手机", COLOR_WARN,
                        "system_server 仍运行 " + hookVersion + "，当前 APK " + BuildConfig.VERSION_NAME,
                        detail);
            }
        } else {
            applyStatus("Modern Hook 未加载", COLOR_IDLE,
                    "请确认 LSPosed 已启用模块，作用域为「系统框架」，并重启一次", detail);
        }
    }

    private void applyStatus(String title, int color, String summary, String detail) {
        hookStatusText.setText(title);
        hookStatusText.setTextColor(color);
        hookStatusDot.setBackground(dotBackground(color));
        hookSummaryText.setText(summary);
        hookDetailText.setText(detail);
    }

    private void refreshGesture(GestureViews views) {
        loadingUi = true;
        try {
            String currentSystem = Settings.System.getString(getContentResolver(), views.settingKey);
            String action = readConfiguredAction(views, currentSystem, serviceReady);

            views.actionGroup.clearCheck();

            if (Config.FUNCTION_BUS.equals(action)) {
                views.actionGroup.check(views.busId);
            } else if (Config.FUNCTION_WALLET.equals(action)) {
                views.actionGroup.check(views.walletId);
            } else if (Config.FUNCTION_PAYMENT.equals(action)) {
                views.actionGroup.check(views.paymentId);
            } else {
                views.actionGroup.check(views.offId);
            }

            String expectedNative = nativeFunctionForAction(action);
            boolean nativeActive = expectedNative.equals(currentSystem);

            if (!nativeActive && serviceReady && !Config.FUNCTION_NONE.equals(action)) {
                String shown = currentSystem == null ? Config.FUNCTION_NONE : currentSystem;
                views.warningText.setText("系统当前绑定：" + shown + "，重新选择当前功能即可恢复绑定。");
                views.warningText.setVisibility(View.VISIBLE);
            } else {
                views.warningText.setVisibility(View.GONE);
            }

            if (Config.FUNCTION_NONE.equals(action)) {
                views.summaryText.setText("已关闭");
                views.summaryText.setTextColor(COLOR_SUBTEXT);
            } else {
                views.summaryText.setText(actionSummary(action));
                views.summaryText.setTextColor(nativeActive ? COLOR_OK : COLOR_WARN);
            }

            String display = preferences.getString(views.displayPrefKey, Config.DISPLAY_REAR);
            if (Config.DISPLAY_MAIN.equals(display)) {
                views.displayGroup.check(views.mainId);
            } else {
                views.displayGroup.check(views.rearId);
            }

            boolean actionEnabled = serviceReady
                    && !Config.FUNCTION_NONE.equals(action)
                    && nativeActive;
            boolean displayEnabled = actionEnabled && !Config.FUNCTION_WALLET.equals(action);
            setDisplayGroupEnabled(views.displayGroup, displayEnabled);
            views.displayLabel.setAlpha(displayEnabled ? 1.0f : 0.45f);
            setActionGroupEnabled(views.actionGroup, serviceReady);
        } finally {
            loadingUi = false;
        }
    }

    private static String actionSummary(String action) {
        if (Config.FUNCTION_WALLET.equals(action)) {
            return "小米钱包刷卡";
        }
        if (Config.FUNCTION_PAYMENT.equals(action)) {
            return "支付宝付款码";
        }
        if (Config.FUNCTION_BUS.equals(action)) {
            return "支付宝乘车码";
        }
        return "已关闭";
    }

    private String readConfiguredAction(GestureViews views, String currentSystem, boolean migrate) {
        String action = preferences.getString(views.actionPrefKey, null);
        if (isValidAction(action)) {
            return action;
        }

        String inferred;
        if (Config.FUNCTION_BUS.equals(currentSystem)) {
            inferred = Config.FUNCTION_BUS;
        } else if (Config.FUNCTION_PAYMENT.equals(currentSystem)) {
            inferred = Config.FUNCTION_PAYMENT;
        } else {
            inferred = Config.FUNCTION_NONE;
        }

        if (migrate) {
            preferences.edit().putString(views.actionPrefKey, inferred).commit();
        }
        return inferred;
    }

    private static boolean isValidAction(String action) {
        return Config.FUNCTION_NONE.equals(action)
                || Config.FUNCTION_PAYMENT.equals(action)
                || Config.FUNCTION_BUS.equals(action)
                || Config.FUNCTION_WALLET.equals(action);
    }

    private static String nativeFunctionForAction(String action) {
        return Config.FUNCTION_NONE.equals(action)
                ? Config.FUNCTION_NONE
                : Config.FUNCTION_PAYMENT;
    }

    private static String actionForId(GestureViews views, int id) {
        if (id == views.offId) return Config.FUNCTION_NONE;
        if (id == views.paymentId) return Config.FUNCTION_PAYMENT;
        if (id == views.busId) return Config.FUNCTION_BUS;
        if (id == views.walletId) return Config.FUNCTION_WALLET;
        return null;
    }

    // ------------------------------------------------------------------ 样式工具

    private void setActionGroupEnabled(RadioGroup group, boolean enabled) {
        group.setEnabled(enabled);
        group.setAlpha(enabled ? 1.0f : 0.5f);
        for (int i = 0; i < group.getChildCount(); i++) {
            group.getChildAt(i).setEnabled(enabled);
        }
    }

    private void setDisplayGroupEnabled(RadioGroup group, boolean enabled) {
        group.setEnabled(enabled);
        group.setAlpha(enabled ? 1.0f : 0.45f);
        for (int i = 0; i < group.getChildCount(); i++) {
            group.getChildAt(i).setEnabled(enabled);
        }
    }

    private void setEnabledState(View view, boolean enabled) {
        view.setEnabled(enabled);
        view.setAlpha(enabled ? 1.0f : 0.45f);
    }

    private LinearLayout card() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setPadding(dp(16), dp(14), dp(16), dp(14));
        view.setBackground(cardBackground());
        view.setElevation(dp(1));
        return view;
    }

    private GradientDrawable cardBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(COLOR_CARD);
        drawable.setCornerRadius(dp(14));
        return drawable;
    }

    private GradientDrawable pillBackground(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(20));
        return drawable;
    }

    private Drawable dotBackground(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(color);
        return drawable;
    }

    private Drawable accentButtonBackground() {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(COLOR_ACCENT);
        shape.setCornerRadius(dp(10));
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), shape, null);
    }

    private TextView sectionTitle(String value) {
        return text(value, 17, COLOR_TEXT, true);
    }

    private TextView sectionLabel(String value) {
        TextView view = text(value, 13, COLOR_SECTION, true);
        view.setLetterSpacing(0.08f);
        return view;
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setLineSpacing(0, 1.12f);
        if (bold) {
            view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        }
        return view;
    }

    private RadioButton radio(String value, int id) {
        RadioButton button = new RadioButton(this);
        button.setId(id);
        button.setText(value);
        button.setTextSize(15);
        button.setTextColor(COLOR_TEXT);
        button.setButtonTintList(new ColorStateList(
                new int[][]{
                        new int[]{android.R.attr.state_checked},
                        new int[]{}
                },
                new int[]{COLOR_ACCENT, Color.rgb(135, 135, 142)}
        ));
        button.setGravity(Gravity.CENTER_VERTICAL);
        button.setPadding(0, dp(3), dp(12), dp(3));
        return button;
    }

    private LinearLayout.LayoutParams cardSpacing() {
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = dp(10);
        return lp;
    }

    private LinearLayout.LayoutParams sectionSpacing() {
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = dp(24);
        lp.bottomMargin = dp(2);
        return lp;
    }

    private LinearLayout.LayoutParams footerSpacing() {
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = dp(22);
        return lp;
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String value) {
        Toast.makeText(this, value, Toast.LENGTH_SHORT).show();
    }

    private static final class GestureViews {
        LinearLayout card;
        RadioGroup actionGroup;
        RadioGroup displayGroup;
        TextView summaryText;
        TextView displayLabel;
        TextView warningText;
        String settingKey;
        String actionPrefKey;
        String displayPrefKey;
        int offId;
        int paymentId;
        int busId;
        int walletId;
        int mainId;
        int rearId;
    }
}
