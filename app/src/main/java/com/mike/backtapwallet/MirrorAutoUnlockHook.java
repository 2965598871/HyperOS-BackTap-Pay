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
 * <p>路径 B 采用<b>密集轮询</b>而非固定档位：投屏建立时手机往往尚未锁屏，keyguard 何时
 * 锁上无法预知，早期 0.5s / 1.5s / 3.0s 三档做法最坏要白等 3.1s。现按
 * {@link Config#PREF_MIRROR_UNLOCK_POLL_INTERVAL}（默认 150ms）轮询，
 * 命中即解、解完立刻停表（{@link #unlockDone}），并受 {@link #POLL_TIMEOUT_MS} 兜底。
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

    /**
     * 单次轮询会话的最长持续时间。
     *
     * <p>守卫作用：投屏建立但用户始终不锁屏时，轮询必须自行停止，不能无限占用。
     * 超过此时长仍未命中即收工，后续若锁屏会由路径 A 或下一次 {@code onDisplayAdded} 接管。</p>
     */
    private static final long POLL_TIMEOUT_MS = 15_000L;

    private static volatile XposedInterface xposed;
    private static volatile ClassLoader classLoader;
    private static volatile SharedPreferences preferences;
    private static volatile Object keyguardMediator;
    private static volatile boolean hooked;
    private static volatile boolean listenerRegistered;
    private static volatile long lastTriggerAt;

    /** 当前轮询会话的序号；每次新会话自增，用于作废旧会话仍在跑的循环。 */
    private static volatile int pollGeneration;

    /**
     * 「本轮投屏已经解锁完成」标志。
     *
     * <p>幂等守卫 {@code isKeyguardLocked()} 只在解锁**真正收屏之后**才变 false，
     * 若两条轮询任务并发进入，会各自跑一遍完整的 GateKeeper 硬件校验（实测浪费一次
     * TEE 往返）。本标志在 {@code handleKeyguardDone} 成功那一刻置位，后续轮询进入即短路。</p>
     */
    private static final java.util.concurrent.atomic.AtomicBoolean unlockDone =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    /** 反射类预热是否已完成，避免重复开销。 */
    private static final java.util.concurrent.atomic.AtomicBoolean prewarmed =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * 解锁执行互斥锁：保证同一时刻只有一条线程在跑 {@code checkCredential}。
     *
     * <p>这是防雪崩的关键护栏。{@code checkCredential} 单次耗时 0.9~2.2 秒，
     * 任何"每个间隔起一条线程"的写法都会让线程数呈指数累积，足以压死 SystemUI。</p>
     */
    private static final java.util.concurrent.atomic.AtomicBoolean unlockRunning =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    /** 当前持锁执行的开始时刻，供看门狗判定是否卡死。 */
    private static volatile long unlockStartedAt;
    /** 看门狗阈值：单次解锁（含 TEE 校验）超过此时长即认为卡死并强制释放锁。 */
    private static final long UNLOCK_WATCHDOG_MS = 10_000L;

    /** 统一的「取锁 → 执行 → 放锁」封装，避免各处重复且遗漏看门狗。 */
    private static void runUnlockGuarded(Object mediator, String from,
                                         boolean requireLocked, int generation) {
        if (!unlockRunning.compareAndSet(false, true)) {
            log(Log.DEBUG, from + ": unlock already in flight, skip", null);
            return;
        }
        final long startedAt = SystemClock.uptimeMillis();
        unlockStartedAt = startedAt;
        armUnlockWatchdog(startedAt);
        try {
            tryUnlock(mediator, from, requireLocked, generation);
        } catch (Throwable t) {
            log(Log.ERROR, "unlock(" + from + ") failed", t);
        } finally {
            unlockRunning.set(false);
        }
    }

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
            prewarmReflection();
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
        // 热重载走本方法替换 Hook，不会重新执行 install()，
        // 因此预热也必须挂在这里，否则热重载后首次解锁又回到冷态。
        prewarmReflection();
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
        // 作废进行中的轮询会话：自增 generation 使旧循环在下一轮判断时自行退出
        pollGeneration++;
        unlockDone.set(false);
        unlockRunning.set(false);
        prewarmed.set(false);
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
                if (!isShowKeyguardPathEnabled()) {
                    // 路径 A 独立开关默认关闭：投屏中手动锁屏不应被立即解掉
                    log(Log.DEBUG, "showKeyguard: path A disabled by switch, skip", null);
                    return result;
                }
                long now = System.currentTimeMillis();
                if (now - lastTriggerAt >= TRIGGER_THROTTLE_MS) {
                    lastTriggerAt = now;
                    // 路径 A 是单次触发，非轮询；结算完成标志以便与路径 B 互斥
                    unlockDone.set(false);
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
                        // 新一次投屏会话：重置完成标志，开启密集轮询
                        startUnlockPoll("display-added");
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

    // ==================================================================
    // 路径 B 轮询
    // ==================================================================

    /**
     * 开启一次轮询会话。
     *
     * <p><b>必须串行</b>：整个会话只在**一条**后台线程里跑，循环体内「检查 → 必要时解锁 →
     * 睡一个间隔 → 再检查」。绝不允许用 {@code Handler.postDelayed} 定时续投 ——
     * 那样会在上一次 {@code checkCredential}（耗时 0.9~2.2s）尚未返回时又起一条新线程，
     * 150ms 的间隔会导致线程数在数十毫秒内爆炸（实测 15ms 内起 21 条），把 SystemUI 拖死。</p>
     *
     * <p>每次调用自增 {@link #pollGeneration}，使上一次会话的循环在下一轮判断时自行退出。</p>
     */
    private static void startUnlockPoll(String from) {
        final int generation = ++pollGeneration;
        final long interval = readPollInterval();
        unlockDone.set(false);
        final long deadline = SystemClock.uptimeMillis() + POLL_TIMEOUT_MS;
        log(Log.INFO, from + ": poll started, interval=" + interval + "ms, timeout="
                + POLL_TIMEOUT_MS + "ms", null);

        Thread thread = new Thread(() -> {
            int ticks = 0;
            while (true) {
                if (generation != pollGeneration) {
                    log(Log.INFO, from + ": poll stopped (superseded) after " + ticks + " ticks", null);
                    return;
                }
                if (unlockDone.get()) {
                    log(Log.INFO, from + ": poll stopped (unlock done) after " + ticks + " ticks", null);
                    return;
                }
                if (SystemClock.uptimeMillis() > deadline) {
                    log(Log.INFO, from + ": poll stopped (timeout) after " + ticks + " ticks", null);
                    return;
                }

                ticks++;
                Object mediator = keyguardMediator != null
                        ? keyguardMediator : findKeyguardMediatorInstance();
                if (mediator != null) {
                    keyguardMediator = mediator;
                    // 与路径 A 共用同一把执行锁，保证全模块同一时刻只有一条解锁线程
                    runUnlockGuarded(mediator, from, true, generation);
                }

                try {
                    Thread.sleep(interval);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log(Log.INFO, from + ": poll interrupted", null);
                    return;
                }
            }
        }, "backtap-unlock-poll");
        thread.setDaemon(true);
        thread.start();
    }

    /** 读取轮询间隔，做上下限钳制，异常时回退默认值。 */
    private static long readPollInterval() {
        int fallback = Config.DEFAULT_UNLOCK_POLL_INTERVAL_MS;
        try {
            SharedPreferences prefs = ensurePreferences();
            if (prefs != null) {
                int value = prefs.getInt(Config.PREF_MIRROR_UNLOCK_POLL_INTERVAL, fallback);
                if (value < Config.MIN_UNLOCK_POLL_INTERVAL_MS) {
                    return Config.MIN_UNLOCK_POLL_INTERVAL_MS;
                }
                if (value > Config.MAX_UNLOCK_POLL_INTERVAL_MS) {
                    return Config.MAX_UNLOCK_POLL_INTERVAL_MS;
                }
                return value;
            }
        } catch (Throwable t) {
            log(Log.WARN, "readPollInterval failed: " + t, null);
        }
        return fallback;
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

    /**
     * 起线程执行解锁（路径 A 专用，单次触发）。
     *
     * <p>用 CAS 保证同一时刻至多一条解锁线程在跑 —— 路径 A 的 1 秒节流并不能防止
     * 「上一条线程仍在 TEE 里未返回、下一条已起来」，必须显式互斥。</p>
     */
    private static void startUnlockThread(final Object mediator, final String from,
                                          final boolean requireLocked) {
        if (unlockDone.get()) {
            return;
        }
        Thread thread = new Thread(() -> runUnlockGuarded(mediator, from, requireLocked,
                pollGeneration), "backtap-mirror-unlock");
        thread.setDaemon(true);
        thread.start();
    }

    private static void tryUnlock(Object mediator, String from, boolean requireLocked)
            throws Throwable {
        tryUnlock(mediator, from, requireLocked, pollGeneration);
    }

    private static void tryUnlock(Object mediator, String from, boolean requireLocked,
                                  int generation) throws Throwable {
        if (unlockDone.get()) {
            return;                                    // 已被其他轮询任务解锁
        }
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
        if (generation != pollGeneration) {
            log(Log.INFO, from + ": session superseded, discard result", null);
            return;                                    // 会话已作废，丢弃本次结果
        }

        // 先置位，让并发的其他轮询任务立刻短路，不再重复支付 TEE 校验成本
        unlockDone.set(true);

        // handleKeyguardDone 碰 View，必须回主线程
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                Method done = mediator.getClass().getDeclaredMethod("handleKeyguardDone");
                done.setAccessible(true);
                done.invoke(mediator);
                log(Log.INFO, from + ": handleKeyguardDone OK (poll halted)", null);
                showUnlockToast(context);
            } catch (Throwable t) {
                unlockDone.set(false);                 // 收屏失败 → 放开，允许后续重试
                log(Log.ERROR, "handleKeyguardDone failed", t);
            }
        });
    }

    /**
     * 解锁执行的看门狗。
     *
     * <p>{@code checkCredential} 是同步的 TEE 调用，无法中断。若它在硬件层卡住，
     * 持锁线程会永远不释放，后续解锁（含路径 A）将全部被 {@code unlockRunning} 挡掉，
     * 表现为"自动解锁再也不生效"。本守护线程每隔一段时间检查一次开始时刻，
     * 超过 {@link #UNLOCK_WATCHDOG_MS} 未完成即强制释放锁并记录告警。</p>
     */
    private static void armUnlockWatchdog(final long startedAt) {
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(UNLOCK_WATCHDOG_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            // 若锁仍被同一次执行持有（开始时刻未变），说明它卡住了
            if (unlockRunning.get() && unlockStartedAt == startedAt) {
                log(Log.ERROR, "unlock watchdog fired after " + UNLOCK_WATCHDOG_MS
                        + "ms, force-releasing lock", null);
                unlockRunning.set(false);
            }
        }, "backtap-unlock-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
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

    /**
     * 反射类预热（方案 C）。
     *
     * <p>实测首次解锁耗时 2.16s，而热态仅 0.93s —— 差值主要来自反射类加载与 JIT 预热。
     * 这里在 hook 就绪时就把用到的类与关键方法解析一遍，把这段成本从「用户等待解锁的时刻」
     * 前移到「模块加载时刻」，用户侧不再感知。</p>
     *
     * <p>失败不影响功能：真正解锁时仍会重新解析。</p>
     */
    private static void prewarmReflection() {
        if (!prewarmed.compareAndSet(false, true)) {
            return;
        }
        Thread thread = new Thread(() -> {
            long start = SystemClock.uptimeMillis();
            try {
                Class<?> credentialClass = loadClass("com.android.internal.widget.LockscreenCredential");
                credentialClass.getDeclaredMethod("createPin", CharSequence.class).setAccessible(true);
                credentialClass.getDeclaredMethod("createPassword", CharSequence.class).setAccessible(true);
                Class<?> cellClass = loadClass("com.android.internal.widget.LockPatternView$Cell");
                cellClass.getDeclaredMethod("of", int.class, int.class).setAccessible(true);
                log(Log.INFO, "Reflection prewarmed in "
                        + (SystemClock.uptimeMillis() - start) + "ms", null);
            } catch (Throwable t) {
                log(Log.WARN, "prewarmReflection failed (non-fatal): " + t, null);
            }
        }, "backtap-prewarm");
        thread.setDaemon(true);
        thread.start();
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

    /**
     * 路径 A（showKeyguard）独立开关，默认关闭。
     *
     * <p>该路径对应「投屏在先 → 之后锁屏」：用户在投屏中手动锁屏时会立刻触发解锁，
     * 表现为"锁不上"。默认关闭后，只有路径 B（已锁屏 → 之后连投屏）在总开关开启时生效。</p>
     */
    private static boolean isShowKeyguardPathEnabled() {
        SharedPreferences prefs = ensurePreferences();
        return prefs != null
                && prefs.getBoolean(Config.PREF_MIRROR_UNLOCK_SHOWKEYGUARD_ENABLED, false);
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
