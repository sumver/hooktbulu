package com.tbulu.adfree;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 屏蔽底部 dock 栏图标上的红点（含未读消息数字），保持 dock 栏纯净。
 *
 * 调用链（两步路 9.2.8）：
 *   MainActivity.o0000()                    // tabIndex=4 即"我的"，未读数 = 通知数 + 聊天未读数
 *     → ITabView.OooO0o0(tab, count, color) // 显示红点
 *     → ITabView$DefaultImpls.OooOO0O
 *     → TabGuideDelegate.OooOOoo(tab,count,color)
 *     → SpaceTabView.OooOO0O(tab, count, color, visible)   // 映射到 SpaceNavigationView 索引
 *     → SpaceNavigationView.Oooo0oo(index, count, color)   // 真正渲染红点
 *
 * 处理原则：只拦"显示红点"这条路，不改动未读消息数据本身，
 * 因此消息中心 / 聊天列表里的未读状态不受影响。
 *
 * 多层保险（任一层生效即可）：
 *   1) SpaceTabView.OooOO0O：visible 参数强制 false（走 App 自带的隐藏分支）
 *   2) TabGuideDelegate.OooOOoo：直接跳过（不再记录/触发红点状态）
 *   3) SpaceNavigationView.Oooo0oo：库层兜底，直接跳过渲染
 */
final class DockBadgeHook {

    private static final String SPACE_TAB_VIEW =
            "com.lolaage.tbulu.tools.ui.widget.maintab.SpaceTabView";
    private static final String TAB_GUIDE_DELEGATE =
            "com.lolaage.tbulu.tools.ui.widget.maintab.TabGuideDelegate";
    private static final String SPACE_NAVIGATION_VIEW =
            "com.luseen.spacenavigation.SpaceNavigationView";

    private DockBadgeHook() {
    }

    static void install(ClassLoader cl) {
        // 1) dock 红点渲染回调：tab(0) / count(1) / color(2) / visible(3)
        Class<?> spaceTabView = findClassSafe(cl, SPACE_TAB_VIEW);
        if (spaceTabView != null) {
            hookAllSafe(spaceTabView, "OooOO0O", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length == 4
                            && param.args[3] instanceof Boolean
                            && param.args[1] instanceof Integer) {
                        param.args[3] = Boolean.FALSE; // 永不显示红点
                        param.args[1] = 0;             // 数字清零（隐藏分支不使用）
                    }
                }
            });
        }

        // 2) 红点状态记录层：不再存入/触发
        Class<?> delegate = findClassSafe(cl, TAB_GUIDE_DELEGATE);
        if (delegate != null) {
            hookAllSafe(delegate, "OooOOoo", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    param.setResult(null);
                }
            });
        }

        // 3) 库层兜底：SpaceNavigationView.Oooo0oo(index, count, color) 直接跳过渲染
        Class<?> navView = findClassSafe(cl, SPACE_NAVIGATION_VIEW);
        if (navView != null) {
            hookAllSafe(navView, "Oooo0oo", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    param.setResult(null);
                }
            });
        }

        XposedInit.log("DockBadgeHook installed.");
    }

    private static Class<?> findClassSafe(ClassLoader cl, String name) {
        try {
            return XposedHelpers.findClass(name, cl);
        } catch (Throwable t) {
            XposedInit.log("DockBadgeHook: class not found (skip): " + name);
            return null;
        }
    }

    private static void hookAllSafe(Class<?> cls, String method, XC_MethodHook cb) {
        try {
            XposedBridge.hookAllMethods(cls, method, cb);
            XposedInit.log("hooked " + cls.getSimpleName() + "." + method);
        } catch (Throwable t) {
            XposedInit.log("DockBadgeHook hook failed: " + cls.getSimpleName() + "." + method + " " + t);
        }
    }
}
