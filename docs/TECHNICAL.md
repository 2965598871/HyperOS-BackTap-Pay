# 技术说明

本文档面向需要核对实现细节或做二次移植的读者，记录两条 Hook 线的完整依据。

---

## 一、背部轻敲链路

### 1.1 系统侧调用链

HyperOS「设置 → 更多设置 → 背部轻敲」把动作写入两个系统设置项：

| 设置项 | 含义 |
|---|---|
| `Settings.System.back_double_tap` | 背部双击绑定的动作 |
| `Settings.System.back_triple_tap` | 背部三击绑定的动作 |

取值受系统白名单约束，只接受少量合法字符串，例如 `none`、`launch_alipay_payment_code`、`launch_alipay_bus_code`。**系统不认识「小米钱包」这个动作**，因此无法直接写入。

敲击发生时，输入子系统调用：

```
com.miui.server.input.util.ShortCutActionsUtils#triggerFunction(...)
```

该方法有多个重载，签名中同时携带两个关键信息：

- **功能字符串**：如 `launch_alipay_payment_code`
- **手势来源字符串**：`back_double_tap` 或 `back_triple_tap`

第二个字符串是本模块能够"只拦截背部轻敲、不影响指纹快支付等其他入口"的依据。

### 1.2 本模块的拦截策略

| 步骤 | 行为 |
|---|---|
| 1 | Hook `ShortCutActionsUtils` 的全部 `triggerFunction` 重载 |
| 2 | 从参数中识别手势来源，非 `back_double_tap` / `back_triple_tap` 立即放行 |
| 3 | 读取 Remote Preferences 中该手势对应的用户配置动作 |
| 4 | 配置为**钱包** → 不调用原方法，用 system Context 启动刷卡页，返回类型适配的空结果 |
| 5 | 配置为**支付宝付款码/乘车码** → 改写功能字符串 + 注入背屏显示参数后放行 |
| 6 | 配置为**关闭** → 原样放行 |

### 1.3 钱包启动

```java
Intent intent = new Intent();
intent.setComponent(new ComponentName(
        "com.miui.tsmclient",
        "com.miui.tsmclient.ui.quick.DoubleClickActivity"));
intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
intent.putExtra("event_source", "key_volume_down");   // 与双击电源键同路径，直接进刷卡态
context.startActivity(intent);
```

`DoubleClickActivity` 在多数 HyperOS 版本上未导出（`exported=false`），普通应用无法启动。本模块运行在 `system_server` 进程内、持有 system 权限，因此可以直接 `startActivity`，与双击电源键呼出的是同一个界面。

`event_source` 取值 `key_volume_down` 用于跳过卡面选择，直接进入待刷状态。

### 1.4 占位值机制

系统设置中始终写入 `launch_alipay_payment_code`（合法的付款码动作）作为**占位值**，作用有二：

1. 保证背部轻敲开关处于"已启用"状态，手势链路保持连通；
2. 系统永远不会读到非法值，跨重启、跨系统设置界面查看均不会失效。

模块在配置变更时通过 `Settings.System.putString` 主动同步该占位值（`syncNativeAction`）。

---

## 二、投屏输入法压制修复

### 2.1 现象

PC 控制端（妙享 / 互融协同）连接后在 App 内点击输入框，**软键盘不弹出**。`logcat` 可见：

```
ImeTracker: <pkg>: onCancelled at PHASE_CLIENT_REPORT_REQUESTED_VISIBLE_TYPES
```

即请求在客户端就被取消，根本没到达 `InputMethodManagerService#showSoftInput`。

### 2.2 成因

`InputMethodManagerServiceImpl`（位于 `miui-services.jar`，非 `services.jar`）中有一个布尔字段：

```java
private boolean mMirrorInputState;
```

投屏/协同态下该字段被写成 `true`，于是两个读点恒为真：

- `getMirrorInputState()`
- `synergyOperate()`

系统据此判定"当前处于投屏输入态"，进而压制软键盘显示。

### 2.3 DEX 证据

对 `mMirrorInputState` 做全量指令级扫描，得到**全部**访问点：

| 类型 | 位置 |
|---|---|
| `iput-boolean`（写） | `-$$Nest$fputmMirrorInputState`、`<init>`、`init` |
| `iget-boolean`（读） | `getMirrorInputState`、`synergyOperate` |

由此确定最小充分拦截面为 4 条规则。

> ⚠️ 注意：类中**不存在**名为 `putState` 的方法。`-$$Nest$fputmMirrorInputState` 是编译器为外部类访问私有字段而生成的静态合成访问器，按 `putState` 匹配会**静默失败**（不报错、不抛异常，日志里只显示装上的条数变少）。

### 2.4 四条规则

| # | 规则 | 目标 | 开关**开** | 开关**关** |
|---|---|---|---|---|
| 1 | `write` | `-$$Nest$fputmMirrorInputState` | 捕获接管前原值，再把写入值改成 `false` | 原样透传 |
| 2 | `readfalse` | `getMirrorInputState()` | 返回 `false`，**不改字段** | 原样透传 |
| 3 | `readfalse` | `synergyOperate()` | 同上 | 原样透传 |
| 4 | `initafter` | `init(...)`（5 参） | after 阶段摁回 `false` | 不动字段 |

写访问器是 `static` 合成方法，`chain.getThisObject()` 返回 `null`，**实例在 `args[0]`、写入值在 `args[1]`**，取值逻辑见 `mirrorTargetInstance()`。

### 2.4.1 语义边界：永不写 `true`

`mMirrorInputState = true` 是**投屏协同中**的语义。非投屏场景一旦为 `true`，App 侧
`InputMethodManager.showSoftInput` 会在客户端被直接取消
（`ImeTracker: onCancelled at PHASE_CLIENT_REPORT_REQUESTED_VISIBLE_TYPES`），
请求到不了 `InputMethodManagerService#showSoftInput` —— 表现就是**日常点输入框也弹不出键盘**。

因此本模块只做**单向修复**：任何分支都只写 `false`。

| 版本 | 关闭开关时的行为 | 结果 |
|---|---|---|
| v0.4.5（已废弃） | 反向压制：把字段强行写成 `true` | 日常场景键盘完全失效 |
| **v0.4.6+** | 还原接管前快照 + 四条钩子全部透传 | 撤销修复，交还系统原生行为 |

### 2.4.2 为什么读点不能改字段

早期实现让两个读点也把 `mMirrorInputState` 反射摁回 `false`。这会让字段**失去系统驱动的能力**：

| 阶段 | 字段状态 | 读点返回值 |
|---|---|---|
| 修复开启 | 每次写入被改成 `false` | `false`（键盘放行） |
| 开关关闭 | 系统不主动回写，仍是 `false` | 透传 → `false`（键盘**仍然**放行） |

结果就是「开关关了但行为没变」。修正后的不变量：

- 读点只覆盖返回值，字段始终由系统维护；
- `write` 在覆盖前捕获**接管前快照**（`preTakeoverMirrorState`，只记录一次）与承载实例（`mirrorInstance`）；
- 闸门 `ON → OFF` 时调用 `applyMirrorState()`，把字段还原为快照值；无快照则保持不动，让系统自己在投屏开始/结束时长回去。

### 2.5 日志判定

安装成功时日志为：

```
Mirror input hooks installed: 4 [write:-$$Nest$fputmMirrorInputState,
    readfalse:getMirrorInputState, initafter:init, readfalse:synergyOperate]
```

**必须出现 4 条。** 若只出现 2 条，说明该 ROM 的类结构与上表不符，需要重新 dump 签名。

---

## 三、现代 Xposed API 要点

| 项 | 说明 |
|---|---|
| API 版本 | 102（`minApiVersion=102`、`targetApiVersion=102`） |
| 入口描述 | `META-INF/xposed/java_init.list` 指向 `com.mike.backtapwallet.HookEntry` |
| 静态作用域 | `META-INF/xposed/scope.list`：`system`（背部轻敲 + 输入法修复）、`com.android.systemui`（投屏自动解锁） |
| 热重载 | `module.prop` 中 `autoHotReload=true`；Hook 以 ID 前缀区分，`onHotReloaded` 按 ID 分发重放 |
| 回调类型 | 是 `XposedInterface.Hooker`，**不是** `XposedInterface.Interceptor`（后者不存在） |
| 配置读写 | `getRemotePreferences()` / `XposedService.getRemotePreferences()` |

Hook ID 约定：

- 触发线：`backtap-wallet:trigger:<方法名>(<参数类型>)`
- 投屏线：`backtap-wallet:mirror:<规则>:<类名>#<方法名>(<参数类型>)`
- 解锁线：`backtap-wallet:unlock:<方法名>(<参数类型>)`（SystemUI 进程）

---

## 四、状态自检

模块加载成功后向 `Settings.System` 写入两个自检键：

| 键 | 含义 |
|---|---|
| `backtap_wallet_hook_version` | 已加载的模块版本名 |
| `backtap_wallet_hook_boot_count` | 加载时的 `Settings.Global.BOOT_COUNT` |

模块 App 通过比较这两个值与当前 `BuildConfig.VERSION_NAME` / `BOOT_COUNT`，判断：

- **已加载**：版本一致且 bootCount 一致；
- **旧版**：版本不一致 → 提示重启；
- **未加载**：bootCount 不一致或键缺失。

这条自检也是判断"Hook 是否真的生效"最直接的手段，比翻日志快。

---

## 五、投屏自动解锁

### 5.1 目标

妙享 / 互联互通投屏连接时，自动用本机凭据解锁，避免在 PC 端或手机上输入密码。全部逻辑在 **SystemUI 进程内闭环**，不依赖 `system_server`、无跨进程通信、无需额外权限。

### 5.2 进程与作用域

| 项 | 值 |
|---|---|
| 运行进程 | `com.android.systemui` |
| 注入回调 | Modern API 的 `onPackageLoaded`（**不是** `onSystemServerStarting`） |
| ClassLoader | `PackageLoadedParam#getDefaultClassLoader()` |
| 安装重试 | 每秒一次，最多 30 次（SystemUI 启动早期相关类可能尚未就绪） |

### 5.3 两条触发路径

| 路径 | 触发点 | 覆盖时序 |
|---|---|---|
| A | `KeyguardViewMediator#showKeyguard`（after 阶段） | 投屏在先 → 之后锁屏 |
| B | `DisplayManager.DisplayListener#onDisplayAdded` | 锁屏在先 → 之后连投屏 |

路径 A 在 `chain.proceed()` 之后执行，并顺带缓存 `KeyguardViewMediator` 实例供路径 B 使用；触发节流窗口 1 秒，避免锁屏动画期间被反复触发。

路径 B 在显示添加事件的 500 / 1500 / 3000 ms 三个时间点各尝试一次，每次以 `KeyguardManager#isKeyguardLocked()` 为守卫，成功后后续重试自动跳过（幂等）。

实例获取有两处来源：路径 A 缓存的实例，或从 `SystemUIApplicationImpl.mServices`（`AtomicReference<CoreStartable[]>`）按类名扫描——后者是 Android 15+ 重构后的入口（`com.android.systemui.SystemUIApplication` 已不存在）。

> 实测妙享连接时 `onDisplayAdded` 比 `showKeyguard` 晚约 1.2 秒，两条路径必须并存，缺一会漏场景。

### 5.4 线程约束

| 方法 | 约束 | 违反后果 |
|---|---|---|
| `LockPatternUtils#checkCredential` | **禁止主线程** | `IllegalStateException: should not be called from the main thread` |
| `KeyguardViewMediator#handleKeyguardDone` | **必须主线程** | `ViewRootImpl$CalledFromWrongThreadException` |

两者要求相反，因此执行形状固定为：

```
后台线程（backtap-mirror-unlock）验证凭据
    → matched 且守卫通过 → 主线程 post → handleKeyguardDone()
```

补充：`showKeyguard` 的 after 回调**不在主线程**，不可假设回调线程。

### 5.5 只有一条完整收屏路径

| 方法 | 结果 |
|---|---|
| `handleKeyguardDone()` | ✅ 正常解锁 |
| `dismiss(callback, msg)` | ❌ 调用成功、无异常，但锁屏不消失 |
| `setShowingLocked(false, ...)` | ❌ `mShowing` 变 false，但 keyguard window 仍挂屏 → 锁屏与桌面叠加、壁纸透明 |

后两者均未走 `handleHide()`、未通知 WMS、无 keyguardGoingAway 动画，禁止替换。

### 5.6 凭据构建

| 类型 | 构建方式 |
|---|---|
| 图案 | `LockPatternView$Cell.of(row, col)` 逐点构造列表 → `LockscreenCredential.createPattern(List)`；九宫格编号 1-9 映射 `row=(v-1)/3`、`col=(v-1)%3` |
| PIN / 密码 | `createPin(CharSequence)` / `createPassword(CharSequence)`，先试前者 |

`createPattern` 不做规范化（不会自动补中间点），须填写实际画出的完整序列。

`checkCredential` 的签名在不同版本间变化（`int` / `long` userId），按运行期参数类型适配；Android 15+ 返回 `VerifyCredentialResponse`（读 `isMatched()`），老版本返回 `boolean`。

`PowerManager#wakeUp(long)` 为 `@hide`，`android.jar` 中无符号，必须反射调用，否则编译失败。

### 5.7 配置来源

| 优先级 | 来源 | 键 |
|---|---|---|
| 1 | 模块 Remote Preferences（App 内配置） | `mirror_unlock_enabled` / `mirror_unlock_type` / `mirror_unlock_secret` |
| 2 | `Settings.Secure`（adb 覆盖，无需重编译） | `mirror_unlock_secret` / `mirror_unlock_type` |

两者均为空时解锁逻辑直接跳过，不做任何动作。

开关 `mirror_unlock_enabled` 在**每次执行时读取**（默认 `false`），因此关闭后立即停止解锁，无需重启；开启也不需要重启（前提是 Hook 已随 SystemUI 进程注入）。

> 原实现中的 `/sdcard/MilinkAutoUnlock.conf` 分支已移除：实测 SystemUI 受分区存储限制读不到该文件（`File.exists()` 直接为 false），属无效路径。

### 5.8 安全性

`checkCredential` 失败**不会**累加 `lockscreen.failed_attempts`（实测连续错误 3 次后计数仍为 0），因此凭据填写错误不会把设备锁死。凭据仅保存在本机 LSPosed 配置中，不上传。

### 5.9 与输入法修复的关系

两项功能互不依赖，可独立开关：输入法修复作用于 `system_server` 的 `InputMethodManagerServiceImpl`，自动解锁作用于 SystemUI 的 `KeyguardViewMediator`。关闭输入法修复开关时四条钩子全部原样透传（`chain.proceed()`），行为等同未安装。

### 5.10 解锁回执（Toast）

解锁成功后在主线程紧接 `handleKeyguardDone()` 弹出一条短 Toast「投屏已连接，自动解锁完成」，文案常量 `UNLOCK_TOAST_TEXT`。目的是让原本完全静默的动作对用户可见，便于确认功能是否真的生效。

**为什么 SystemUI 弹 Toast 不会被拦截**：Android 13+ 限制了后台应用弹 Toast（文本 Toast 要求应用处于前台，或持有 `SYSTEM_ALERT_WINDOW`）。SystemUI 满足全部豁免条件，实测：

| 项 | 实测值 |
|---|---|
| sharedUserId | `android.uid.system`（uid **1000**） |
| `SYSTEM_ALERT_WINDOW` | `granted=true` |
| `POST_NOTIFICATIONS` | `granted=true`（`SYSTEM_FIXED`） |

uid 1000 属系统 uid，`NotificationManagerService` 会按系统 Toast 放行。因此**不需要**改用 `WindowManager` 浮层。

约束：`Toast.show()` 必须在主线程调用，调用点已位于 `new Handler(Looper.getMainLooper()).post(...)` 内；`Toast` 失败只记 `WARN` 日志，绝不影响解锁本身（已整体 `try/catch`）。
