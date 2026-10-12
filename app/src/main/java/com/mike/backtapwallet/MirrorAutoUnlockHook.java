package com.mike.backtapwallet;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import io.github.libxposed.api.XposedInterface;

/**
 * 投屏自动解锁 —— Modern Xposed API 102 移植版。
 *
 * <p>妙享 / 互联互通投屏连接时，自动用本机凭据解锁，无需在 PC 端或手机上输入密码。
 * 全部逻辑在 SystemUI 进程内闭环，不依赖 system_server、不跨进程通信、不需要额外权限。
 *
 * <pre>
 * 触发 → 判投屏（存在 screen-mirror-* 虚拟显示） → LockPatternUtils.checkCredential(凭据)
 *     → VerifyCredentialResponse.isMatched() → KeyguardViewMediator.handleKeyguardDone()
 * </pre>
 *
 * <p>两条触发路径并存，缺一会漏场景：
 * <ul>
 *   <li>A：hook {@code KeyguardViewMediator#showKeyguard} —— 投屏在先，之后锁屏</li>
 *   <li>B：{@link DisplayManager.DisplayListener#onDisplayAdded} —— 锁屏在先，之后连投屏</li>
 * </ul>
 * 实测妙享连接时 {@code onDisplayAdded} 比 {@code showKeyguard} 晚约 1.2s，两条都必须保留。
 *
 * <p>两个必须维持的设计（改代码时勿"简化"）：
 * <ol>
 *   <li>{@code LockPatternUtils.checkCredential} <b>禁止主线程</b>，而
 *       {@code KeyguardViewMediator.handleKeyguardDone} <b>必须主线程</b>（内部碰 View）。
 *       因此形状固定为：后台线程验证 → 通过后 post 回主线程收屏。</li>
 *   <li>只有 {@code handleKeyguardDone()} 是完整收屏路径；{@code dismiss()} /
 *       {@code setShowingLocked(false)} 只改标志位，会留下锁屏与桌面叠加的残影，禁止替换。</li>
 * </ol>
 */
public final class MirrorAutoUnlockHook {

    public static final String HOOK_ID_PREFIX = "backtap-wallet:unlock:";

    private static final String TAG = "BackTapWallet";
    private static final long RETRY_DELAY_MS = 1000L;
    private static final int MAX_INSTALL_ATTEMPTS = 30;
    private static final long TRIGGER_THROTTLE_MS = 1000L;
    private static final String MIRROR_DISPLAY_PREFIX = "screen-mirror";
    /** 解锁成功后的可见回执文案。 */
    private static final String UNLOCK_TOAST_TEXT = "投屏已连接，自动解锁完成";

    private static volatile XposedInterface xposed;
    private static volatile ClassLoader classLoader;
    private static volatile SharedPreferences preferences;
    private static volatile Object keyguardMediator;
    private static volatile boolean hooked;
    private static volatile boolean listenerRegistered;
    private static volatile long lastTriggerAt;

    private MirrorAutoUnlockHook() {
    }

    // ==================================================================
    // 安装
    // ==================================================================

    /**
     * 在 SystemUI 进程中安装。失败会自动重试（SystemUI 启动早期类可能尚未就绪）。
     */
    public static void install(XposedInterface module, ClassLoader loader, int attempt) {
        if (hooked) {
            return;
        }
        xposed = module;
        classLoader = loader;
        ensurePreferences();

        try {
            Class<?> mediatorClass = findKeyguardMediatorClass();
            if (mediatorClass == null) {
                scheduleRetry(loader, attempt, "KeyguardViewMediator not found");
                return;
            }

            int count = hookShowKeyguard(mediatorClass);
            if (count <= 0) {
                scheduleRetry(loader, attempt, "showKeyguard not found");
                return;
            }

            hooked = true;
            log(Log.INFO, "Auto-unlock hook ready, showKeyguard hooked x" + count);
            startDisplayWatch();
        } catch (Throwable t) {
            scheduleRetry(loader, attempt, t);
        }
    }

    private static void scheduleRetry(ClassLoader loader, int attempt, Object reason) {
        if (attempt == 1 || attempt % 10 == 0 || attempt >= MAX_INSTALL_ATTEMPTS) {
            String message = "Auto-unlock install attempt " + attempt + "/"
                    + MAX_INSTALL_ATTEMPTS + " pending: " + reason;
            if (reason instanceof Throwable) {
                log(Log.WARN, message, (Throwable) reason);
            } else {
                log(Log.WARN, message, null);
            }
        }
        if (attempt >= MAX_INSTALL_ATTEMPTS) {
            return;
        }
        try {
            new Handler(Looper.getMainLooper()).postDelayed(
                    () -> install(xposed, loader, attempt + 1), RETRY_DELAY_MS);
        } catch (Throwable t) {
            log(Log.ERROR, "Unable to schedule auto-unlock retry", t);
        }
    }

    private static int hookShowKeyguard(Class<?> mediatorClass) {
        int count = 0;
        for (Method method : mediatorClass.getDeclaredMethods()) {
            if (!"showKeyguard".equals(method.getName())) {
                continue;
            }
            try {
                method.setAccessible(true);
                xposed.hook(method)
                        .setId(HOOK_ID_PREFIX + method.getName() + '(' + signatureOf(method) + ')')
                        .intercept(MirrorAutoUnlockHook::onShowKeyguard);
                count++;
            } catch (Throwable t) {
                log(Log.WARN, "Unable to hook showKeyguard", t);
            }
        }
        return count;
    }

    /** 热重载时按 hook ID 取回拦截器。 */
    public static XposedInterface.Hooker hookerFor(String hookId) {
        return MirrorAutoUnlockHook::onShowKeyguard;
    }

    /** 热重载前重置状态。 */
    public static void reset() {
        hooked = false;
        listenerRegistered = false;
        keyguardMediator = null;
        lastTriggerAt = 0L;
        preferences = null;
        xposed = null;
        classLoader = null;
    }

    // ==================================================================
    // 路径 A：投屏在先 → 之后锁屏
    // ==================================================================

    private static Object onShowKeyguard(XposedInterface.Chain chain) throws Throwable {
        Object result = chain.proceed();
        try {
            Object instance = chain.getThisObject();
            if (instance != null) {
                keyguardMediator = instance;   // 顺手缓存，供路径 B 使用
                long now = System.currentTimeMillis();
                if (now - lastTriggerAt >= TRIGGER_THROTTLE_MS) {
                    lastTriggerAt = now;
                    startUnlockThread(instance, "showKeyguard", false);
                }
            }
        } catch (Throwable t) {
            log(Log.WARN, "showKeyguard post-processing failed", t);
        }
        return result;
    }

    // ==================================================================
    // 路径 B：锁屏在先 → 之后连投屏
    // ==================================================================

    private static void startDisplayWatch() {
        Thread thread = new Thread(() -> {
            for (int i = 0; i < 60; i++) {          // 最多等 30s
                Context context = appContext();
                if (context != null && registerDisplayListener(context)) {
                    return;
                }
                try {
                    Thread.sleep(500L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            log(Log.WARN, "Display watch gave up after 30s", null);
        }, "backtap-display-watch");
        thread.setDaemon(true);
        thread.start();
    }

    private static boolean registerDisplayListener(Context context) {
        if (listenerRegistered) {
            return true;
        }
        try {
            DisplayManager displayManager =
                    (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
            if (displayManager == null) {
                return false;
            }
            displayManager.registerDisplayListener(new DisplayManager.DisplayListener() {
                @Override
                public void onDisplayAdded(int displayId) {
                    Display display = displayManager.getDisplay(displayId);
                    String name = display == null ? null : display.getName();
                    log(Log.INFO, "onDisplayAdded #" + displayId + " name=" + name, null);
                    if (name != null && name.startsWith(MIRROR_DISPLAY_PREFIX)) {
                        // 多段延迟：虚拟显示刚建立时 keyguard 可能还没到，一次会扑空
                        scheduleUnlock("display-added", 500L);
                        scheduleUnlock("display-added", 1500L);
                        scheduleUnlock("display-added", 3000L);
                    }
                }

                @Override
                public void onDisplayChanged(int displayId) {
                }

                @Override
                public void onDisplayRemoved(int displayId) {
                    log(Log.INFO, "onDisplayRemoved #" + displayId, null);
                }
            }, new Handler(Looper.getMainLooper()));
            listenerRegistered = true;
            log(Log.INFO, "Display listener registered", null);
            return true;
        } catch (Throwable t) {
            log(Log.WARN, "registerDisplayListener failed", t);
            return false;
        }
    }

    private static void scheduleUnlock(final String from, long delayMs) {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            Object mediator = keyguardMediator != null ? keyguardMediator : findKeyguardMediatorInstance();
            if (mediator == null) {
                log(Log.DEBUG, from + ": KeyguardViewMediator not found yet, skip", null);
                return;
            }
            keyguardMediator = mediator;
            startUnlockThread(mediator, from, true);
        }, delayMs);
    }

    /** 不依赖 showKeyguard 被触发过：从 SystemUIApplicationImpl.mServices 中按类名捞实例。 */
    private static Object findKeyguardMediatorInstance() {
        try {
            Context context = appContext();
            if (context == null) {
                return null;
            }
            Field field = findField(context.getClass(), "mServices");
            if (field == null) {
                return null;
            }
            field.setAccessible(true);
            Object ref = field.get(context);
            Object value = ref instanceof AtomicReference ? ((AtomicReference<?>) ref).get() : ref;
            if (value instanceof Object[]) {
                for (Object item : (Object[]) value) {
                    if (item != null && item.getClass().getName().endsWith("KeyguardViewMediator")) {
                        return item;
                    }
                }
            }
        } catch (Throwable t) {
            log(Log.WARN, "findKeyguardMediatorInstance failed: " + t, null);
        }
        return null;
    }

    // ==================================================================
    // 解锁主流程
    // ==================================================================

    private static void startUnlockThread(final Object mediator, final String from, final boolean requireLocked) {
        Thread thread = new Thread(() -> {
            try {
                tryUnlock(mediator, from, requireLocked);   // checkCredential 不能在主线程
            } catch (Throwable t) {
                log(Log.ERROR, "unlock(" + from + ") failed", t);
            }
        }, "backtap-mirror-unlock");
        thread.setDaemon(true);
        thread.start();
    }

    private static void tryUnlock(Object mediator, String from, boolean requireLocked) throws Throwable {
        if (!isUnlockEnabled()) {
            log(Log.DEBUG, from + ": auto unlock disabled, skip", null);
            return;
        }

        Context context = appContext();
        if (context == null) {
            log(Log.DEBUG, from + ": context not ready, skip", null);
            return;
        }

        List<String> displays = new ArrayList<>();
        boolean casting = isCasting(context, displays);
        log(Log.INFO, from + ": casting=" + casting + " displays=" + displays, null);
        if (!casting) {
            return;                                  // 没投屏 → 完全不动作（防误判）
        }

        if (requireLocked) {
            boolean locked = isKeyguardLocked(context);
            log(Log.INFO, from + ": keyguardLocked=" + locked, null);
            if (!locked) {
                return;                              // 已在桌面 → 后续重试自动跳过（幂等）
            }
            wakeUpIfNeeded(context);                 // 屏幕灭着先点亮，否则解完仍是黑屏
        }

        Object lockPatternUtils = getObjectField(mediator, "mLockPatternUtils");
        if (lockPatternUtils == null) {
            log(Log.WARN, from + ": mLockPatternUtils unavailable, skip", null);
            return;
        }

        String secret = readSecret();
        if (secret == null || secret.isEmpty()) {
            log(Log.WARN, from + ": no credential configured, skip", null);
            return;
        }

        Object credential;
        try {
            credential = buildCredential(secret, isPatternSecret(secret));
        } catch (Throwable t) {
            log(Log.ERROR, "buildCredential failed", t);
            return;
        }
        if (credential == null) {
            log(Log.WARN, from + ": buildCredential returned null", null);
            return;
        }

        int userId = currentUser(mediator);
        Object response;
        try {
            response = checkCredential(lockPatternUtils, credential, userId);
        } catch (Throwable t) {
            log(Log.ERROR, "checkCredential failed", t);
            return;
        }
        boolean matched = isMatched(response);
        log(Log.INFO, from + ": checkCredential userId=" + userId
                + " -> " + response + " matched=" + matched, null);
        if (!matched) {
            return;
        }

        // handleKeyguardDone 碰 View，必须回主线程
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                Method done = mediator.getClass().getDeclaredMethod("handleKeyguardDone");
                done.setAccessible(true);
                done.invoke(mediator);
                log(Log.INFO, from + ": handleKeyguardDone OK", null);
                showUnlockToast(context);
            } catch (Throwable t) {
                log(Log.ERROR, "handleKeyguardDone failed", t);
            }
        });
    }

    /**
     * 解锁成功后给用户一条可见回执。
     *
     * <p>本 Hook 跑在 SystemUI 进程，其 uid 为 {@code android.uid.system}(1000)，
     * 属系统 uid 且已持有 {@code SYSTEM_ALERT_WINDOW} / {@code POST_NOTIFICATIONS}，
     * 因此 Android 13+ 的后台 Toast 限制不会拦截。必须在主线程调用（已由调用点保证）。</p>
     */
    private static void showUnlockToast(Context context) {
        if (context == null) {
            return;
        }
        try {
            Toast.makeText(context.getApplicationContext(), UNLOCK_TOAST_TEXT, Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            log(Log.WARN, "unlock toast failed", t);
        }
    }

    // ==================================================================
    // 判据与状态
    // ==================================================================

    /** 投屏判据：存在名字以 screen-mirror 开头的虚拟显示。实测 screen-mirror-ScreenRecorder */
    private static boolean isCasting(Context context, List<String> names) {
        try {
            DisplayManager displayManager =
                    (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
            if (displayManager == null) {
                return false;
            }
            Display[] displays = displayManager.getDisplays();
            if (displays == null) {
                return false;
            }
            boolean found = false;
            for (Display display : displays) {
                String name = display.getName();
                names.add(name + "#" + display.getDisplayId());
                if (name != null && name.startsWith(MIRROR_DISPLAY_PREFIX)) {
                    found = true;
                }
            }
            return found;
        } catch (Throwable t) {
            log(Log.WARN, "isCasting failed: " + t, null);
            return false;
        }
    }

    private static boolean isKeyguardLocked(Context context) {
        try {
            android.app.KeyguardManager manager =
                    (android.app.KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
            return manager != null && manager.isKeyguardLocked();
        } catch (Throwable t) {
            log(Log.WARN, "isKeyguardLocked failed: " + t, null);
            return false;
        }
    }

    private static void wakeUpIfNeeded(Context context) {
        try {
            PowerManager powerManager =
                    (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (powerManager == null || powerManager.isInteractive()) {
                return;
            }
            // wakeUp(long) 是 @hide，android.jar 无符号，直接调编译不过，必须反射
            Method method = powerManager.getClass().getDeclaredMethod("wakeUp", long.class);
            method.setAccessible(true);
            method.invoke(powerManager, SystemClock.uptimeMillis());
            log(Log.INFO, "wakeUp called", null);
        } catch (Throwable t) {
            log(Log.WARN, "wakeUp failed: " + t, null);
        }
    }

    // ==================================================================
    // 凭据
    // ==================================================================

    private static Class<?> loadClass(String name) throws Throwable {
        try {
            return Class.forName(name, false, classLoader);
        } catch (Throwable first) {
            return Class.forName(name);
        }
    }

    private static Object buildCredential(String secret, boolean isPattern) throws Throwable {
        Class<?> credentialClass = loadClass("com.android.internal.widget.LockscreenCredential");

        if (isPattern) {
            Class<?> cellClass = loadClass("com.android.internal.widget.LockPatternView$Cell");
            Method of = cellClass.getDeclaredMethod("of", int.class, int.class);
            of.setAccessible(true);
            List<Object> cells = new ArrayList<>();
            for (int i = 0; i < secret.length(); i++) {
                int value = Character.digit(secret.charAt(i), 10);
                if (value <= 0 || value > 9) {
                    continue;
                }
                cells.add(of.invoke(null, (value - 1) / 3, (value - 1) % 3));   // 九宫格 1..9
            }
            if (cells.isEmpty()) {
                return null;
            }
            Method createPattern = credentialClass.getDeclaredMethod("createPattern", List.class);
            createPattern.setAccessible(true);
            return createPattern.invoke(null, cells);
        }

        // 数字串可能是 PIN 也可能是密码，两个都试
        for (String name : new String[]{"createPin", "createPassword"}) {
            try {
                Method method = credentialClass.getDeclaredMethod(name, CharSequence.class);
                method.setAccessible(true);
                return method.invoke(null, (CharSequence) secret);
            } catch (Throwable ignored) {
                // 试下一个
            }
        }
        return null;
    }

    /** 签名随版本变过（int / long userId），按参数类型适配。 */
    private static Object checkCredential(Object lockPatternUtils, Object credential, int userId)
            throws Throwable {
        Method target = null;
        for (Method method : lockPatternUtils.getClass().getMethods()) {
            if ("checkCredential".equals(method.getName()) && method.getParameterTypes().length == 3) {
                target = method;
                break;
            }
        }
        if (target == null) {
            throw new NoSuchMethodException("checkCredential/3");
        }
        target.setAccessible(true);
        Class<?>[] parameterTypes = target.getParameterTypes();
        Object[] args = new Object[3];
        args[0] = credential;
        args[1] = (parameterTypes[1] == int.class || parameterTypes[1] == Integer.class)
                ? (Object) Integer.valueOf(userId)
                : (Object) Long.valueOf(userId);
        args[2] = null;
        return target.invoke(lockPatternUtils, args);
    }

    /** Android 15+ 返回 VerifyCredentialResponse（用 isMatched），老版本返回 boolean。 */
    private static boolean isMatched(Object response) {
        if (response == null) {
            return false;
        }
        if (response instanceof Boolean) {
            return (Boolean) response;
        }
        try {
            Method method = response.getClass().getMethod("isMatched");
            method.setAccessible(true);
            Object result = method.invoke(response);
            return result instanceof Boolean && (Boolean) result;
        } catch (Throwable t) {
            log(Log.WARN, "isMatched fallback failed: " + t, null);
            return false;
        }
    }

    // ==================================================================
    // 配置来源
    // ==================================================================

    /**
     * 优先级：
     * <ol>
     *   <li>模块 Remote Preferences（App 内配置）</li>
     *   <li>{@code Settings.Secure.mirror_unlock_secret}（adb 覆盖，无需重编译）</li>
     * </ol>
     * 都未配置时返回 null，解锁逻辑直接跳过。
     */
    private static String readSecret() {
        SharedPreferences prefs = ensurePreferences();
        if (prefs != null) {
            String value = prefs.getString(Config.PREF_MIRROR_UNLOCK_SECRET, "");
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return secureGet(Config.SECURE_UNLOCK_SECRET);
    }

    private static String readSecretType() {
        SharedPreferences prefs = ensurePreferences();
        if (prefs != null) {
            String value = prefs.getString(Config.PREF_MIRROR_UNLOCK_TYPE, "");
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return secureGet(Config.SECURE_UNLOCK_TYPE);
    }

    private static boolean isPatternSecret(String secret) {
        String explicit = readSecretType();
        if (explicit != null && !explicit.isEmpty()) {
            String type = explicit.toLowerCase();
            if (type.startsWith("pat")) {
                return true;
            }
            if (type.startsWith("pin") || type.startsWith("pass")) {
                return false;
            }
        }
        for (int i = 0; i < secret.length(); i++) {
            if (!Character.isDigit(secret.charAt(i))) {
                return false;
            }
        }
        return true;   // 全数字且未显式指定类型 → 按图案处理
    }

    private static boolean isUnlockEnabled() {
        SharedPreferences prefs = ensurePreferences();
        return prefs != null && prefs.getBoolean(Config.PREF_MIRROR_UNLOCK_ENABLED, false);
    }

    private static SharedPreferences ensurePreferences() {
        SharedPreferences cached = preferences;
        if (cached != null) {
            return cached;
        }
        XposedInterface module = xposed;
        if (module == null) {
            return null;
        }
        try {
            cached = module.getRemotePreferences(Config.PREFS_NAME);
            preferences = cached;
        } catch (Throwable t) {
            log(Log.DEBUG, "Remote Preferences unavailable: " + t, null);
        }
        return cached;
    }

    private static String secureGet(String key) {
        try {
            Context context = appContext();
            if (context == null) {
                return null;
            }
            String value = Settings.Secure.getString(context.getContentResolver(), key);
            if (value == null) {
                return null;
            }
            value = value.trim();
            return value.isEmpty() ? null : value;
        } catch (Throwable t) {
            log(Log.WARN, "secureGet(" + key + ") failed: " + t, null);
            return null;
        }
    }

    // ==================================================================
    // 杂项
    // ==================================================================

    private static Class<?> findKeyguardMediatorClass() {
        String[] names = {
                "com.android.systemui.keyguard.KeyguardViewMediator",   // AOSP / HyperOS 实测命中
                "com.android.keyguard.KeyguardViewMediator"             // MIUI 旧版
        };
        for (String name : names) {
            try {
                Class<?> clazz = Class.forName(name, false, classLoader);
                log(Log.INFO, "KeyguardViewMediator resolved: " + name, null);
                return clazz;
            } catch (Throwable ignored) {
                // 试下一个
            }
        }
        return null;
    }

    private static Object getObjectField(Object target, String name) {
        try {
            Field field = findField(target.getClass(), name);
            if (field == null) {
                return null;
            }
            field.setAccessible(true);
            return field.get(target);
        } catch (Throwable t) {
            log(Log.WARN, "Unable to read field " + name + ": " + t, null);
            return null;
        }
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

    private static int currentUser(Object mediator) {
        Object value = getObjectField(mediator, "mCurrentUserId");
        if (value instanceof Integer) {
            return (Integer) value;
        }
        try {
            Method method = Class.forName("android.app.ActivityManager")
                    .getMethod("getCurrentUser");
            Object result = method.invoke(null);
            if (result instanceof Integer) {
                return (Integer) result;
            }
        } catch (Throwable ignored) {
            // 保持默认
        }
        return 0;
    }

    private static Context appContext() {
        try {
            Method method = Class.forName("android.app.ActivityThread")
                    .getDeclaredMethod("currentApplication");
            method.setAccessible(true);
            return (Context) method.invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String signatureOf(Method method) {
        StringBuilder builder = new StringBuilder();
        Class<?>[] parameterTypes = method.getParameterTypes();
        for (int i = 0; i < parameterTypes.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(parameterTypes[i].getName());
        }
        return builder.toString();
    }

    private static void log(int priority, String message) {
        log(priority, message, null);
    }

    private static void log(int priority, String message, Throwable throwable) {
        XposedInterface module = xposed;
        if (module == null) {
            return;
        }
        try {
            if (throwable == null) {
                module.log(priority, TAG, "unlock: " + message);
            } else {
                module.log(priority, TAG, "unlock: " + message, throwable);
            }
        } catch (Throwable ignored) {
            // 日志失败不影响功能
        }
    }
}
