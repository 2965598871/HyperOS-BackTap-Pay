package com.mike.backtapwallet;

public final class Config {
    private Config() {}

    public static final String PREFS_NAME = "config";
    public static final String PREF_MIGRATED_MODERN = "modern_migrated_v1";

    public static final String PREF_DOUBLE_ACTION = "double_action";
    public static final String PREF_TRIPLE_ACTION = "triple_action";
    public static final String PREF_DOUBLE_DISPLAY = "double_display";
    public static final String PREF_TRIPLE_DISPLAY = "triple_display";
    public static final String PREF_SHOW_TIPS = "show_tips";

    // ---- 投屏自动解锁 ----
    /** 开关，默认关闭 */
    public static final String PREF_MIRROR_UNLOCK_ENABLED = "mirror_unlock_enabled";
    /**
     * 路径 A（{@code KeyguardViewMediator#showKeyguard}：投屏在先 → 之后锁屏）独立开关。
     *
     * <p>默认关闭：该路径会在投屏中**手动锁屏的瞬间**把锁解掉，导致用户"锁不上"。
     * 路径 B（锁屏在先 → 之后连投屏，{@code onDisplayAdded}）不受此开关影响，
     * 仍由 {@link #PREF_MIRROR_UNLOCK_ENABLED} 单独控制。</p>
     */
    public static final String PREF_MIRROR_UNLOCK_SHOWKEYGUARD_ENABLED =
            "mirror_unlock_showkeyguard_enabled";
    /**
     * 路径 B 的解锁尝试间隔（毫秒）。
     *
     * <p>投屏建立时手机往往尚未锁屏，keyguard 何时锁上无法预知。早期实现用
     * 0.5s / 1.5s / 3.0s 三个固定档位，命中时机纯靠运气 —— 实测最坏要等到第 3 档，
     * 白等 3.1s。现改为按本间隔密集轮询，命中即解并立刻停表。</p>
     */
    public static final String PREF_MIRROR_UNLOCK_POLL_INTERVAL = "mirror_unlock_poll_interval";
    /** 轮询间隔默认值（毫秒）。150ms 对应最坏额外等待 0.15s。 */
    public static final int DEFAULT_UNLOCK_POLL_INTERVAL_MS = 150;
    /** 轮询间隔下限，防止配置过密。 */
    public static final int MIN_UNLOCK_POLL_INTERVAL_MS = 80;
    /** 轮询间隔上限，防止配置过疏导致体感迟钝。 */
    public static final int MAX_UNLOCK_POLL_INTERVAL_MS = 2000;

    /** 凭据类型：pattern / pin / password */
    public static final String PREF_MIRROR_UNLOCK_TYPE = "mirror_unlock_type";
    /** 凭据内容；图案为九宫格编号串（左上=1），如 "4756" */
    public static final String PREF_MIRROR_UNLOCK_SECRET = "mirror_unlock_secret";

    public static final String UNLOCK_TYPE_PATTERN = "pattern";
    public static final String UNLOCK_TYPE_PIN = "pin";
    public static final String UNLOCK_TYPE_PASSWORD = "password";

    /** 可选覆盖来源：Settings.Secure，可用 adb 写入，无需重编译 */
    public static final String SECURE_UNLOCK_SECRET = "mirror_unlock_secret";
    public static final String SECURE_UNLOCK_TYPE = "mirror_unlock_type";

    // ---- 投屏输入法修复 ----
    /** 开关，默认开启 */
    public static final String PREF_MIRROR_IME_FIX_ENABLED = "mirror_ime_fix_enabled";

    /** 投屏自动解锁的运行作用域 */
    public static final String SYSUI_PACKAGE = "com.android.systemui";

    public static final String DISPLAY_MAIN = "main";
    public static final String DISPLAY_REAR = "rear";

    public static final String SETTING_BACK_DOUBLE = "back_double_tap";
    public static final String SETTING_BACK_TRIPLE = "back_triple_tap";

    public static final String FUNCTION_NONE = "none";
    public static final String FUNCTION_PAYMENT = "launch_alipay_payment_code";
    public static final String FUNCTION_BUS = "launch_alipay_bus_code";
    public static final String FUNCTION_WALLET = "launch_wallet_nfc";

    public static final String DISPLAY_BUNDLE_KEY = "show_code_display";
    public static final int DISPLAY_ID_MAIN = 0;
    public static final int DISPLAY_ID_REAR = 1;

    public static final String WALLET_PACKAGE = "com.miui.tsmclient";
    public static final String WALLET_ACTIVITY = "com.miui.tsmclient.ui.quick.DoubleClickActivity";
    public static final String WALLET_EVENT_SOURCE_EXTRA = "event_source";
    public static final String WALLET_EVENT_SOURCE_VALUE = "key_volume_down";

    public static final String STATUS_HOOK_VERSION = "backtap_wallet_hook_version";
    public static final String STATUS_HOOK_BOOT_COUNT = "backtap_wallet_hook_boot_count";
    /**
     * system_server 侧「投屏输入法修复」开关的**实际生效值**（"on"/"off"）。
     *
     * <p>RemotePreferences 是进程内快照（{@code RemotePreferences.mMap}），
     * 且 {@code commit()} 会先改本地快照再落库，落库失败时 UI 会显示未生效的值。
     * 因此模块把真正生效的闸门值回写到这里，App 据此判断开关是否真的下发成功。</p>
     */
    public static final String STATUS_IME_FIX_STATE = "backtap_wallet_ime_fix_state";
    public static final String STATE_ON = "on";
    public static final String STATE_OFF = "off";
}
