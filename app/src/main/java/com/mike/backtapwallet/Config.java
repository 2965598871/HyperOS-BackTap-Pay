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
