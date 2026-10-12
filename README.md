# BackTap Wallet

> 澎湃 OS（HyperOS）**背部轻敲唤起小米钱包 NFC 刷卡界面**的 LSPosed 模块，并附带**两项投屏增强：输入法压制修复与自动解锁**。

本仓库 fork 自 [13112442872/HyperOS-BackTap-Pay](https://github.com/13112442872/HyperOS-BackTap-Pay)，在原作者"背部轻敲唤起支付宝付款码"的基础上扩展了三项能力：把**小米钱包 NFC 刷卡页**接入背部轻敲手势、修复**妙享 / 互融协同下 PC 端输入时手机软键盘不弹出**，以及**投屏连接时自动解锁**。后两项均可在模块内独立开关。

---

## 功能

| 功能 | 说明 |
|---|---|
| **小米钱包 NFC 刷卡** | 背部双击 / 三击直接唤起小米智能卡刷卡页，与双击电源键行为一致，跳过卡面选择直接进入待刷状态 |
| 支付宝付款码 | 保留原仓库能力，可选主屏 / 背屏显示 |
| 支付宝乘车码 | 同上 |
| **投屏输入法修复** | 修复妙享 / 互融协同下 PC 控制端输入时手机侧软键盘被压制、永不弹出的问题（**可开关**，默认开） |
| **投屏自动解锁** | 投屏连接后自动用本机凭据解锁，无需在 PC 端或手机上输入密码；解锁成功后手机会弹出一条 Toast 回执（**可开关**，默认关） |
| 触发 Tips | 可选。敲击后在主屏提示击打次数与实际执行结果 |
| 状态自检 | 模块页直接显示 Hook 是否已加载、是否需要在 `system_server` 中更新 |

背部双击与三击**可独立配置**为：关闭 / 小米钱包刷卡 / 支付宝付款码 / 支付宝乘车码。

---

## 环境要求

| 项 | 要求 |
|---|---|
| 系统 | 澎湃 OS（HyperOS），Android 14+。当前版本在 **Android 17 / SDK 37 / HyperOS 4.0.0.14.XNCCNXM** 完成真机验证 |
| 框架 | LSPosed 2.x（需支持 Modern Xposed API 102）。已在 LSPosed 2.2.1 验证 |
| Root | 需要（LSPosed 自身依赖；KernelSU / Magisk 均可） |
| 目标应用 | 小米智能卡 `com.miui.tsmclient`（系统内置，无需额外安装） |
| 作用域 | **系统框架**（背部轻敲、输入法修复）+ **SystemUI**（仅「投屏自动解锁」需要） |

> 模块本身不申请任何额外 Root 权限。背部轻敲与输入法修复运行于 `system_server`；投屏自动解锁运行于 SystemUI 进程内闭环，不依赖 `system_server`、无跨进程通信。

---

## 安装

1. 下载最新 Release 中的 `BackTapWallet-vX.Y.Z-release.apk` 并安装：

   ```bash
   adb install -r BackTapWallet-v0.4.7-release.apk
   ```

2. 打开 **LSPosed** → 模块 → 启用 **BackTap Wallet**。
3. **勾选作用域**（模块为静态作用域，声明在 `scope.list` 中）：
   - 必选：**系统框架 / System Framework** —— 背部轻敲、投屏输入法修复
   - 可选：**SystemUI / 系统界面** —— 仅在使用「投屏自动解锁」时需要
4. 重启手机（`system_server` 与 SystemUI 需重新加载模块）。
5. 打开 BackTap Wallet，确认首页显示 **● Modern Hook 已加载**。

---

## 配置

1. 在模块首页选择「背部双击」/「背部三击」对应的功能，例如 **小米钱包刷卡（NFC）**。
2. 系统设置中的背部轻敲动作**无需手动修改**——模块会自动把该手势的系统绑定同步为支付宝付款码作为**占位值**，保证手势开关处于启用状态。

   > 占位机制是本模块兼容性的基础：系统设置里永远只保存合法值，真实动作由 Hook 在敲击发生时判定，因此不存在"系统把非法值擦掉"导致失效的问题。

3. 「显示位置（仅支付宝）」在功能选为钱包时自动置灰——钱包刷卡页固定为全屏原生界面。

### 投屏相关开关

| 开关 | 默认 | 生效方式 |
|---|---|---|
| 投屏输入法修复 | **开** | 即时生效，无需重启（关闭后 Hook 原样透传，行为与未安装一致） |
| 投屏自动解锁 | **关** | 开关即时生效；但首次使用需先勾选 SystemUI 作用域并重启一次，Hook 才会注入该进程 |

「投屏自动解锁」需配置凭据：选择类型（图案 / PIN / 密码）后填写，点「保存凭据」。

- **图案**：填九宫格编号串（左上为 1），例如 `4756`。不自动补中间点，须按实际画出的顺序填写。
- **PIN / 密码**：直接填明文。

凭据保存在本机 LSPosed Remote Preferences 中，不会上传。也可用 adb 覆盖（优先级低于模块内配置）：

```bash
adb shell su -c "settings put secure mirror_unlock_secret 4756"
adb shell su -c "settings put secure mirror_unlock_type pattern"   # pattern | pin | password
```

> 凭据错误**不会**累加 `lockscreen.failed_attempts`，不会因为填错把设备锁死。

---

## 验证

### 状态自检（最快）

模块匹配成功后会向 `Settings.System` 写入三个键：

```bash
adb shell settings get system backtap_wallet_hook_version    # 应为 0.4.7
adb shell settings get system backtap_wallet_hook_boot_count # 应等于当前 BOOT_COUNT
adb shell settings get system backtap_wallet_ime_fix_state   # on / off，修复的实际生效值
adb shell settings get global boot_count
```

版本名与 `BOOT_COUNT` 均一致 → Hook 已在当前 `system_server` 中生效。若版本名不一致，说明 `system_server` 仍在运行旧模块，重启即可。

`backtap_wallet_ime_fix_state` 是**判定输入法开关是否真的生效**的唯一可靠依据（App 侧 RemotePreferences 快照在落库失败时同样会变，不可信）。模块首页在该卡片下也会显示同样的回执。

### 背部轻敲

```bash
adb shell settings get system back_double_tap   # 占位值为 launch_alipay_payment_code
adb shell settings get system back_triple_tap
```

双击 / 三击背部，应立即唤起小米钱包刷卡页。选择打开「触发 Tips」时，主屏会给出提示。

### 投屏输入法

PC 控制端连接后，在手机 App 输入框内点击，软键盘应正常弹出；且 `logcat` 中不再出现：

```
ImeTracker: <pkg>: onCancelled at PHASE_CLIENT_REPORT_REQUESTED_VISIBLE_TYPES
```

### 模块日志

Modern API 模块的日志**不写入 `logcat`**，而在 LSPosed 自己的日志文件中：

```bash
adb shell su -c 'ls -t /data/adb/lspd/log/ | head'
adb shell su -c 'grep -a BackTapWallet /data/adb/lspd/log/modules_<时间戳>.log | tail -40'
```

加载正常时应包含：

```
Modern module loaded in system, systemServer=true, API=102
Modern hook ready, installed 2 triggerFunction hook(s), 4 mirror input hook(s)
Mirror input hooks installed: 4 [write:-$$Nest$fputmMirrorInputState,
    readfalse:getMirrorInputState, initafter:init, readfalse:synergyOperate]
Published Modern Hook status version=0.4.7, bootCount=...
```

> `mirror input hook(s)` 必须为 **4**。若为 0 或 2，说明该 ROM 的类结构与预期不符，请附日志提 Issue。

### 投屏自动解锁

SystemUI 进程的日志同在一个文件里，前缀为 `unlock:`：

```bash
adb shell su -c 'grep -a "Auto-unlock\|unlock:" /data/adb/lspd/log/modules_*.log | tail -30'
```

正常工作时应包含：

```
SystemUI loaded, installing mirror auto-unlock hooks
Auto-unlock hook ready, showKeyguard hooked x1
display listener registered
showKeyguard: casting=true displays=[内置屏幕#0, screen-mirror-ScreenRecorder#13]
showKeyguard: checkCredential userId=0 -> ... matched=true
showKeyguard: handleKeyguardDone OK
```

解锁成功后会在手机屏幕上弹出一条 Toast「**投屏已连接，自动解锁完成**」，用于确认功能确实执行。Toast 由 SystemUI 进程发出（其 uid 为 `android.uid.system`，属系统 uid，不受 Android 13+ 后台 Toast 限制）。

未投屏时应为 `casting=false` 后直接返回——这是防误判设计，不是故障。判定结果：

```bash
adb shell dumpsys window | grep isKeyguardShowing              # 期望 false
adb shell dumpsys display | grep -o "screen-mirror[^ ]*"        # 确认投屏在
```

> 若日志里始终没有 `Auto-unlock hook ready`，通常是 LSPosed 作用域未勾选 **SystemUI**，或勾选后未重启。

---

## 实现原理

### 背部轻敲 → 小米钱包

系统只认有限的几个合法动作（`none`、`launch_alipay_payment_code` 等），**没有「小米钱包」这个选项**，因此不能沿用"改写动作字符串后放行"的做法，必须改为**拦截后自行启动**：

1. Hook `com.miui.server.input.util.ShortCutActionsUtils#triggerFunction(...)` 的全部重载；
2. 参数中同时携带功能字符串与手势来源字符串（`back_double_tap` / `back_triple_tap`），据此只在**背部轻敲**时介入，不影响指纹快支付等其他入口；
3. 若该手势被配置为钱包，则不调用原方法，用 system Context 启动：

   ```
   com.miui.tsmclient / com.miui.tsmclient.ui.quick.DoubleClickActivity
   extra: event_source = key_volume_down
   ```

   `DoubleClickActivity` 通常未导出，普通应用无法启动；模块运行在 `system_server` 内、持有 system 权限，可直接启动。

### 投屏输入法修复

`InputMethodManagerServiceImpl.mMirrorInputState` 在投屏协同态被写成 `true`，使 `getMirrorInputState()` / `synergyOperate()` 恒为真，系统据此压制软键盘。模块对该字段的全部读写点施加 4 条规则：

| 规则 | 目标 | 开关**开** | 开关**关** |
|---|---|---|---|
| `write` | `-$$Nest$fputmMirrorInputState` | 记录接管前原值，再把写入值改成 `false` | 原样透传，不改写入值 |
| `readfalse` | `getMirrorInputState()` | 返回 `false` | 原样透传 |
| `readfalse` | `synergyOperate()` | 返回 `false` | 原样透传 |
| `initafter` | `init(...)` | after 阶段把字段摁回 `false` | 不动字段 |

> **关键约束：本模块永不写入 `true`。** `true` 是「投屏协同中」的语义，一旦在非投屏场景写入，`showSoftInput` 请求会在客户端就被取消，表现是**日常点输入框也弹不出键盘**。因此开关关闭时只做两件事：把字段还原成**接管前快照**，并让四条钩子全部透传——不做任何反向压制。
>
> 与之相对，早期实现在开关关闭时会把字段强行写成 `true`（"反向压制"），结果日常场景键盘完全失效，已废弃。

> 读点只覆盖返回值、**不写字段**。若在读点反射摁字段，等于把状态钉死：关闭开关后系统不主动回写，字段仍是 `false`，读点放行后依旧返回 `false`，表现就是「开关关了、行为却没变」。

> 注意：类中**不存在**名为 `putState` 的方法。`-$$Nest$fputmMirrorInputState` 是编译器生成的静态合成写访问器，按 `putState` 匹配会静默失败。

#### 开关是否真的生效

LSPosed RemotePreferences 是**进程内快照**（`RemotePreferences.mMap`），而 `commit()` 会先改本地快照、再落库——落库失败时 App 仍会显示新值。因此模块把**真正生效**的闸门值回写到 `Settings.System`：

```bash
adb shell settings get system backtap_wallet_ime_fix_state   # on / off
```

模块首页「投屏输入法修复」标题下同时显示回执：**已同步**为绿色，**未下发**为橙色且可点击重试。与之对应的 LSPosed 日志：

```text
Remote Preferences connected, snapshot={... mirror_ime_fix_enabled=true}, imeFix=true
IME fix gate -> ON|OFF
IME fix preference pushed by app -> ON|OFF
IME fix OFF, restored mMirrorInputState=...
```

### 投屏自动解锁

逻辑在 **SystemUI 进程内闭环**，不经过 `system_server`：

```
触发 → 判投屏（存在 screen-mirror-* 虚拟显示） → LockPatternUtils.checkCredential(凭据)
     → VerifyCredentialResponse.isMatched() → KeyguardViewMediator.handleKeyguardDone()
```

两条触发路径并存，缺一会漏场景：

| 路径 | 触发点 | 覆盖时序 |
|---|---|---|
| A | `KeyguardViewMediator#showKeyguard` | 投屏在先 → 之后锁屏 |
| B | `DisplayManager.DisplayListener#onDisplayAdded` | 锁屏在先 → 之后连投屏 |

实测妙享连接时 `onDisplayAdded` 比 `showKeyguard` 晚约 1.2 秒，两条都必须保留。路径 B 的解锁带 `isKeyguardLocked()` 守卫，成功后后续重试自动跳过（幂等）。

两个线程约束必须维持（勿"简化"）：

- `LockPatternUtils.checkCredential` **禁止主线程**（`IllegalStateException: should not be called from the main thread`）
- `KeyguardViewMediator.handleKeyguardDone` **必须主线程**（内部触碰 View）

因此固定形状为：**后台线程验证 → 通过后 `Handler(Looper.getMainLooper()).post()` 回主线程收屏**。另需注意只有 `handleKeyguardDone()` 是完整收屏路径，`dismiss()` / `setShowingLocked(false)` 会留下锁屏与桌面叠加的残影。

完整的 DEX 证据、方法签名与 API 细节见 **[docs/TECHNICAL.md](docs/TECHNICAL.md)**。

---

## 构建

### 本地构建

```bash
# 需要 JDK 17 + Android SDK（platforms/android-37）
./gradlew :app:assembleRelease
```

本工程依赖 `io.github.libxposed:service:102.0.0`，其 AAR 元数据要求 `compileSdk >= 37`。

若 `sdkmanager` 无法安装 API 37（网络原因），可手动下载平台包：

```bash
curl -L -o platform-37.zip https://dl.google.com/android/repository/platform-37.0_r02.zip
unzip platform-37.zip -d "$ANDROID_HOME/platforms"
mv "$ANDROID_HOME/platforms/android-37.0" "$ANDROID_HOME/platforms/android-37"
```

### CI 自动构建

推送至 `main` 或手动触发 `Build Release APK` workflow，自动完成构建并发布 Release（含 APK 附件）。发布版本号取自 `app/build.gradle` 中的 `versionName`。

---

## 常见问题

**Q：敲背没有反应，或者唤起的还是付款码？**

按顺序排查：① 模块是否已启用且作用域勾选**系统框架**；② 是否重启过手机；③ 模块首页是否显示「● Modern Hook 已加载」；④ `adb shell settings get system back_double_tap` 是否为 `launch_alipay_payment_code`（若非此值，重新在模块里选择一次功能即可恢复绑定）。

**Q：LSPosed 日志里没有 `logcat` 记录？**

Modern API 模块日志不走 `logcat`，请查看 `/data/adb/lspd/log/modules_<时间戳>.log`。

**Q：钱包刷卡页打不开，日志提示启动失败？**

不同 HyperOS 版本上刷卡页的 Activity 名可能变化。请确认当前版本中双击电源键唤出的界面：

```bash
adb shell dumpsys activity top | grep ACTIVITY
```

若与 `com.miui.tsmclient.ui.quick.DoubleClickActivity` 不同，请提 Issue 并附上实际组件名。

**Q：投屏自动解锁没生效？**

按顺序排查：① LSPosed 作用域是否勾选了 **SystemUI**；② 勾选后是否**重启过手机**（只软重启不够，SystemUI 需重新加载）；③ 「投屏自动解锁」开关是否已打开；④ 是否已填写并保存凭据；⑤ 查日志中是否有 `Auto-unlock hook ready` 与 `casting=true`。

**Q：锁屏状态下敲背需要指纹验证吗？**

与双击电源键的行为一致。若设备开启了强加密验证，仍需解锁后刷卡，属预期表现。

---

## 已知限制

- 仅适配小米 / 红米机型（依赖 MIUI `ShortCutActionsUtils` 与 `com.miui.tsmclient`）。
- 投屏输入法修复依赖 `InputMethodManagerServiceImpl` 的类结构，个别 ROM 上可能只有部分规则生效。
- 投屏自动解锁依赖 `KeyguardViewMediator` 与 LSPosed 对 SystemUI 的注入；若 ROM 改动相关字段名，日志会出现 `KeyguardViewMediator not found`，该功能自动跳过，不影响其他能力。
- 背屏显示能力继承自上游，仅适用于带背屏的机型。

---

## 致谢

- [13112442872/HyperOS-BackTap-Pay](https://github.com/13112442872/HyperOS-BackTap-Pay)：背部轻敲触发与支付宝动作改写方案的原始实现。
- [LSPosed](https://github.com/LSPosed/LSPosed)：Modern Xposed API 与 Remote Preferences 运行时。

## 免责声明

本模块仅用于个人设备的自动化便利性改造。请自行评估刷机与 Root 风险，因使用本模块导致的设备异常由使用者自行承担。
