# 两步路去广告（TbuluAdFree）

> 模块版本：**v1.12**（git tag `v1.12`；模块包名 `com.sumver.tbulu`；版本号对应 `app/build.gradle` 的 versionName）

针对 **两步路 App**（`com.lolaage.tbulu.tools`，目标版本 **9.2.8 / versionCode 1652**）的 LSPosed / EdXposed 模块。

只做八件事：

1. **隐藏广告位**：开屏广告、首页信息流广告、多页面轮播横幅广告、资讯列表广告、运动记录页广告、出游详情顶部广告、主界面插屏广告、弹窗广告。
2. **隐藏"我的"页会员促销横幅**（`rlVip`：开通 / 续费会员的横条），并**压缩横幅遗留的空档**：头部块 252dp→202dp、数据面板底距 60dp→10dp，使"个人信息"与下方宫格（运动团/福利抽奖/Top100）自然衔接。
3. **隐藏"我的"页顶部 VIP 客服按钮**（`btnCustomerService`，点击会弹出"专属客服"促销弹层：稍后再说 / 立即开通）。
4. **屏蔽底部 dock 栏图标红点**（"我的"图标上的未读数红点等，保持 dock 栏纯净）。
5. **装备 tab 防误触**：点击底部 dock 的"装备"（我的左边）不再直接跳商城，先弹窗确认"是否打开商城？"，点"否"保持原样，点"是"再跳转。
6. **屏蔽升级弹窗**：App 检测到新版本后不再弹窗（版本详情弹窗与"我的"页更新提示弹窗均屏蔽）；自动检测逻辑本身不变。
7. **解锁五项会员权益的本地门控**（v1.6 新增）：图片水印、DIY首页（把地图设为启动界面）、头像挂件、记录/导航皮肤、个性化分享（高级数据分享图）。仅解除客户端的"需要开通会员"拦截，不伪造账号 VIP 状态、不拦截网络请求、不影响购买流程；皮肤/挂件/分享图等素材与字段仍由服务端下发。
8. **设置界面（v1.8 新增，Material 3；v1.10 起改为重启生效）**：以上全部功能（含五项会员解锁）在模块 App 中按分类提供开关，默认全部开启；修改后需强制停止并重新打开两步路才生效。

不伪造 VIP 状态、不修改购买流程、不拦截业务请求；开屏广告走 App 自带的"跳过/无广告"路径，行为与真实无填充一致；dock 红点只拦截"显示"链路，未读消息数据本身不受影响。第 7 项采用线程级作用域放行：只在对应功能的"交互入口"调用期间让客户端会员判断临时为真，账号数据与网络请求完全保持原样。

## 已确认的 Hook 点（v9.2.8）

| 广告位 | 类 / 方法 | 处理 |
|---|---|---|
| 开屏广告 | `ui.fragment.WelcomeNormalFragment#Oooo0O0`（三方）、`#Oooo0`（自家 Pangle） | 直接走 `OooOoo()` 跳过路径 |
| 开屏广告(兜底) | `business.managers.AdConfigManager#isNeedShowSplashAdThisTime` | 返回 false |
| 首页信息流广告 | `list.multitype.HomePageAdvertGeneral#OooO0Oo`（显示类型=29） | 跳过并隐藏条目 + 高度 0 |
| 首页信息流广告(兜底) | `ui.views.homepage.HomePageAdvertItemView#setData` | 跳过并隐藏 |
| 轮播横幅广告 | `ui.views.adv.AdvScrollView#setDatas` 及 `setVisibility` | 跳过并隐藏 |
| 资讯列表广告 | `ui.views.horizontal.NewsListADView#setDatas` 及 `setVisibility` | 跳过并隐藏 |
| 列表广告(TopOn) | `ui.views.adv.list.TakuListAdView#OooO0O0` | 跳过加载并隐藏 |
| 运动记录页广告 | `ui.views.SportRecordAdDataView#OooO0O0`、`ui.activity.sport.SportRecordAdView#setData` | 跳过并隐藏 |
| 出游详情顶部广告 | `ui.views.adv.OutingTopImageAdView#setData` | 跳过并隐藏 |
| 弹窗广告 | `ui.dialog.AdvDialog#show` | 不弹 |
| 主界面插屏广告 | `ui.views.adv.splash.TakuInterstitialAdView#Oooo000` | 返回 false（未展示） |
| 通用守卫 | `ViewGroup#addView` / `View#setVisibility`（按广告视图类名 + 容器 id） | 广告视图一律 GONE + 高度 0 |

| VIP 促销横幅 | 类 / 方法 | 处理 |
|---|---|---|
| "我的"页 `rlVip` | `ui.fragment.main.o0000#o000OOo0 / #OooOo0 / #onResume / #onHiddenChanged` | 视图置 GONE |
| 横幅遗留空档压缩 | 同上（`account_part` 252dp→202dp、`vUserData` 底距 60dp→10dp，dimens 运行时解析） | 消除空档，宫格上移 50dp |
| "我的"页顶部 VIP 客服按钮 `btnCustomerService`（图标 ic_vip_4，点击弹"专属客服"弹层：稍后再说/立即开通） | 同上（统一在 `VipBannerHook` 的多层隐藏清单中） | 视图置 GONE |
| 兜底 | `View#setVisibility`（按运行时解析的目标 id） | 强制 GONE |

| dock 栏红点 | 类 / 方法 | 处理 |
|---|---|---|
| 红点渲染回调 | `ui.widget.maintab.SpaceTabView#OooOO0O(IIIZ)` | visible 强制 false（走自带隐藏分支） |
| 红点状态记录 | `ui.widget.maintab.TabGuideDelegate#OooOOoo(III)` | 跳过（不记录不触发） |
| 库层兜底 | `com.luseen.spacenavigation.SpaceNavigationView#Oooo0oo(III)` | 跳过渲染 |

> "我的" tab 索引为 4：`MainActivity.o0000()` 用 `通知数 + 聊天未读数` 更新红点；本模块只拦显示链路，不碰未读数据。

| 装备 tab 防误触 | 类 / 方法 | 处理 |
|---|---|---|
| 装备 tab 点击分发 | `ui.widget.maintab.ITabView$OooO00o#OooO0OO(I)`（tab==3 → 有赞商城） | 首次点击消费并弹"是否打开商城？"确认框；点"是"带放行标记再调原方法跳转，点"否"/取消保持原样 |

| 升级弹窗屏蔽 | 类 / 方法 | 处理 |
|---|---|---|
| 版本详情弹窗（UpgradeInfoDialog）唯一展示入口 | `upgrade.OooO0o#OooOOOo(BaseActivity)`（启动缓存路径、网络回调路径、手动检查均汇聚于此） | 跳过，不弹 |
| "我的"页更新提示弹窗（UpdateTipsDialog）唯一入口 | `ui.fragment.main.o0000#o0000oo0()`（自动触发与点击触发都走这里） | 跳过，不弹 |

| 会员本地门控解锁（v1.6 起） | 类 / 方法 | 处理 |
|---|---|---|
| 会员判断单例 | `o00OOOOo.OooO0O0#OooOooO()`（= OooOoOO() \|\| OooOoo0()，VIP/SVIP）、`#OooOoo0()`（SVIP） | **线程作用域内**临时返回 true（账号状态、网络请求不动） |
| 图片水印 | `ui.activity.RecordPictureOrVideoActivity#o0000O()`（`SpUtils.Oooo0o()>0 \|\| !开关 \|\| 会员` 否则 `showNeedVip`） | 作用域包裹：拍照水印不再弹"需要会员" |
| DIY首页 · 入口 | `ui.activity.settings.MoreSettingsActivity#onClick`（case `lyStartDefaultView` 需会员） | 作用域包裹：可进入"启动后默认界面"设置页 |
| DIY首页 · 生效 | `ui.activity.main.MainActivity#onFirstResume`（`会员 && KEY_START_DEFAULT_VIEW==2` 才跳地图） | 作用域包裹：把地图设为启动界面后正常自动跳转 |
| 个性化形象 · 挂件选择 | `ui.activity.SetPendantActivity#Oooo0O0(I)`（1=VIP/2=SVIP 权限判断，仅本类内部使用） | 直接放行（返回 true） |
| 个性化形象 · 挂件 item 点击（v1.7） | `ui.activity.PendantListFragment$mAdapter$2$1$convert$1$1#invoke(View)`（锁定挂件点击即弹 `VipGuideDialog`，文案"随心定制界面，解锁个人风格"） | 作用域包裹：点击不再弹开通窗，直接选中 |
| 个性化形象 · 立即装扮 | `ui.activity.SetPendantActivity#onClick`（`selectPendantPicType` 1/2 时校验会员，否则 `VipGuideDialog`） | 作用域包裹：直接进入装扮保存流程（保存走 `UserAPI.updateUserInfo`，服务端行为不变） |
| 专属样式 · 皮肤可用性 | `domain.SkinInfo#isCanUse()`（`vip→需VIP、svip→需SVIP`，皮肤列表/点选所有判断的唯一出口） | 直接放行（返回 true） |
| 专属样式 · 记录页入口 | `ui.dialog.TrackSetUpDialog#onClick`（"记录/导航时皮肤"需会员，否则 `showNeedVip`） | 作用域包裹：可进入皮肤设置页 |
| 专属样式 · 桌面图标点击（v1.7） | `ChangeAppLogoActivity$mAdapter$2$OooO00o#OooOo00(...)`（图标自带分档 type：1=VIP / 3=SVIP，点击时经 `ChangeAppLogoActivity#OooOoo(item)` 校验，失败弹 `VipGuideDialog`） | 作用域包裹：点击图标不再弹开通窗，直接走 alias 切换应用分支 |
| 个性化分享 · 保存 | `ui.views.SportShareAdvancedDataView#OooOOo0()`（保存带高级数据的分享图前校验 VIP/SVIP，否则弹 `RecordShareVipGuideDialog`） | 作用域包裹：直接保存 |
| 个性化分享 · 横幅 | `ui.views.SportShareAdvancedDataView#OooOo00()`（非会员显示"加入两步路VIP/SVIP会员"横幅） | 作用域包裹：横幅按已解锁逻辑隐藏 |

> 补充说明（v1.7）：
> - 桌面图标**页面的入口**本身免费，但每个图标 item 自带 VIP/SVIP 分档（type 1/3），点击时才校验并可能弹开通窗；v1.7 起与挂件 item 点击一并放行。
> - 记录页底部弹窗 `RecordAndNavSetUpBottomSheet` 打开皮肤页无 VIP 校验（免费入口），未做处理；
> - 会员权益页/购买页是 H5（`www.2bulu.com/mc/vip/...`，Vue SPA，路由 `/vip/rights/v9` 与 `/vip/v9/rights`），不在模块范围内。

> 所有类名/方法名均与 9.2.8 的混淆结果绑定；App 升级后若失效，模块会自动跳过（仅记录日志），不影响 App 运行。

## 设置界面与开关（v1.8）

模块 App（图标：蓝色盾牌）启动即为设置界面，Material 3（API 31+ 自动应用动态取色），结构：

- 「广告净化」：隐藏各类广告
- 「我的」页净化：会员促销横幅、VIP 客服按钮、dock 栏红点、升级弹窗
- 交互优化：装备（商城）tab 防误触
- 会员权益本地解锁：图片水印、DIY首页、头像挂件、专属样式、个性化分享
- 「使用说明」卡片（含可点击的开源地址 `https://github.com/sumver/hooktbulu`，Linkify 转链）

**开关 ↔ 功能 ↔ 偏好 key 对照：**

| 开关 | 偏好 key（`settings.xml`） | Hook |
|---|---|---|
| 隐藏各类广告 | `ads` | `AdsHook` 全部拦截点 |
| 隐藏会员促销横幅 | `vip_banner` | `VipBannerHook`（`rlVip` + 空档压缩） |
| 隐藏 VIP 客服按钮 | `vip_service` | `VipBannerHook`（`btnCustomerService`） |
| 屏蔽 dock 栏红点 | `dock_badge` | `DockBadgeHook` |
| 屏蔽升级弹窗 | `upgrade_dialog` | `UpgradeDialogHook` |
| 装备 tab 防误触 | `equipment_confirm` | `EquipmentTabHook` |
| 图片水印 | `vip_watermark` | `VipGateUnlockHook`（水印入口） |
| DIY首页 | `vip_diy_home` | `VipGateUnlockHook`（入口 + 地图启动生效） |
| 头像挂件 | `vip_pendant` | `VipGateUnlockHook`（item 点击 + 立即装扮 + 选择权限） |
| 专属样式 | `vip_style` | `VipGateUnlockHook`（皮肤可用性/入口 + 桌面图标点击） |
| 个性化分享 | `vip_share` | `VipGateUnlockHook`（保存 + 横幅） |

**实现机制**（模块进程 ↔ 两步路进程，遵循 LSPosed 官方「New XSharedPreferences」协议）：

- **模块 Manifest 声明 `<meta-data android:name="xposedsharedprefs" android:value="true"/>`**（v1.11 补上，关键）——
  LSPosed 1.9.2 源码 `de.robv.android.xposed.XSharedPreferences` 据此把读取路径切到
  `serviceClient.getPrefsPath(packageName)` 的 **LSPosed 代理目录**；
  缺此声明（且 xposedminversion ≤ 92）时只会去读 `/data/data/<模块>/shared_prefs/`，跨应用不可读，开关永远不生效；
- 设置界面：用 `Context.MODE_WORLD_READABLE` 打开 `SharedPreferences("settings")`（`Prefs.ui()`）——
  LSPosed 拦截该模式并把文件读写重定向到代理目录；非 LSPosed 环境会抛 `SecurityException`，回退 `MODE_PRIVATE`。
  首次打开写入全部默认值 `true`；
- Hook 进程：`XSharedPreferences(com.sumver.tbulu, "settings")` 只读代理目录中的同一份文件，
  使用前检查 `getFile().canRead()`；并在 `XposedInit.handleLoadPackage` 启动时预读（`Prefs.prime()`）；
- 读取策略：**每个进程启动后只读一次并缓存**（`Prefs.isEnabled()`）——
  开关修改后必须强制停止并重新打开两步路才生效（刻意设计，不做运行中实时生效，避免状态错乱）；
- 默认策略：未配置 / 读取失败 / 从未打开设置界面 → 全部按 `true`（即 v1.7 的原行为），并输出明确错误日志；
- 作用域放行采用线程级深度计数 + 决策栈（before 入栈、after 出栈），保证配对不出错。

> 注：若日志出现 `Prefs: settings unreadable`，说明 Hook 侧仍没读到代理目录：
> 先打开一次模块设置界面（触发代理目录写入），再强制停止两步路后重新打开；
> 并确认模块 APK 里带有 `xposedsharedprefs` 元数据（v1.11 起已内置）。
>
> 日志：设置界面进程写入 `android.util.Log`（tag `TbuluAdFree`，见 logcat）；
> Hook（两步路）进程仍走 `XposedBridge.log`（LSPosed 日志）。两边故意分开——
> 模块 App 进程没有 Xposed API 类（`compileOnly`），引用 `XposedInit` 会导致类加载失败。

## 构建

需要 Android SDK（`local.properties` 中已指向本机 SDK）与 JDK 17+；设置界面依赖 Material Components（`com.google.android.material:material`，因此 `gradle.properties` 需 `android.useAndroidX=true`）。

```bat
:: 在项目根目录（含 settings.gradle 的目录）执行：
gradle assembleDebug      :: debug 包
gradle assembleRelease    :: 正式发布包（release）
```

产物：
- debug：`app\build\outputs\apk\debug\app-debug.apk`
- release：`app\build\outputs\apk\release\app-release.apk`（已构建）

## 安装与激活（LSPosed）

```bat
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

1. LSPosed 管理器中启用"两步路去广告"；
2. **作用域只勾选** `两步路`（com.lolaage.tbulu.tools）；
3. 强制停止两步路后重新打开；
4. 需要调整功能时，打开模块 App（蓝色盾牌图标）在设置界面里开关即可，改动最长约 1 秒后生效。

## 验证

- LSPosed 日志 / logcat 过滤 `TbuluAdFree`，应看到：
  - `loaded target: com.lolaage.tbulu.tools process=...`
  - `hooked ...` 系列
  - `splash ad skipped`
  - `rlVip hidden by fragment hook` / `btnCustomerService hidden by fragment hook` / 解析出的 ids
  - 点击装备 tab：`equipment confirm: YES` 或 `equipment confirm: NO (stay)`
  - v1.6：`VipGateUnlockHook: member-check singleton hooked (o00OOOOo.OooO0O0)` 及 `scoped/hooked ...` 系列；触发对应功能时出现（每个 key 仅首次）
    `bypass OooOooO -> true (scoped)` / `bypass SetPendantActivity.Oooo0O0 -> true` / `bypass SkinInfo.isCanUse -> true`
  - v1.7：`scoped PendantListFragment$mAdapter$2$1$convert$1$1.invoke (pendant item click)`、`scoped ChangeAppLogoActivity$mAdapter$2$OooO00o.OooOo00 (app logo item click)`
  - v1.8+：打开模块 App（蓝色盾牌）可见 Material 3 设置界面；切换任一开关，模块进程 logcat 出现 `settings: <key> -> <true|false>`；
    强制停止并重开两步路后生效：Hook 进程日志应出现 `Prefs: loaded N switches (read once per process; restart target app to apply)`；
    若出现 `Prefs: settings unreadable ...` 则是偏好文件权限未就绪（按提示先切一次开关再重启）；
- 界面验证：
  - 冷启动无开屏广告，直接进主界面；
  - "我的"页顶部无会员促销横幅；
  - 首页信息流 / 资讯列表 / 运动记录页 / 出游详情页无广告条目；
  - v1.6/v1.7（非会员账号）：
    - 记录页拍照：图片水印开关打开后拍照直接带水印，不再弹"需要开通会员"；
    - "更多设置 → 启动后默认界面"：可进入设置页；选"地图"后重进 App 自动跳地图页；
    - 头像挂件页：点击任意挂件不再弹开通窗（v1.7），"立即装扮"直接生效；
    - "更多设置 → 记录/导航时皮肤"或记录页设置弹窗：可进入皮肤页并应用任意皮肤；
    - "更多设置 → 变更手机桌面两步路图标"：点击任意图标不再弹开通窗（v1.7），图标直接切换；
    - 运动记录分享页：可保存带高级数据的分享图，且不显示"加入两步路VIP/SVIP会员"横幅；
  - v1.8 开关联动（任选两项验证）：
    - 模块界面关闭「隐藏会员促销横幅」→ 重进"我的"页，横幅恢复显示（位置与未装模块一致）；
    - 模块界面开关「头像挂件」→ 打开挂件页点挂件：关闭时恢复开通弹窗，开启时直接选中。

## 已知限制

- 广告 SDK（TopOn / 穿山甲 / 广点通）的初始化与预加载请求仍会发生（只隐藏展示与跳过展示入口）；个别预加载在后台进行，不影响界面。
- 网页(H5)内嵌的广告/会员横幅不在本模块范围内。
- 未处理"看广告换权益"类功能（本版本未见）。
- v1.6/v1.7 会员门控解锁只针对**客户端本地判断**：皮肤清单、挂件清单、分享图背景/水印字段等仍由服务端下发，本模块不制造服务端数据；挂件保存走 `UserAPI.updateUserInfo` 提交，最终展示以服务端为准（非会员账号若被服务端另有约束，本模块不处理）。
- v1.6/v1.7 不修改账号 VIP 状态：头像/昵称的会员标识、会员权益介绍页（H5）等仍按真实账号状态展示。
- v1.8 起全部功能均有独立开关（模块设置界面，默认开启）；v1.10 起改为**重启生效**：修改开关后需强制停止并重新打开两步路（运行中不实时应用）。XSharedPreferences 读取失败时按"全部开启"回退（即行为与 v1.7 一致），并输出错误日志。
