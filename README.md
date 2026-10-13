# 两步路去广告（TbuluAdFree）

针对 **两步路 App**（`com.lolaage.tbulu.tools`，目标版本 **9.2.8 / versionCode 1652**）的 LSPosed / EdXposed 模块。

只做五件事：

1. **隐藏广告位**：开屏广告、首页信息流广告、多页面轮播横幅广告、资讯列表广告、运动记录页广告、出游详情顶部广告、主界面插屏广告、弹窗广告。
2. **隐藏"我的"页会员促销横幅**（`rlVip`：开通 / 续费会员的横条）。
3. **隐藏"我的"页顶部 VIP 客服按钮**（`btnCustomerService`，点击会弹出"专属客服"促销弹层：稍后再说 / 立即开通）。
4. **屏蔽底部 dock 栏图标红点**（"我的"图标上的未读数红点等，保持 dock 栏纯净）。
5. **装备 tab 防误触**：点击底部 dock 的"装备"（我的左边）不再直接跳商城，先弹窗确认"是否打开商城？"，点"否"保持原样，点"是"再跳转。

不伪造 VIP 状态、不修改购买流程、不拦截业务请求；开屏广告走 App 自带的"跳过/无广告"路径，行为与真实无填充一致；dock 红点只拦截"显示"链路，未读消息数据本身不受影响。

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

> 所有类名/方法名均与 9.2.8 的混淆结果绑定；App 升级后若失效，模块会自动跳过（仅记录日志），不影响 App 运行。

## 构建

需要 Android SDK（`local.properties` 中已指向本机 SDK）与 JDK 17+。

```bat
:: 在项目根目录（含 settings.gradle 的目录）执行：
gradle assembleDebug
:: 或使用本机 gradle 8.13：
C:\Users\24688\.gradle\wrapper\dists\gradle-8.13-bin\5xuhj0ry160q40clulazy9h7d\gradle-8.13\bin\gradle.bat assembleDebug --console=plain
```

产物：`app\build\outputs\apk\debug\app-debug.apk`（已构建）

## 安装与激活（LSPosed）

```bat
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

1. LSPosed 管理器中启用"两步路去广告"；
2. **作用域只勾选** `两步路`（com.lolaage.tbulu.tools）；
3. 强制停止两步路后重新打开。

## 验证

- LSPosed 日志 / logcat 过滤 `TbuluAdFree`，应看到：
  - `loaded target: com.lolaage.tbulu.tools process=...`
  - `hooked ...` 系列
  - `splash ad skipped`
  - `rlVip hidden by fragment hook` / `btnCustomerService hidden by fragment hook` / 解析出的 ids
  - 点击装备 tab：`equipment confirm: YES` 或 `equipment confirm: NO (stay)`
- 界面验证：
  - 冷启动无开屏广告，直接进主界面；
  - "我的"页顶部无会员促销横幅；
  - 首页信息流 / 资讯列表 / 运动记录页 / 出游详情页无广告条目。

## 已知限制

- 广告 SDK（TopOn / 穿山甲 / 广点通）的初始化与预加载请求仍会发生（只隐藏展示与跳过展示入口）；个别预加载在后台进行，不影响界面。
- 网页(H5)内嵌的广告/会员横幅不在本模块范围内。
- 未处理"看广告换权益"类功能（本版本未见）。
