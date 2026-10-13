package com.tbulu.adfree;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 屏蔽升级弹窗（两步路 9.2.8）。
 *
 * 涉及两个弹窗：
 *  1) UpgradeInfoDialog（版本详情弹窗：立即更新/下载安装）
 *     smali 核实：所有展示路径（启动自动检查的缓存路径、网络检查回调路径、
 *     设置页手动检查）最终都汇聚到唯一入口
 *     com.lolaage.tbulu.tools.upgrade.OooO0o#OooOOOo(BaseActivity)
 *     （OooO0Oo 缓存分支直接调用；网络回调经静态桥 OooO0O0 → OooOOOo）
 *  2) UpdateTipsDialog（"我的"页的更新提示弹窗）
 *     唯一创建入口：ui.fragment.main.o0000#o0000oo0()
 *     （自动触发 OooOOo0(Z) 与 手动点击 :sswitch_6 都走这里）
 *
 * 处理：两个入口直接跳过。自动版本检测逻辑本身不动（不修改功能），
 * 仅不再弹出任何升级窗口；手动检查在"已是最新"时仍有 toast 提示。
 */
final class UpgradeDialogHook {

    private static final String UPGRADE_MANAGER = "com.lolaage.tbulu.tools.upgrade.OooO0o";
    private static final String SETUP_FRAGMENT = "com.lolaage.tbulu.tools.ui.fragment.main.o0000";

    private UpgradeDialogHook() {
    }

    static void install(ClassLoader cl) {
        // 1) UpgradeInfoDialog 唯一展示入口
        Class<?> manager = findClassSafe(cl, UPGRADE_MANAGER);
        if (manager != null) {
            hookAllSafe(manager, "OooOOOo", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    XposedInit.log("upgrade dialog (UpgradeInfoDialog) blocked");
                    param.setResult(null);
                }
            });
        }

        // 2) 我的页"更新提示弹窗"唯一入口
        Class<?> fragment = findClassSafe(cl, SETUP_FRAGMENT);
        if (fragment != null) {
            hookAllSafe(fragment, "o0000oo0", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    XposedInit.log("update tips dialog (UpdateTipsDialog) blocked");
                    param.setResult(null);
                }
            });
        }

        XposedInit.log("UpgradeDialogHook installed.");
    }

    private static Class<?> findClassSafe(ClassLoader cl, String name) {
        try {
            return XposedHelpers.findClass(name, cl);
        } catch (Throwable t) {
            XposedInit.log("UpgradeDialogHook: class not found (skip): " + name);
            return null;
        }
    }

    private static void hookAllSafe(Class<?> cls, String method, XC_MethodHook cb) {
        try {
            XposedBridge.hookAllMethods(cls, method, cb);
            XposedInit.log("hooked " + cls.getSimpleName() + "." + method);
        } catch (Throwable t) {
            XposedInit.log("UpgradeDialogHook hook failed: " + cls.getSimpleName() + "." + method + " " + t);
        }
    }
}
