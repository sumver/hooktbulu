package com.tbulu.adfree;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 会员权益"本地门控"解锁（两步路 9.2.8，v1.6 新增）。
 *
 * 背景（smali/JADX 核实）：
 *   App 的"我是不是会员"客户端判断集中在单例 o00OOOOo.OooO0O0：
 *     OooOooO() = OooOoOO() || OooOoo0()   —— VIP 或 SVIP
 *     OooOoo0()                            —— SVIP
 *   五项权益的入口/落点都会调这两个方法做本地拦截（弹 CommonConfirmDialog.showNeedVip
 *   或 VipGuideDialog），判断通过才放行。
 *
 * 本 Hook 的设计原则：
 *   - 不修改账号 VIP 状态、不伪造会员标识、不拦截任何网络请求、不影响购买流程；
 *   - 只在下述"交互入口"方法的执行期间（线程级作用域）让会员判断临时返回 true，
 *     即解除客户端拦截，服务端数据/校验完全保持原样；
 *   - 皮肤/挂件/分享图等"内容数据"仍来自服务端下发，本 Hook 只能解锁本地门控。
 *
 * 五项权益对应处理：
 *   1) 图片水印      RecordPictureOrVideoActivity.o0000O()
 *                    条件：SpUtils.Oooo0o()>0 || !开关 || 会员 → 否则 showNeedVip。
 *                    作用域内会员判断为真 → 拍照直接带水印。
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
 *
 * 所有类名/方法名均与 9.2.8 的混淆结果绑定；App 升级后若失效会自动跳过
 * （仅记录日志），不影响 App 运行。
 */
final class VipGateUnlockHook {

    /** 会员判断单例（Kotlin object：o00OOOOo.OooO0O0）。 */
    private static final String AUTH_SINGLETON = "o00OOOOo.OooO0O0";
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

    /** 已记录过的解锁日志 key（避免刷屏）。 */
    private static final Set<String> sLogged = ConcurrentHashMap.newKeySet();

    private VipGateUnlockHook() {
    }

    static void install(ClassLoader cl) {
        // 1) 会员判断单例：作用域内临时返回 true（不改账号状态、不碰网络）
        try {
            Class<?> auth = XposedHelpers.findClass(AUTH_SINGLETON, cl);
            hookScopedVip(auth, M_VIP_OR_SVIP);
            hookScopedVip(auth, M_SVIP);
            XposedInit.log("VipGateUnlockHook: member-check singleton hooked (" + AUTH_SINGLETON + ")");
        } catch (Throwable t) {
            XposedInit.log("VipGateUnlockHook: member-check singleton not found, skip. " + t);
            return; // 所有解锁都依赖该单例，找不到就整体跳过
        }

        // 2) 各权益交互入口：调用期间打开作用域
        wrapScope(cl, "com.lolaage.tbulu.tools.ui.activity.settings.MoreSettingsActivity",
                "onClick", "diy-home/skin entry");
        wrapScope(cl, "com.lolaage.tbulu.tools.ui.activity.main.MainActivity",
                "onFirstResume", "diy-home map as start page");
        wrapScope(cl, "com.lolaage.tbulu.tools.ui.activity.RecordPictureOrVideoActivity",
                "o0000O", "camera watermark");
        wrapScope(cl, "com.lolaage.tbulu.tools.ui.activity.SetPendantActivity",
                "onClick", "pendant dress-up");
        // 挂件列表 item 点击（adapter 内联点击监听：锁定挂件在此弹 VipGuideDialog）
        wrapScope(cl, "com.lolaage.tbulu.tools.ui.activity.PendantListFragment$mAdapter$2$1$convert$1$1",
                "invoke", "pendant item click");
        // 桌面图标点击（ChangeAppLogoActivity 内联点击监听：type 1=VIP / 3=SVIP）
        wrapScope(cl, "com.lolaage.tbulu.tools.ChangeAppLogoActivity$mAdapter$2$OooO00o",
                "OooOo00", "app logo item click");
        wrapScope(cl, "com.lolaage.tbulu.tools.ui.views.SportShareAdvancedDataView",
                "OooOOo0", "share advanced data save");
        wrapScope(cl, "com.lolaage.tbulu.tools.ui.views.SportShareAdvancedDataView",
                "OooOo00", "share vip banner");
        wrapScope(cl, "com.lolaage.tbulu.tools.ui.dialog.TrackSetUpDialog",
                "onClick", "record skin entry");

        // 3) 独立门控（仅对应功能类内部使用，直接放行）
        hookAlwaysTrue(cl, "com.lolaage.tbulu.domain.SkinInfo",
                "isCanUse", "skin isCanUse");
        hookAlwaysTrue(cl, "com.lolaage.tbulu.tools.ui.activity.SetPendantActivity",
                "Oooo0O0", "pendant selection permission");

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

    private static void wrapScope(ClassLoader cl, String className, String method, String label) {
        try {
            Class<?> cls = XposedHelpers.findClass(className, cl);
            XposedBridge.hookAllMethods(cls, method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    enterScope();
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    exitScope();
                }
            });
            XposedInit.log("VipGateUnlockHook: scoped " + cls.getSimpleName() + "." + method
                    + " (" + label + ")");
        } catch (Throwable t) {
            XposedInit.log("VipGateUnlockHook: scope hook failed " + className + "." + method + ": " + t);
        }
    }

    private static void hookAlwaysTrue(ClassLoader cl, String className, String method, String label) {
        try {
            Class<?> cls = XposedHelpers.findClass(className, cl);
            XposedBridge.hookAllMethods(cls, method, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.getThrowable() != null) {
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
            XposedInit.log("VipGateUnlockHook: hook failed " + className + "." + method + ": " + t);
        }
    }

    // ------------------------------------------------------------------
    // 作用域工具
    // ------------------------------------------------------------------

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
