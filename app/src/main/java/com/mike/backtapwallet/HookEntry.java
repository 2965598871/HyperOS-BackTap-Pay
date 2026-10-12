package com.mike.backtapwallet;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Queue;
import java.util.Set;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

public final class HookEntry extends XposedModule {
    private static final String TAG = "BackTapWallet";
    private static final String TARGET_CLASS = "com.miui.server.input.util.ShortCutActionsUtils";
    private static final String TARGET_METHOD = "triggerFunction";
    private static final String HOOK_ID_PREFIX = "backtap-wallet:trigger:";
    private static final String MIRROR_CLASS =
            "com.android.server.inputmethod.InputMethodManagerServiceImpl";
    private static final String MIRROR_HOOK_ID_PREFIX = "backtap-wallet:mirror:";
    private static final String MIRROR_FIELD = "mMirrorInputState";
    /** 编译器为外部类访问私有字段生成的静态合成写访问器。 */
    private static final String MIRROR_WRITE_ACCESSOR = "-$$Nest$fputmMirrorInputState";
    private static final int MIRROR_INIT_PARAM_COUNT = 5;
    /** 兜底组：DEX 证据表明前 4 条已覆盖全部读写点，仅在失效时才需打开。 */
    private static final boolean MIRROR_EXTRA_GUARDS = false;
    private static final String RULE_WRITE = "write";
    private static final String RULE_READ_FALSE = "readfalse";
    private static final String RULE_READ_TRUE = "readtrue";
    private static final String RULE_INIT_AFTER = "initafter";
    private static final String RULE_OBSERVE = "observe";
    private static final long TIP_DEDUP_WINDOW_MS = 800L;
    private static final long INSTALL_RETRY_DELAY_MS = 1000L;
    private static final int MAX_INSTALL_ATTEMPTS = 45;
    private static final int MAX_STATUS_ATTEMPTS = 45;

    private final Object tipDedupLock = new Object();
    private final Object installLock = new Object();
    private final ThreadLocal<Integer> triggerDepth = ThreadLocal.withInitial(() -> 0);

    private volatile SharedPreferences preferences;
    private volatile SharedPreferences.OnSharedPreferenceChangeListener preferenceListener;
    private volatile Context systemContext;
    private volatile Handler systemMainHandler;
    private volatile ClassLoader systemServerClassLoader;
    private volatile boolean hooksInstalled;
    private volatile boolean statusPublished;
    /** 最近一次观测到的投屏输入法修复闸门值，null 表示尚未求值。 */
    private volatile Boolean lastImeFixEnabled;
    /** 承载 mMirrorInputState 的 IMMS 实例；开关切换时用它立即刷新字段。 */
    private volatile Object mirrorInstance;
    /**
     * 模块接管（第一次强制改写字段）之前 IMMS 上 mMirrorInputState 的原值快照，只记录一次。
     * 开关关闭时用它把字段还原，从而彻底撤销修复、把控制权交还系统。
     */
    private volatile Boolean preTakeoverMirrorState;
    private String lastTipKey;
    private long lastTipUptime;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        log(Log.INFO, TAG, "Modern module loaded in " + param.getProcessName()
                + ", systemServer=" + param.isSystemServer()
                + ", API=" + getApiVersion() + ", framework=" + getFrameworkName()
                + " " + getFrameworkVersion());

        if (param.isSystemServer()) {
            try {
                initializeRemotePreferences();
            } catch (Throwable t) {
                log(Log.ERROR, TAG, "Remote Preferences init failed during module load", t);
            }
        }
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        if (!Config.SYSUI_PACKAGE.equals(param.getPackageName())) {
            return;
        }
        log(Log.INFO, TAG, "SystemUI loaded, installing mirror auto-unlock hooks");
        try {
            MirrorAutoUnlockHook.install(this, param.getDefaultClassLoader(), 1);
        } catch (Throwable t) {
            log(Log.WARN, TAG, "Mirror auto-unlock install failed", t);
        }
    }

    @Override
    public void onSystemServerStarting(SystemServerStartingParam param) {
        systemServerClassLoader = param.getClassLoader();
        hooksInstalled = false;
        statusPublished = false;

        try {
            initializeRemotePreferences();
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Remote Preferences init failed", t);
        }

        rememberSystemContext(resolveSystemContext());
        installOrSchedule(param.getClassLoader(), 1);
    }

    @Override
    public boolean onHotReloading(HotReloadingParam param) {
        log(Log.INFO, TAG, "Preparing API 102 hot reload");
        Handler handler = systemMainHandler;
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }
        detachPreferenceListener();
        preferences = null;
        systemContext = null;
        systemMainHandler = null;
        systemServerClassLoader = null;
        hooksInstalled = false;
        statusPublished = false;
        lastTipKey = null;
        lastTipUptime = 0L;
        lastImeFixEnabled = null;
        triggerDepth.remove();
        MirrorAutoUnlockHook.reset();
        return true;
    }

    @Override
    public void onHotReloaded(HotReloadedParam param) {
        log(Log.INFO, TAG, "Hot reloaded in " + param.getProcessName()
                + ", systemServer=" + param.isSystemServer()
                + ", old hooks=" + param.getOldHookHandles().size());

        try {
            initializeRemotePreferences();
            rememberSystemContext(resolveSystemContext());

            ClassLoader targetClassLoader = null;
            int replacedCount = 0;
            for (XposedInterface.HookHandle oldHandle : param.getOldHookHandles()) {
                String id = oldHandle.getId();
                if (id != null && id.startsWith(HOOK_ID_PREFIX)) {
                    if (targetClassLoader == null) {
                        targetClassLoader = oldHandle.getExecutable().getDeclaringClass().getClassLoader();
                    }
                    oldHandle.replaceHook(this::interceptTrigger);
                    replacedCount++;
                } else if (id != null && id.startsWith(MIRROR_HOOK_ID_PREFIX)) {
                    if (targetClassLoader == null) {
                        targetClassLoader = oldHandle.getExecutable().getDeclaringClass().getClassLoader();
                    }
                    oldHandle.replaceHook(
                            interceptorForMirrorRule(mirrorRuleOfHookId(id)));
                    replacedCount++;
                } else if (id != null && id.startsWith(MirrorAutoUnlockHook.HOOK_ID_PREFIX)) {
                    oldHandle.replaceHook(MirrorAutoUnlockHook.hookerFor(id));
                    replacedCount++;
                } else {
                    oldHandle.unhook();
                }
            }

            if (replacedCount > 0) {
                systemServerClassLoader = targetClassLoader;
                hooksInstalled = true;
                log(Log.INFO, TAG, "Replaced " + replacedCount + " hook(s) after hot reload");
                syncAllNativeActionsSafely();
                publishImeFixState(isImeFixEnabled());
                publishHookStatusWhenReady(1);
                return;
            }

            if (param.isSystemServer()) {
                targetClassLoader = findSystemServerClassLoader();
                if (targetClassLoader != null) {
                    systemServerClassLoader = targetClassLoader;
                    installOrSchedule(targetClassLoader, 1);
                } else {
                    log(Log.ERROR, TAG, "Hot reload has no old hook handles and system_server classloader recovery failed");
                }
            }
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Hot reload initialization failed", t);
        }
    }

    private void installOrSchedule(ClassLoader classLoader, int attempt) {
        if (classLoader == null || hooksInstalled) {
            return;
        }

        synchronized (installLock) {
            if (hooksInstalled) {
                return;
            }
            try {
                int count = installTriggerHooks(classLoader);
                if (count <= 0) {
                    throw new IllegalStateException("No triggerFunction overload found");
                }
                int mirrorCount = 0;
                try {
                    mirrorCount = installMirrorInputHooks(classLoader);
                } catch (Throwable t) {
                    // 投屏输入法修复为附加功能，不阻塞主 Hook
                    log(Log.WARN, TAG, "Mirror input hooks unavailable on this ROM", t);
                }
                hooksInstalled = true;
                systemServerClassLoader = classLoader;
                log(Log.INFO, TAG, "Modern hook ready, installed " + count
                        + " triggerFunction hook(s), " + mirrorCount + " mirror input hook(s)");
                syncAllNativeActionsSafely();
                publishImeFixState(isImeFixEnabled());
                publishHookStatusWhenReady(1);
                return;
            } catch (Throwable t) {
                if (attempt == 1 || attempt % 10 == 0 || attempt >= MAX_INSTALL_ATTEMPTS) {
                    log(attempt >= MAX_INSTALL_ATTEMPTS ? Log.ERROR : Log.WARN,
                            TAG,
                            "Hook install attempt " + attempt + "/" + MAX_INSTALL_ATTEMPTS + " failed",
                            t);
                }
            }
        }

        if (attempt < MAX_INSTALL_ATTEMPTS) {
            getSystemMainHandler().postDelayed(
                    () -> installOrSchedule(classLoader, attempt + 1),
                    INSTALL_RETRY_DELAY_MS
            );
        }
    }

    private int installTriggerHooks(ClassLoader classLoader) throws Exception {
        Class<?> clazz = Class.forName(TARGET_CLASS, false, classLoader);
        int count = 0;
        for (Method method : clazz.getDeclaredMethods()) {
            if (!TARGET_METHOD.equals(method.getName())) {
                continue;
            }
            try {
                method.setAccessible(true);
                hook(method)
                        .setId(hookId(method))
                        .intercept(this::interceptTrigger);
                count++;
            } catch (Throwable t) {
                log(Log.WARN, TAG, "Unable to hook " + method, t);
            }
        }
        return count;
    }

    /**
     * 投屏（妙享/互融）输入法压制修复，依据 DEX RULE 清单：
     * 目标机 Android 17 / SDK 37 / HyperOS 4.0.0.14.XNCCNXM，类在
     * /system_ext/framework/miui-services.jar（不在 services.jar）。
     *
     * 成因：投屏协同态下 system_server 里 InputMethodManagerServiceImpl.mMirrorInputState
     * 被写成 true，使 getMirrorInputState() / synergyOperate() 恒为 true，
     * App 侧 showSoftInput 请求在客户端即被取消
     * （ImeTracker: onCancelled at PHASE_CLIENT_REPORT_REQUESTED_VISIBLE_TYPES），
     * 请求根本到不了 InputMethodManagerService#showSoftInput，软键盘永不弹出。
     *
     * DEX 静态证据（mMirrorInputState 的全部访问点）：
     *   WRITE (iput-boolean)：-$$Nest$fputmMirrorInputState / <init> / init
     *   READ  (iget-boolean)：getMirrorInputState / synergyOperate
     * => 最小充分拦截面即本方法安装的 4 条：
     *   [write]     -$$Nest$fputmMirrorInputState：编译器生成的静态写访问器（唯一外部写入口）。
     *   [readfalse] getMirrorInputState()：读点 1。
     *   [readfalse] synergyOperate()：读点 2（方法体内直读字段）。
     *   [initafter] init(...)：方法体内直接 iput-boolean，绕过写访问器，需 after 阶段处理。
     * 注意写访问器是 static 合成方法：thisObject == null，实例在 args[0]、写入值在 args[1]。
     *
     * <p>v0.4.6 语义（单向修复 + 可完全撤销）：
     * <ul>
     *   <li>开关 <b>开</b>：只把字段钉成 {@code false}（放行键盘）。读点返回 false、写点把入参
     *       改写成 false、init 后摁回 false —— 四条钩子全部朝同一个方向发力。</li>
     *   <li>开关 <b>关</b>：<b>不再写 true</b>。四条钩子全部透传（内存/返回都被动放行），
     *       并把字段还原成 {@link #preTakeoverMirrorState}（接管前的原值）。</li>
     * </ul>
     * 关键教训：{@code true} 是「投屏协同中」的语义，一旦在非投屏场景写入就会让
     * {@code showSoftInput} 在客户端被取消 —— 连日常点输入框都弹不出键盘。
     * 因此本模块 <b>永不写 true</b>，关闭时只还原、不反向压制。
     */
    private int installMirrorInputHooks(ClassLoader classLoader) throws Exception {
        Class<?> root = Class.forName(MIRROR_CLASS, false, classLoader);
        int count = 0;
        StringBuilder installed = new StringBuilder();
        Set<Class<?>> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Queue<Class<?>> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            Class<?> clazz = queue.poll();
            if (clazz == null || !visited.add(clazz)) {
                continue;
            }
            for (Method method : clazz.getDeclaredMethods()) {
                String rule = mirrorRuleFor(method);
                if (rule == null) {
                    continue;
                }
                try {
                    method.setAccessible(true);
                    hook(method)
                            .setId(mirrorHookId(rule, clazz, method))
                            .intercept(interceptorForMirrorRule(rule));
                    count++;
                    installed.append(installed.length() == 0 ? "" : ", ")
                            .append(rule).append(':').append(method.getName());
                } catch (Throwable t) {
                    log(Log.WARN, TAG, "Unable to hook mirror input method "
                            + method.getName(), t);
                }
            }
            Collections.addAll(queue, clazz.getDeclaredClasses());
        }
        if (count > 0) {
            log(Log.INFO, TAG, "Mirror input hooks installed: " + count
                    + " [" + installed + "] in " + MIRROR_CLASS);
        }
        return count;
    }

    /**
     * 按方法名判定所属规则；返回 null 表示不拦截。
     * 兜底组（isPhoneMirrorState / isUsingIphone / shouldShowCurrentInput /
     * notifyImeInsetsControlChanged）默认不启用——DEX 证据表明前 4 条已覆盖全部读写点，
     * 仅在个别 ROM 上前 4 条失效才需要打开 MIRROR_EXTRA_GUARDS。
     */
    private static String mirrorRuleFor(Method method) {
        String name = method.getName();
        if (MIRROR_WRITE_ACCESSOR.equals(name)) {
            return RULE_WRITE;
        }
        if ("getMirrorInputState".equals(name) || "synergyOperate".equals(name)) {
            return RULE_READ_FALSE;
        }
        if ("init".equals(name) && method.getParameterCount() == MIRROR_INIT_PARAM_COUNT) {
            return RULE_INIT_AFTER;
        }
        if (!MIRROR_EXTRA_GUARDS) {
            return null;
        }
        if ("isPhoneMirrorState".equals(name) || "isUsingIphone".equals(name)) {
            return RULE_READ_FALSE;
        }
        if ("shouldShowCurrentInput".equals(name)) {
            return RULE_READ_TRUE;
        }
        if ("notifyImeInsetsControlChanged".equals(name)) {
            return RULE_OBSERVE;
        }
        return null;
    }

    private XposedInterface.Hooker interceptorForMirrorRule(String rule) {
        if (RULE_WRITE.equals(rule)) {
            return this::interceptMirrorWrite;
        }
        if (RULE_READ_TRUE.equals(rule)) {
            return this::interceptMirrorReadTrue;
        }
        if (RULE_INIT_AFTER.equals(rule)) {
            return this::interceptMirrorAfterInit;
        }
        if (RULE_OBSERVE.equals(rule)) {
            return this::interceptMirrorObserve;
        }
        return this::interceptMirrorReadFalse;
    }

    /** 投屏输入法修复开关，默认开启（Remote Preferences 未就绪时按开启处理）。 */
    private boolean isImeFixEnabled() {
        SharedPreferences prefs = preferences;
        if (prefs == null) {
            try {
                prefs = getRemotePreferences(Config.PREFS_NAME);
                preferences = prefs;
            } catch (Throwable t) {
                log(Log.DEBUG, TAG, "Remote Preferences unavailable for IME switch", t);
            }
        }
        boolean enabled = prefs == null
                || prefs.getBoolean(Config.PREF_MIRROR_IME_FIX_ENABLED, true);
        Boolean previous = lastImeFixEnabled;
        if (previous == null || previous != enabled) {
            lastImeFixEnabled = enabled;
            log(Log.INFO, TAG, "IME fix gate -> " + (enabled ? "ON" : "OFF")
                    + (prefs == null ? " (Remote Preferences 不可用，按默认开启处理)" : ""));
            publishImeFixState(enabled);
            applyMirrorState();
        }
        return enabled;
    }

    /** Remote Preferences 变更回调：只记录与回写，不在此处改 Hook 行为。 */
    private void onImeFixPreferenceChanged(SharedPreferences prefs, String key) {
        boolean enabled = prefs.getBoolean(key, true);
        lastImeFixEnabled = enabled;
        log(Log.INFO, TAG, "IME fix preference pushed by app -> "
                + (enabled ? "ON" : "OFF"));
        publishImeFixState(enabled);
        applyMirrorState();
    }

    /** 把实际生效的闸门值回写到 Settings.System，供 App 校验开关是否真正下发成功。 */
    private void publishImeFixState(boolean enabled) {
        Context context = systemContext;
        if (context == null) {
            try {
                context = resolveSystemContext();
                rememberSystemContext(context);
            } catch (Throwable ignored) {
                // system Context 尚未就绪，安装完成后的重试会补上
            }
        }
        if (context == null) {
            return;
        }
        try {
            Settings.System.putString(
                    context.getContentResolver(),
                    Config.STATUS_IME_FIX_STATE,
                    enabled ? Config.STATE_ON : Config.STATE_OFF
            );
        } catch (Throwable t) {
            log(Log.DEBUG, TAG, "Unable to publish IME fix state", t);
        }
    }

    private static String safeSnapshot(SharedPreferences prefs) {
        try {
            return String.valueOf(prefs.getAll());
        } catch (Throwable t) {
            return "<unavailable>";
        }
    }

    /**
     * 写访问器：仅在开关开启时把写入值改写成 {@code false}；开关关闭时原样透传。
     * 无论开关状态都会在改前抓一次字段原值作为「接管前快照」。
     */
    private Object interceptMirrorWrite(XposedInterface.Chain chain) throws Throwable {
        boolean enabled = isImeFixEnabled();
        Object[] args = chain.getArgs().toArray(new Object[0]);
        Object instance = mirrorTargetInstance(chain, args);
        rememberMirrorInstance(instance);
        capturePreTakeoverState(instance);
        if (enabled) {
            for (int i = 0; i < args.length; i++) {
                if (args[i] instanceof Boolean) {
                    args[i] = Boolean.FALSE;
                }
            }
        }
        Object result = chain.proceed(args);
        if (enabled) {
            forceMirrorState(instance, false);
        }
        return result;
    }

    /**
     * 读点：开关开启时返回 {@code false}（放行键盘）；开关关闭时完全透传，
     * 让系统自身的状态（投屏协同中为 true）原样生效。
     */
    private Object interceptMirrorReadFalse(XposedInterface.Chain chain) throws Throwable {
        Object instance = mirrorTargetInstance(chain, null);
        rememberMirrorInstance(instance);
        capturePreTakeoverState(instance);
        if (isImeFixEnabled() && isBooleanReturn(chain)) {
            return Boolean.FALSE;
        }
        return chain.proceed();
    }

    /** 兜底：强制放行输入法显示（仅在开关开启且额外守护组启用时可能命中）。 */
    private Object interceptMirrorReadTrue(XposedInterface.Chain chain) throws Throwable {
        if (isBooleanReturn(chain)) {
            return Boolean.TRUE;
        }
        return chain.proceed();
    }

    /** init 方法体内直接 iput：开关开启时 after 阶段把字段摁回 false；关闭时不动它。 */
    private Object interceptMirrorAfterInit(XposedInterface.Chain chain) throws Throwable {
        Object instance = chain.getThisObject();
        rememberMirrorInstance(instance);
        capturePreTakeoverState(instance);
        Object result = chain.proceed();
        if (isImeFixEnabled()) {
            forceMirrorState(instance, false);
        }
        return result;
    }

    /** 诊断用：只记录，不修改行为。 */
    private Object interceptMirrorObserve(XposedInterface.Chain chain) throws Throwable {
        log(Log.DEBUG, TAG, "IME insets control changed: " + chain.getArgs());
        return chain.proceed();
    }

    private static Object mirrorTargetInstance(XposedInterface.Chain chain, Object[] args) {
        Object thisObject = chain.getThisObject();
        if (thisObject != null) {
            return thisObject;
        }
        return args != null && args.length > 0 ? args[0] : null;
    }

    private static boolean isBooleanReturn(XposedInterface.Chain chain) {
        if (!(chain.getExecutable() instanceof Method)) {
            return false;
        }
        Class<?> type = ((Method) chain.getExecutable()).getReturnType();
        return type == boolean.class || type == Boolean.class;
    }

    /** 把承载实例上的 mMirrorInputState 摁成指定值。 */
    private void forceMirrorState(Object instance, boolean value) {
        if (instance == null) {
            return;
        }
        try {
            Field field = findField(instance.getClass(), MIRROR_FIELD);
            if (field == null) {
                return;
            }
            field.setAccessible(true);
            if (field.getBoolean(instance) != value) {
                field.setBoolean(instance, value);
            }
        } catch (Throwable t) {
            log(Log.DEBUG, TAG, "Unable to force " + MIRROR_FIELD + "=" + value, t);
        }
    }

    private void rememberMirrorInstance(Object instance) {
        if (instance != null) {
            mirrorInstance = instance;
        }
    }

    /**
     * 只记录一次「模块动手之前」的字段原值。必须在任何强制改写之前调用，
     * 后续调用直接短路，保证快照永远是接管前的状态而非我们写进去的值。
     */
    private void capturePreTakeoverState(Object instance) {
        if (preTakeoverMirrorState != null || instance == null) {
            return;
        }
        try {
            Field field = findField(instance.getClass(), MIRROR_FIELD);
            if (field == null) {
                return;
            }
            field.setAccessible(true);
            preTakeoverMirrorState = field.getBoolean(instance);
            log(Log.INFO, TAG, "Mirror input state snapshot (pre-takeover) = "
                    + preTakeoverMirrorState);
        } catch (Throwable t) {
            log(Log.DEBUG, TAG, "Unable to snapshot " + MIRROR_FIELD, t);
        }
    }

    /**
     * 开关切换时立即刷新字段，不等下一次系统调用。
     *
     * <ul>
     *   <li>开：钉成 false（放行键盘）。</li>
     *   <li>关：还原成接管前快照，彻底撤销模块影响；无快照时保持不动，
     *       让系统自己在投屏开始/结束时长回去。</li>
     * </ul>
     * 任何分支都不会写入 {@code true} —— 那是投屏协同的语义，误写会直接让键盘弹不出来。
     */
    private void applyMirrorState() {
        Object instance = mirrorInstance;
        if (instance == null) {
            log(Log.DEBUG, TAG, "Mirror instance not seen yet, apply deferred");
            return;
        }
        if (isImeFixEnabled()) {
            forceMirrorState(instance, false);
            log(Log.INFO, TAG, "Mirror input state applied -> FORCE FALSE (投屏键盘放行)");
            return;
        }
        Boolean snapshot = preTakeoverMirrorState;
        if (snapshot == null) {
            log(Log.INFO, TAG, "Mirror input state: switch OFF, no snapshot -> hands off");
            return;
        }
        forceMirrorState(instance, snapshot);
        log(Log.INFO, TAG, "Mirror input state restored -> " + snapshot
                + " (接管前原值，模块已停止干预)");
    }

    private static String mirrorHookId(String rule, Class<?> clazz, Method method) {
        StringBuilder id = new StringBuilder(MIRROR_HOOK_ID_PREFIX)
                .append(rule).append(':')
                .append(clazz.getName()).append('#').append(method.getName()).append('(');
        Class<?>[] parameterTypes = method.getParameterTypes();
        for (int i = 0; i < parameterTypes.length; i++) {
            if (i > 0) {
                id.append(',');
            }
            id.append(parameterTypes[i].getName());
        }
        return id.append(')').toString();
    }

    private static String mirrorRuleOfHookId(String hookId) {
        String rest = hookId.substring(MIRROR_HOOK_ID_PREFIX.length());
        int separator = rest.indexOf(':');
        return separator < 0 ? RULE_READ_FALSE : rest.substring(0, separator);
    }

    private Object interceptTrigger(XposedInterface.Chain chain) throws Throwable {
        int depth = triggerDepth.get();
        triggerDepth.set(depth + 1);

        try {
            List<Object> originalArgs = chain.getArgs();
            Object[] args = originalArgs.toArray(new Object[0]);

            int functionIndex = -1;
            String function = null;
            String shortcut = null;
            for (int i = 0; i < args.length; i++) {
                Object arg = args[i];
                if (Config.FUNCTION_PAYMENT.equals(arg) || Config.FUNCTION_BUS.equals(arg)) {
                    functionIndex = i;
                    function = (String) arg;
                } else if (Config.SETTING_BACK_DOUBLE.equals(arg)
                        || Config.SETTING_BACK_TRIPLE.equals(arg)) {
                    shortcut = (String) arg;
                }
            }

            if (functionIndex < 0 || function == null || shortcut == null) {
                return chain.proceed();
            }

            rememberSystemContext(extractContext(chain.getThisObject()));
            if (!statusPublished) {
                publishHookStatusWhenReady(1);
            }

            String actionPrefKey = Config.SETTING_BACK_TRIPLE.equals(shortcut)
                    ? Config.PREF_TRIPLE_ACTION
                    : Config.PREF_DOUBLE_ACTION;
            String displayPrefKey = Config.SETTING_BACK_TRIPLE.equals(shortcut)
                    ? Config.PREF_TRIPLE_DISPLAY
                    : Config.PREF_DOUBLE_DISPLAY;

            String configuredAction = readAction(actionPrefKey, function);

            // 小米钱包 NFC 刷卡：拦截后不调用原方法，直接启动钱包刷卡页
            if (Config.FUNCTION_WALLET.equals(configuredAction)) {
                boolean launched = launchWallet();
                log(Log.INFO, TAG, "BackTap " + shortcut + " -> wallet NFC, launched=" + launched);
                if (depth == 0 && readShowTips() && shouldShowTip(shortcut, configuredAction)) {
                    showTriggerTip(shortcut, configuredAction, launched ? " ✓" : " · 启动失败");
                }
                return emptyResult(chain.getExecutable());
            }

            if (Config.FUNCTION_PAYMENT.equals(configuredAction)
                    || Config.FUNCTION_BUS.equals(configuredAction)) {
                if (!configuredAction.equals(function)) {
                    args[functionIndex] = configuredAction;
                    log(Log.DEBUG, TAG, "Remapped " + shortcut + " " + function
                            + " -> " + configuredAction);
                }
                function = configuredAction;
            }

            int bundleIndex = findBundleArgument(chain.getExecutable(), args);
            if (bundleIndex >= 0) {
                Bundle bundle = args[bundleIndex] instanceof Bundle
                        ? (Bundle) args[bundleIndex]
                        : new Bundle();
                args[bundleIndex] = bundle;
                int displayId = readDisplayId(displayPrefKey);
                bundle.putInt(Config.DISPLAY_BUNDLE_KEY, displayId);
                log(Log.DEBUG, TAG, "Set " + Config.DISPLAY_BUNDLE_KEY + "=" + displayId
                        + " for " + shortcut + " -> " + function);
            } else {
                log(Log.WARN, TAG, "Matched BackTap action but no Bundle argument was found");
            }

            Object result = chain.proceed(args);
            if (depth == 0 && readShowTips() && shouldShowTip(shortcut, function)) {
                String suffix = "";
                if (result instanceof Boolean) {
                    suffix = ((Boolean) result) ? " ✓" : " · 触发失败";
                }
                showTriggerTip(shortcut, function, suffix);
            }
            return result;
        } finally {
            if (depth == 0) {
                triggerDepth.remove();
            } else {
                triggerDepth.set(depth);
            }
        }
    }

    private boolean launchWallet() {
        Context context = ensureSystemContext();
        if (context == null) {
            log(Log.ERROR, TAG, "system Context unavailable, cannot launch wallet");
            return false;
        }

        try {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(Config.WALLET_PACKAGE, Config.WALLET_ACTIVITY));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            // 与双击电源键原生路径一致，直接进入刷卡态，省去点选卡面
            intent.putExtra(Config.WALLET_EVENT_SOURCE_EXTRA, Config.WALLET_EVENT_SOURCE_VALUE);
            context.startActivity(intent);
            return true;
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Launch wallet " + Config.WALLET_ACTIVITY + " failed", t);
            return false;
        }
    }

    private static Object emptyResult(Executable executable) {
        if (executable instanceof Method) {
            Class<?> type = ((Method) executable).getReturnType();
            if (type == boolean.class || type == Boolean.class) {
                return Boolean.TRUE;
            }
        }
        return null;
    }

    private void initializeRemotePreferences() {
        if (preferences != null) {
            return;
        }
        SharedPreferences prefs = getRemotePreferences(Config.PREFS_NAME);
        SharedPreferences.OnSharedPreferenceChangeListener listener = (sharedPreferences, key) -> {
            if (Config.PREF_DOUBLE_ACTION.equals(key) || Config.PREF_TRIPLE_ACTION.equals(key)) {
                syncNativeAction(key);
            } else if (Config.PREF_MIRROR_IME_FIX_ENABLED.equals(key)) {
                onImeFixPreferenceChanged(sharedPreferences, key);
            }
        };
        prefs.registerOnSharedPreferenceChangeListener(listener);
        preferences = prefs;
        preferenceListener = listener;
        // 打印快照内容：RemotePreferences 是进程内缓存，这行是判断「模块究竟读到了什么」的唯一依据
        log(Log.INFO, TAG, "Remote Preferences connected, snapshot="
                + safeSnapshot(prefs)
                + ", imeFix=" + prefs.getBoolean(Config.PREF_MIRROR_IME_FIX_ENABLED, true));
        publishImeFixState(prefs.getBoolean(Config.PREF_MIRROR_IME_FIX_ENABLED, true));
    }

    private void detachPreferenceListener() {
        SharedPreferences prefs = preferences;
        SharedPreferences.OnSharedPreferenceChangeListener listener = preferenceListener;
        if (prefs != null && listener != null) {
            try {
                prefs.unregisterOnSharedPreferenceChangeListener(listener);
            } catch (Throwable t) {
                log(Log.WARN, TAG, "Unable to unregister Remote Preferences listener", t);
            }
        }
        preferenceListener = null;
    }

    private void syncAllNativeActionsSafely() {
        try {
            syncNativeAction(Config.PREF_DOUBLE_ACTION);
            syncNativeAction(Config.PREF_TRIPLE_ACTION);
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Failed to sync native actions", t);
        }
    }

    private void syncNativeAction(String prefKey) {
        SharedPreferences prefs = preferences;
        if (prefs == null || !prefs.contains(prefKey)) {
            return;
        }

        String action = prefs.getString(prefKey, Config.FUNCTION_NONE);
        if (!isAllowedAction(action)) {
            return;
        }

        String settingKey = Config.PREF_TRIPLE_ACTION.equals(prefKey)
                ? Config.SETTING_BACK_TRIPLE
                : Config.SETTING_BACK_DOUBLE;
        // 钱包/付款码/乘车码都以系统认可的付款码动作作为占位，保证手势开关保持开启
        String nativeFunction = Config.FUNCTION_NONE.equals(action)
                ? Config.FUNCTION_NONE
                : Config.FUNCTION_PAYMENT;

        Context context = ensureSystemContext();
        if (context == null) {
            log(Log.WARN, TAG, "Cannot sync " + settingKey + ": system Context unavailable");
            return;
        }

        try {
            boolean ok = Settings.System.putString(
                    context.getContentResolver(), settingKey, nativeFunction);
            log(Log.INFO, TAG, "Synced " + settingKey + "=" + nativeFunction
                    + " (configured=" + action + ") result=" + ok);
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Failed to sync native BackTap setting", t);
        }
    }

    private String readAction(String prefKey, String fallback) {
        SharedPreferences prefs = preferences;
        if (prefs == null) {
            return fallback;
        }
        String stored = prefs.getString(prefKey, fallback);
        return isAllowedAction(stored) ? stored : fallback;
    }

    private int readDisplayId(String prefKey) {
        SharedPreferences prefs = preferences;
        String display = prefs == null
                ? Config.DISPLAY_REAR
                : prefs.getString(prefKey, Config.DISPLAY_REAR);
        return Config.DISPLAY_MAIN.equals(display)
                ? Config.DISPLAY_ID_MAIN
                : Config.DISPLAY_ID_REAR;
    }

    private boolean readShowTips() {
        SharedPreferences prefs = preferences;
        return prefs != null && prefs.getBoolean(Config.PREF_SHOW_TIPS, false);
    }

    private static boolean isAllowedAction(String action) {
        return Config.FUNCTION_NONE.equals(action)
                || Config.FUNCTION_PAYMENT.equals(action)
                || Config.FUNCTION_BUS.equals(action)
                || Config.FUNCTION_WALLET.equals(action);
    }

    private boolean shouldShowTip(String shortcut, String function) {
        String key = shortcut + "|" + function;
        long now = SystemClock.uptimeMillis();
        synchronized (tipDedupLock) {
            if (key.equals(lastTipKey) && now - lastTipUptime < TIP_DEDUP_WINDOW_MS) {
                return false;
            }
            lastTipKey = key;
            lastTipUptime = now;
            return true;
        }
    }

    private void showTriggerTip(String shortcut, String function, String resultSuffix) {
        Context context = ensureSystemContext();
        if (context == null) {
            return;
        }

        String tapText = Config.SETTING_BACK_TRIPLE.equals(shortcut) ? "背部三击" : "背部双击";
        String functionText;
        if (Config.FUNCTION_WALLET.equals(function)) {
            functionText = "小米钱包刷卡";
        } else if (Config.FUNCTION_BUS.equals(function)) {
            functionText = "支付宝乘车码";
        } else {
            functionText = "支付宝付款码";
        }
        String message = tapText + " · " + functionText + resultSuffix;

        getSystemMainHandler().post(() -> {
            try {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
            } catch (Throwable t) {
                log(Log.WARN, TAG, "Unable to show trigger Tips", t);
            }
        });
    }

    private void publishHookStatusWhenReady(int attempt) {
        if (!hooksInstalled || statusPublished) {
            return;
        }

        Context context = ensureSystemContext();
        if (context != null) {
            try {
                ContentResolver resolver = context.getContentResolver();
                int bootCount = Settings.Global.getInt(resolver, Settings.Global.BOOT_COUNT, -1);
                boolean versionOk = Settings.System.putString(
                        resolver,
                        Config.STATUS_HOOK_VERSION,
                        BuildConfig.VERSION_NAME
                );
                boolean bootOk = Settings.System.putInt(
                        resolver,
                        Config.STATUS_HOOK_BOOT_COUNT,
                        bootCount
                );
                statusPublished = versionOk && bootOk;
                if (statusPublished) {
                    log(Log.INFO, TAG, "Published Modern Hook status version="
                            + BuildConfig.VERSION_NAME + ", bootCount=" + bootCount);
                    return;
                }
                log(Log.WARN, TAG, "Settings.System rejected Hook status write");
            } catch (Throwable t) {
                if (attempt == 1 || attempt % 10 == 0) {
                    log(Log.WARN, TAG, "Hook status publish attempt " + attempt + " failed", t);
                }
            }
        } else if (attempt == 1 || attempt % 10 == 0) {
            log(Log.WARN, TAG, "system Context not ready for Hook status, attempt=" + attempt);
        }

        if (!statusPublished && attempt < MAX_STATUS_ATTEMPTS) {
            getSystemMainHandler().postDelayed(
                    () -> publishHookStatusWhenReady(attempt + 1),
                    INSTALL_RETRY_DELAY_MS
            );
        }
    }

    private Handler getSystemMainHandler() {
        Handler handler = systemMainHandler;
        if (handler != null) {
            return handler;
        }
        synchronized (this) {
            handler = systemMainHandler;
            if (handler == null) {
                Looper looper = Looper.getMainLooper();
                if (looper == null) {
                    looper = Looper.myLooper();
                }
                if (looper == null) {
                    throw new IllegalStateException("No Looper available in system_server");
                }
                handler = new Handler(looper);
                systemMainHandler = handler;
            }
        }
        return handler;
    }

    private Context ensureSystemContext() {
        Context context = systemContext;
        if (context == null) {
            context = resolveSystemContext();
            rememberSystemContext(context);
        }
        return context;
    }

    private void rememberSystemContext(Context context) {
        if (context == null) {
            return;
        }
        systemContext = context;
        if (systemMainHandler == null && Looper.getMainLooper() != null) {
            systemMainHandler = new Handler(Looper.getMainLooper());
        }
    }

    private Context resolveSystemContext() {
        try {
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Method current = activityThreadClass.getDeclaredMethod("currentActivityThread");
            current.setAccessible(true);
            Object activityThread = current.invoke(null);
            if (activityThread == null) {
                return null;
            }
            Method getSystemContext = activityThreadClass.getDeclaredMethod("getSystemContext");
            getSystemContext.setAccessible(true);
            Object value = getSystemContext.invoke(activityThread);
            return value instanceof Context ? (Context) value : null;
        } catch (Throwable t) {
            log(Log.DEBUG, TAG, "ActivityThread system Context not ready", t);
            return null;
        }
    }

    private ClassLoader findSystemServerClassLoader() {
        ClassLoader[] candidates = new ClassLoader[]{
                systemServerClassLoader,
                Thread.currentThread().getContextClassLoader(),
                ClassLoader.getSystemClassLoader()
        };
        for (ClassLoader candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            try {
                Class.forName(TARGET_CLASS, false, candidate);
                log(Log.INFO, TAG, "Recovered system_server classloader: " + candidate);
                return candidate;
            } catch (Throwable ignored) {
                // Try the next candidate.
            }
        }
        return null;
    }

    private Context extractContext(Object object) {
        if (object == null) {
            return null;
        }
        for (String fieldName : new String[]{"mContext", "mSystemContext"}) {
            try {
                Field field = findField(object.getClass(), fieldName);
                if (field == null) {
                    continue;
                }
                field.setAccessible(true);
                Object value = field.get(object);
                if (value instanceof Context) {
                    return (Context) value;
                }
            } catch (Throwable ignored) {
                // Try the next known field name.
            }
        }
        return null;
    }

    private static Field findField(Class<?> clazz, String name) {
        Class<?> current = clazz;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private static int findBundleArgument(Executable executable, Object[] args) {
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof Bundle) {
                return i;
            }
        }
        Class<?>[] parameterTypes = executable.getParameterTypes();
        for (int i = 0; i < parameterTypes.length; i++) {
            if (Bundle.class.isAssignableFrom(parameterTypes[i])) {
                return i;
            }
        }
        return -1;
    }

    private static String hookId(Method method) {
        StringBuilder id = new StringBuilder(HOOK_ID_PREFIX).append(method.getName()).append('(');
        Class<?>[] parameterTypes = method.getParameterTypes();
        for (int i = 0; i < parameterTypes.length; i++) {
            if (i > 0) {
                id.append(',');
            }
            id.append(parameterTypes[i].getName());
        }
        return id.append(')').toString();
    }
}
