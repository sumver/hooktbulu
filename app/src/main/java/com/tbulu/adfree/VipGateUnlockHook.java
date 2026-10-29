package com.tbulu.adfree;

import java.util.ArrayDeque;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 会员权益"本地门控"解锁（两步路 9.2.8，v1.6 新增，v1.8 起接入分类开关，v1.23 追加轨迹叠加）。
 *
 * 背景（smali/JADX 核实）：
 *   App 的"我是不是会员"客户端判断集中在单例 o00OOOOo.OooO0O0：
 *     OooOooO() = OooOoOO() || OooOoo0()   —— VIP 或 SVIP
 *     OooOoo0()                            —— SVIP
 *   各权益的入口/落点都会调这两个方法做本地拦截（弹 CommonConfirmDialog.showNeedVip
 *   或 VipGuideDialog），判断通过才放行。
 *
 * 本 Hook 的设计原则：
 *   - 不修改账号 VIP 状态、不伪造会员标识、不拦截任何网络请求、不影响购买流程；
 *   - 只在下述"交互入口"方法的执行期间（线程级作用域）让会员判断临时返回 true，
 *     即解除客户端拦截，服务端数据/校验完全保持原样；
 *   - 皮肤/挂件/分享图等"内容数据"仍来自服务端下发，本 Hook 只能解锁本地门控。
 *
 * 开关（设置界面「解锁会员功能」分组，默认开启）：
 *   图片水印 → K_VIP_WATERMARK；DIY首页 → K_VIP_DIY_HOME；头像挂件 → K_VIP_PENDANT；
 *   专属样式 → K_VIP_STYLE；个性化分享 → K_VIP_SHARE；轨迹叠加 → K_VIP_OVERLAY_TRACKS；
 *   批量导出/分享 → K_VIP_BULK_EXPORT_SHARE。
 *   对应开关关闭时，其交互入口不再进入作用域，行为回到未解锁状态。
 *
 * 各权益对应处理：
 *   1) 图片水印      RecordPictureOrVideoActivity.o0000O()
 *                    条件：SpUtils.Oooo0o()>0 || !开关 || 会员 → 否则 showNeedVip。
 *   2) DIY首页       MoreSettingsActivity.onClick（"启动后默认界面"入口）+
 *                    MainActivity.onFirstResume（把地图设为首页后的自动跳转生效）。
 *   3) 个性化形象    SetPendantActivity.onClick（"立即装扮"按钮）+
 *                    SetPendantActivity.Oooo0O0(int)（挂件选择权限：1=VIP/2=SVIP）+
 *                    PendantListFragment$mAdapter$2$1$convert$1$1.invoke（挂件 item 点击监听：
 *                    锁定挂件本来会在此弹 VipGuideDialog）。
 *   4) 专属样式      SkinInfo.isCanUse()（皮肤可用性：VIP/SVIP 门槛）
 *                    + TrackSetUpDialog.onClick（记录页"记录/导航时皮肤"入口）
 *                    + ChangeAppLogoActivity$mAdapter$2$OooO00o.OooOo00（桌面图标点击：
 *                      图标自带分档 type 1=VIP / 3=SVIP，点击时校验，放行后走图标应用分支）。
 *   5) 个性化分享    SportShareAdvancedDataView.OooOOo0()（保存带高级数据的分享图）
 *                    + OooOo00()（隐藏"加入VIP/SVIP会员"横幅）。
 *   6) 轨迹叠加      o00O.OooO0O0.OooOOO(ctx, 已选数, 新增数, skipOffset)：配额判断，
 *                    非会员 >10 条弹 TrackSuperpositionGuideDialog（开通引导），
 *                    会员 >100 条 Toast"已达到叠加上限"；
 *                    + TrackSearchAndSelectActivity.Ooooooo()（"选择轨迹(x/10)" vs "(x/100)" 标题）；
 *                    + o00O.OooO0O0$Oooo000#then / $o000oOoO#then（叠加确认链路分支：非会员
 *                      "已有+新增>10"弹 StackingTrackDialog，VIP 按 100 判断；选择页"确认叠加"、
 *                      地图页"叠加到地图"、云端/本地列表等批量入口共用）；
 *                    + StackingTrackDialog#setData（"温馨提醒"确认窗渲染：非会员显示"开通会员"
 *                      按钮 + 10/100 引导文案、新增>10 时隐藏"叠加到地图"；VIP 无开通按钮、
 *                      "≤100 条可叠加"）。
 *                    作用域内按会员处理 → 上限 100 条、开通窗不再出现。
 *                    注：配额判断为纯本地逻辑，无服务端校验。
 *   7) 批量导出/分享 MyTrackLocalFragment.o00000oO(dataType)：轨迹列表「合并/导出」→ 批量操作弹窗
 *                    （批量合并=1 / 批量导出=2 / 批量分享=3）的确定回调，导出与分享在此做本地会员
 *                    判断，非会员弹 TrackBatchVipGuideDialog（批量合并无校验）；VIP 档"最多同时
 *                    操作 100 条"的限制不变；名称/图标导出与系统分享的执行链路无二次校验。
 *                    作用域内按会员处理 → 导出/分享直接执行。
 *
 * 所有类名/方法名均与 9.2.8 / 9.2.9 两版混淆结果绑定（9.2.9 优先，miss 时回退 9.2.8 旧名）：
 * 9.2.9 中仅以下名字发生漂移（其余 com.lolaage.tbulu.tools.* 类名不变）：
 *   - 会员判断单例  o00OOOOo.OooO0O0  →  o00O0oOO.o0OoOo0（方法名 OooOooO/OooOoo0/OooOoOO 不变）
 *   - 轨迹叠加配额  o00O.OooO0O0      →  o00O0O0O.OooO0O0（方法 OooOOO、内部类 $Oooo000/$o000oOoO 不变）
 *   - 桌面图标点击监听  ChangeAppLogoActivity$mAdapter$2$OooO00o  →  $mAdapter$2$1
 * App 升级后若失效会自动跳过（仅记录日志），不影响 App 运行。
 */
final class VipGateUnlockHook {

    /** OooOooO() = OooOoOO() || OooOoo0()（VIP 或 SVIP）。 */
    private static final String M_VIP_OR_SVIP = "OooOooO";
    /** OooOoo0()（SVIP）。 */
    private static final String M_SVIP = "OooOoo0";

    /** 作用域深度（线程级）：>0 时会员判断临时返回 true。 */
    private static final ThreadLocal<Integer> sScopeDepth = new ThreadLocal<Integer>() {
        @Override
        protected Integer initialValue() {
            return 0;
        }
    };

    /** 每个包装方法调用的"本次是否进入作用域"决策栈（before 入栈 / after 出栈）。 */
    private static final ThreadLocal<ArrayDeque<Boolean>> sScopeStack =
            new ThreadLocal<ArrayDeque<Boolean>>() {
                @Override
                protected ArrayDeque<Boolean> initialValue() {
                    return new ArrayDeque<>();
                }
            };

    /** 已记录过的解锁日志 key（避免刷屏）。 */
    private static final Set<String> sLogged = ConcurrentHashMap.newKeySet();

    private VipGateUnlockHook() {
    }

    static void install(ClassLoader cl, TargetProfile profile) {
        // 1) 会员判断单例：作用域内临时返回 true（不改账号状态、不碰网络）
        Class<?> auth = findClassAny(cl, profile.authSingletonNames);
        if (auth == null) {
            XposedInit.log("VipGateUnlockHook: member-check singleton not found, skip.");
            return; // 所有解锁都依赖该单例，找不到就整体跳过
        }
        try {
            hookScopedVip(auth, M_VIP_OR_SVIP);
            hookScopedVip(auth, M_SVIP);
            XposedInit.log("VipGateUnlockHook: member-check singleton hooked (" + auth.getName() + ")");
        } catch (Throwable t) {
            XposedInit.log("VipGateUnlockHook: member-check singleton hook failed: " + t);
            return;
        }

        // 2) 各权益交互入口：调用期间按开关打开作用域
        wrapScope(cl, "com.lolaage.tbulu.tools.ui.activity.settings.MoreSettingsActivity",
                "onClick", "diy-home/skin entry", Prefs.K_VIP_DIY_HOME, Prefs.K_VIP_STYLE);
        wrapScope(cl, "com.lolaage.tbulu.tools.ui.activity.main.MainActivity",
                "onFirstResume", "diy-home map as start page", Prefs.K_VIP_DIY_HOME);
        wrapScope(cl, "com.lolaage.tbulu.tools.ui.activity.RecordPictureOrVideoActivity",
                "o0000O", "camera watermark", Prefs.K_VIP_WATERMARK);
        wrapScope(cl, "com.lolaage.tbulu.tools.ui.activity.SetPendantActivity",
                "onClick", "pendant dress-up", Prefs.K_VIP_PENDANT);
        // 挂件列表 item 点击（adapter 内联点击监听：锁定挂件在此弹 VipGuideDialog）
        wrapScope(cl, "com.lolaage.tbulu.tools.ui.activity.PendantListFragment$mAdapter$2$1$convert$1$1",
                "invoke", "pendant item click", Prefs.K_VIP_PENDANT);
        // 桌面图标点击（ChangeAppLogoActivity 内联点击监听：type 1=VIP / 3=SVIP）
        wrapScopeAny(cl, profile.appLogoListenerNames,
                "OooOo00", "app logo item click", Prefs.K_VIP_STYLE);
        wrapScope(cl, "com.lolaage.tbulu.tools.ui.views.SportShareAdvancedDataView",
                "OooOOo0", "share advanced data save", Prefs.K_VIP_SHARE);
        wrapScope(cl, "com.lolaage.tbulu.tools.ui.views.SportShareAdvancedDataView",
                "OooOo00", "share vip banner", Prefs.K_VIP_SHARE);
        wrapScope(cl, "com.lolaage.tbulu.tools.ui.dialog.TrackSetUpDialog",
                "onClick", "record skin entry", Prefs.K_VIP_STYLE);
        // 轨迹叠加配额：非会员上限 10 条（超限弹 TrackSuperpositionGuideDialog）；
        // 作用域内按会员处理 → 上限 100 条（所有叠加入口共用该方法，见类注释 6)）
        wrapScopeAny(cl, profile.overlayQuotaNames,
                "OooOOO", "track overlay quota (10 -> 100)", Prefs.K_VIP_OVERLAY_TRACKS);
        // 选择轨迹页标题：同一本地判断决定 "选择轨迹(x/10)" 还是 "(x/100)"
        wrapScope(cl, "com.lolaage.tbulu.tools.ui.activity.tracks.search.TrackSearchAndSelectActivity",
                "Ooooooo", "track select title max (10 -> 100)", Prefs.K_VIP_OVERLAY_TRACKS);
        // 叠加确认链路（Bolts 回调）：非会员"已有+新增>10 或 本次新增=0"弹 StackingTrackDialog，
        // 否则直接叠加；VIP 按 100 判断。两个 continuation 覆盖全部批量叠加入口
        // （选择页"确认叠加"、地图页"叠加到地图"、云端/本地轨迹列表等）
        wrapScopeAny(cl, profile.overlayBranch1Names,
                "then", "overlay branch (select page confirm)", Prefs.K_VIP_OVERLAY_TRACKS);
        wrapScopeAny(cl, profile.overlayBranch2Names,
                "then", "overlay branch (batch api)", Prefs.K_VIP_OVERLAY_TRACKS);
        // 叠加确认弹窗（温馨提醒）渲染：非会员显示"开通会员"按钮 + 10/100 引导文案，
        // 且本次新增>10 时隐藏"叠加到地图"；作用域内按会员渲染（"您最多支持同时叠加100条轨迹。"、
        // 无开通按钮、≤100 条时保留"叠加到地图"）
        wrapScope(cl, "com.lolaage.tbulu.tools.ui.dialog.StackingTrackDialog",
                "setData", "overlay confirm dialog (vip render)", Prefs.K_VIP_OVERLAY_TRACKS);
        // 轨迹列表「合并/导出」→ 批量操作弹窗（TrackBulkDataOperationDialog）确定回调：
        // 各版本回调方法名不同（9.2.9=o00000oo(Integer)，9.2.8=o00000oO(Integer)），
        // 由版本档案给出候选，并按「1 个 int/Integer 参数」的签名校验，避免命中同名的无参方法。
        // dataType=2 导出 / 3 分享都会调本地会员判断，非会员弹 TrackBatchVipGuideDialog（开通引导）；
        // 会员档 >100 条同样弹该窗口（"VIP最多可同时操作100条"）。执行链路（本地生成文件/分享）无校验。
        wrapScopeBySignature(cl, "com.lolaage.tbulu.tools.ui.fragment.mytracks.MyTrackLocalFragment",
                profile.bulkExportMethods, "bulk export/share vip gate", Prefs.K_VIP_BULK_EXPORT_SHARE);

        // 3) 独立门控（仅对应功能类内部使用，按开关直接放行）
        hookAlwaysTrue(cl, "com.lolaage.tbulu.domain.SkinInfo",
                "isCanUse", "skin isCanUse", Prefs.K_VIP_STYLE);
        // 挂件选择权限（(int)->boolean，1=VIP / 2=SVIP）：版本档案给出方法名候选
        hookAlwaysTrueBySignature(cl, "com.lolaage.tbulu.tools.ui.activity.SetPendantActivity",
                profile.pendantPermissionMethods, "pendant selection permission", Prefs.K_VIP_PENDANT);

        XposedInit.log("VipGateUnlockHook installed.");
    }

    // ------------------------------------------------------------------
    // 会员判断单例：作用域内翻转
    // ------------------------------------------------------------------

    private static void hookScopedVip(Class<?> auth, final String method) {
        try {
            XposedBridge.hookAllMethods(auth, method, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!inScope() || param.getThrowable() != null) {
                        return;
                    }
                    Object result = param.getResult();
                    if (result instanceof Boolean && (Boolean) result) {
                        return; // 本来就是真（VIP 用户），无需干预
                    }
                    param.setResult(Boolean.TRUE);
                    logOnce("bypass " + method + " -> true (scoped)");
                }
            });
            XposedInit.log("VipGateUnlockHook: hooked " + auth.getSimpleName() + "." + method + " (scoped)");
        } catch (Throwable t) {
            XposedInit.log("VipGateUnlockHook: hook " + method + " failed: " + t);
        }
    }

    // ------------------------------------------------------------------
    // 作用域包装 / 直接置真
    // ------------------------------------------------------------------

    /**
     * 包装一个"交互入口"方法：方法执行期间打开作用域（会员判断临时为真）。
     * 仅当 {@code keys} 中任一开关开启时进入；before/after 通过线程级决策栈配对，
     * 避免"进入时开着、退出时关了"造成的深度错配。
     */
    private static void wrapScope(ClassLoader cl, String className, String method, String label,
                                  final String... keys) {
        wrapScopeAny(cl, new String[]{className}, method, label, keys);
    }

    /** 同 {@link #wrapScope}，但类名支持多候选（9.2.9 优先，miss 时尝试 9.2.8 旧名）。 */
    private static void wrapScopeAny(ClassLoader cl, String[] classNames, String method, String label,
                                     final String... keys) {
        wrapScopeAnyMethods(cl, classNames, new String[]{method}, label, keys);
    }

    /** 类名、方法名都支持多候选：取第一个「类存在且含该方法」的组合。 */
    private static void wrapScopeMethods(ClassLoader cl, String className, String[] methods,
                                         String label, final String... keys) {
        wrapScopeAnyMethods(cl, new String[]{className}, methods, label, keys);
    }

    private static void wrapScopeAnyMethods(ClassLoader cl, String[] classNames, String[] methods,
                                            String label, final String... keys) {
        try {
            Class<?> cls = findClassAny(cl, classNames);
            if (cls == null) {
                XposedInit.log("VipGateUnlockHook: scope hook class not found: "
                        + String.join("/", classNames) + "." + String.join("/", methods));
                return;
            }
            String method = firstExistingMethod(cls, methods);
            if (method == null) {
                XposedInit.log("VipGateUnlockHook: scope hook method not found: "
                        + cls.getName() + "." + String.join("/", methods));
                return;
            }
            XposedBridge.hookAllMethods(cls, method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    boolean enter = anyEnabled(keys);
                    sScopeStack.get().push(enter);
                    if (enter) {
                        enterScope();
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Boolean enter = sScopeStack.get().poll();
                    if (Boolean.TRUE.equals(enter)) {
                        exitScope();
                    }
                }
            });
            XposedInit.log("VipGateUnlockHook: scoped " + cls.getSimpleName() + "." + method
                    + " (" + label + ")");
        } catch (Throwable t) {
            XposedInit.log("VipGateUnlockHook: scope hook failed "
                    + String.join("/", classNames) + "." + String.join("/", methods) + ": " + t);
        }
    }

    /** 按签名（1 个 int/Integer 参数）解析方法并做作用域包装。 */
    private static void wrapScopeBySignature(ClassLoader cl, String className, String[] methods,
                                             String label, final String... keys) {
        try {
            Class<?> cls = findClassAny(cl, new String[]{className});
            if (cls == null) {
                XposedInit.log("VipGateUnlockHook: scope hook class not found: " + className);
                return;
            }
            String method = firstMethodBySignature(cls, methods, true);
            if (method == null) {
                XposedInit.log("VipGateUnlockHook: scope hook method(1-int) not found: "
                        + cls.getName() + "." + String.join("/", methods));
                return;
            }
            hookScopeNamed(cls, method, label, keys);
        } catch (Throwable t) {
            XposedInit.log("VipGateUnlockHook: scope hook failed " + className + ": " + t);
        }
    }

    private static void hookScopeNamed(Class<?> cls, String method, String label, final String... keys) {
        XposedBridge.hookAllMethods(cls, method, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                boolean enter = anyEnabled(keys);
                sScopeStack.get().push(enter);
                if (enter) {
                    enterScope();
                }
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Boolean enter = sScopeStack.get().poll();
                if (Boolean.TRUE.equals(enter)) {
                    exitScope();
                }
            }
        });
        XposedInit.log("VipGateUnlockHook: scoped " + cls.getSimpleName() + "." + method
                + " (" + label + ")");
    }

    /**
     * 返回第一个满足签名的方法名：
     *  - oneIntParam=true：恰好 1 个 int/Integer 参数（批量导出回调等）；
     *  - oneIntParam=false：恰好 1 个 int 参数且返回 boolean（挂件权限等）。
     */
    private static String firstMethodBySignature(Class<?> cls, String[] methods, boolean oneIntParam) {
        for (String name : methods) {
            for (java.lang.reflect.Method m : cls.getDeclaredMethods()) {
                if (!m.getName().equals(name)) {
                    continue;
                }
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length != 1) {
                    continue;
                }
                boolean intParam = ps[0] == int.class || ps[0] == Integer.class;
                if (!intParam) {
                    continue;
                }
                if (oneIntParam || m.getReturnType() == boolean.class) {
                    return name;
                }
            }
        }
        return null;
    }

    /** 按签名（1 个 int 参数 -> boolean）解析并做“直接置真”。 */
    private static void hookAlwaysTrueBySignature(ClassLoader cl, String className, String[] methods,
                                                  String label, final String key) {
        try {
            Class<?> cls = findClassAny(cl, new String[]{className});
            if (cls == null) {
                XposedInit.log("VipGateUnlockHook: always-true class not found: " + className);
                return;
            }
            String method = firstMethodBySignature(cls, methods, false);
            if (method == null) {
                XposedInit.log("VipGateUnlockHook: always-true method(int->bool) not found: "
                        + cls.getName() + "." + String.join("/", methods));
                return;
            }
            hookAlwaysTrueOn(cls, method, label, key);
        } catch (Throwable t) {
            XposedInit.log("VipGateUnlockHook: always-true hook failed " + className + ": " + t);
        }
    }

    private static void hookAlwaysTrueOn(Class<?> cls, String method, String label, final String key) {
        XposedBridge.hookAllMethods(cls, method, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (param.getThrowable() != null || !Prefs.isEnabled(key)) {
                    return;
                }
                Object result = param.getResult();
                if (result instanceof Boolean && (Boolean) result) {
                    return;
                }
                param.setResult(Boolean.TRUE);
                logOnce("bypass " + cls.getSimpleName() + "." + method + " -> true");
            }
        });
        XposedInit.log("VipGateUnlockHook: hooked " + cls.getSimpleName() + "." + method
                + " (" + label + ")");
    }

    /** 返回 methods 中第一个在 cls 上存在的方法名。 */
    private static String firstExistingMethod(Class<?> cls, String[] methods) {
        for (String m : methods) {
            try {
                for (java.lang.reflect.Method dm : cls.getDeclaredMethods()) {
                    if (dm.getName().equals(m)) {
                        return m;
                    }
                }
                // 也检查继承方法
                cls.getMethod(m);
                return m;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static void hookAlwaysTrue(ClassLoader cl, String className, String method, String label,
                                       final String key) {
        hookAlwaysTrueAny(cl, new String[]{className}, new String[]{method}, label, key);
    }

    /** 同 {@link #hookAlwaysTrue}，但支持多个方法候选（9.2.9 新名优先）。 */
    private static void hookAlwaysTrueAny(ClassLoader cl, String[] classNames, String[] methods,
                                          String label, final String key) {
        try {
            Class<?> cls = findClassAny(cl, classNames);
            if (cls == null) {
                XposedInit.log("VipGateUnlockHook: always-true class not found: "
                        + String.join("/", classNames) + "." + String.join("/", methods));
                return;
            }
            for (String method : methods) {
                try {
                    XposedBridge.hookAllMethods(cls, method, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.getThrowable() != null || !Prefs.isEnabled(key)) {
                                return;
                            }
                            // 仅对返回 boolean 的方法置真（9.2.9 存在同名 void 方法，避免 setResult 抛错）
                            if (param.method instanceof java.lang.reflect.Method
                                    && ((java.lang.reflect.Method) param.method).getReturnType() != boolean.class) {
                                return;
                            }
                            Object result = param.getResult();
                            if (result instanceof Boolean && (Boolean) result) {
                                return;
                            }
                            param.setResult(Boolean.TRUE);
                            logOnce("bypass " + cls.getSimpleName() + "." + method + " -> true");
                        }
                    });
                    XposedInit.log("VipGateUnlockHook: hooked " + cls.getSimpleName() + "." + method
                            + " (" + label + ")");
                } catch (Throwable t) {
                    XposedInit.log("VipGateUnlockHook: hook failed " + cls.getSimpleName() + "."
                            + method + ": " + t);
                }
            }
        } catch (Throwable t) {
            XposedInit.log("VipGateUnlockHook: hook failed " + String.join("/", classNames)
                    + "." + String.join("/", methods) + ": " + t);
        }
    }

    // ------------------------------------------------------------------
    // 作用域工具
    // ------------------------------------------------------------------

    /** 按候选顺序（9.2.9 优先）解析类，全部 miss 返回 null。 */
    private static Class<?> findClassAny(ClassLoader cl, String... names) {
        for (String name : names) {
            try {
                return XposedHelpers.findClass(name, cl);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static boolean anyEnabled(String[] keys) {
        for (String key : keys) {
            if (Prefs.isEnabled(key)) {
                return true;
            }
        }
        return false;
    }

    private static void enterScope() {
        sScopeDepth.set(sScopeDepth.get() + 1);
    }

    private static void exitScope() {
        int depth = sScopeDepth.get();
        sScopeDepth.set(depth > 0 ? depth - 1 : 0);
    }

    private static boolean inScope() {
        Integer depth = sScopeDepth.get();
        return depth != null && depth > 0;
    }

    private static void logOnce(String msg) {
        if (sLogged.add(msg)) {
            XposedInit.log(msg);
        }
    }
}
