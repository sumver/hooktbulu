package com.tbulu.adfree;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.AndroidAppHelper;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 拦截底部 dock 栏"装备"tab（我的左边）的点击：先弹窗确认"是否打开商城？"。
 * 点"否"（或返回键取消）保持当前页面不变；点"是"才真正跳转有赞商城。
 * 目的：防止误触。
 *
 * 链路（两步路 9.2.8，smali 核实）：
 *   装备tab点击 → SpaceTabView$OooO00o.OooO00o → SpaceTabView.OooOo0(tabIndex=3,true,false)
 *   → ITabView$DefaultImpls.dispatchTabClick → ITabView$dispatchTabClick$1.invoke
 *   → ITabView$OooO00o.OooO0OO(3)
 *        tab==3 → YouZanWebviewActivity 打开商城（返回 true，不切 fragment）
 *        tab==2 → 中心"记录"按钮 → TabTrackActivity
 *        其他   → 返回 false → 正常切 tab
 *
 * 处理：hook ITabView$OooO00o.OooO0OO(I)Z
 *   - 非 3 直接放行；
 *   - ==3：消费本次点击（返回 true，dock 保持原样）并弹确认框：
 *       点"是" → 设置一次性放行标记后再调用原方法（真正打开商城）；
 *       点"否"/取消 → 什么都不做。
 *     弹窗展示期间重复点击不会叠加弹窗。
 *
 * 开关：受设置界面「装备 tab 防误触」({@link Prefs#K_EQUIPMENT}) 控制，默认开启。
 */
final class EquipmentTabHook {

    private static final String HELPER_CLASS =
            "com.lolaage.tbulu.tools.ui.widget.maintab.ITabView$OooO00o";
    private static final String LIFECYCLE_CLASS =
            "com.lolaage.tbulu.tools.utils.ActivityLifecycleListener";
    private static final String METHOD = "OooO0OO";
    private static final int TAB_EQUIPMENT = 3;

    /** 用户已确认"是"：一次性放行标记。 */
    private static final AtomicBoolean sConfirmedBypass = new AtomicBoolean(false);
    /** 确认弹窗正在展示（防重复弹）。 */
    private static final AtomicBoolean sDialogShowing = new AtomicBoolean(false);

    private EquipmentTabHook() {
    }

    static void install(ClassLoader cl) {
        Class<?> helper;
        try {
            helper = XposedHelpers.findClass(HELPER_CLASS, cl);
        } catch (Throwable t) {
            XposedInit.log("EquipmentTabHook: helper class not found, skip. " + t);
            return;
        }
        try {
            XposedBridge.hookAllMethods(helper, METHOD, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length < 1 || !(param.args[0] instanceof Integer)) {
                        return;
                    }
                    if (!Prefs.isEnabled(Prefs.K_EQUIPMENT)) {
                        return; // 开关关闭：不拦截，直接走原点击逻辑
                    }
                    int tab = (Integer) param.args[0];
                    if (tab != TAB_EQUIPMENT) {
                        return;
                    }
                    if (sConfirmedBypass.compareAndSet(true, false)) {
                        // 用户确认过"是"：放行本次调用（真正打开商城）
                        return;
                    }
                    // 首次点击：消费（dock 保持原样），进入确认流程
                    param.setResult(Boolean.TRUE);
                    requestConfirm(param);
                }
            });
            XposedInit.log("hooked ITabView$OooO00o.OooO0OO (equipment tab guard)");
        } catch (Throwable t) {
            XposedInit.log("EquipmentTabHook hook failed: " + t);
        }
        XposedInit.log("EquipmentTabHook installed.");
    }

    private static void requestConfirm(final XC_MethodHook.MethodHookParam param) {
        if (!sDialogShowing.compareAndSet(false, true)) {
            // 已有弹窗：静默消费本次点击
            return;
        }
        final Context ctx = findTopActivity(param);
        if (ctx == null) {
            // 拿不到上下文：不拦截，直接放行原逻辑（避免卡住用户）
            XposedInit.log("equipment confirm: no context, allow original flow");
            sDialogShowing.set(false);
            sConfirmedBypass.set(true);
            try {
                XposedHelpers.callMethod(param.thisObject, METHOD, param.args);
            } catch (Throwable t) {
                XposedInit.log("open mall (fallback) failed: " + t);
            }
            return;
        }
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                try {
                    new AlertDialog.Builder(ctx)
                            .setTitle("提示")
                            .setMessage("是否打开商城？")
                            .setPositiveButton("是", new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    XposedInit.log("equipment confirm: YES");
                                    sConfirmedBypass.set(true);
                                    try {
                                        XposedHelpers.callMethod(param.thisObject, METHOD, param.args);
                                    } catch (Throwable t) {
                                        XposedInit.log("open mall failed: " + t);
                                    }
                                }
                            })
                            .setNegativeButton("否", new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    XposedInit.log("equipment confirm: NO (stay)");
                                }
                            })
                            .setCancelable(true)
                            .setOnDismissListener(new DialogInterface.OnDismissListener() {
                                @Override
                                public void onDismiss(DialogInterface dialog) {
                                    sDialogShowing.set(false);
                                }
                            })
                            .show();
                } catch (Throwable t) {
                    sDialogShowing.set(false);
                    XposedInit.log("show confirm dialog failed: " + t);
                }
            }
        });
    }

    private static Context findTopActivity(XC_MethodHook.MethodHookParam param) {
        try {
            ClassLoader cl = param.thisObject.getClass().getClassLoader();
            Class<?> lifecycle = XposedHelpers.findClass(LIFECYCLE_CLASS, cl);
            Object inst = XposedHelpers.getStaticObjectField(lifecycle, "INSTANCE");
            Object act = XposedHelpers.callMethod(inst, "getTopActivity");
            if (act instanceof Activity) {
                return (Activity) act;
            }
        } catch (Throwable t) {
            XposedInit.log("findTopActivity failed: " + t);
        }
        try {
            return AndroidAppHelper.currentApplication();
        } catch (Throwable ignored) {
        }
        return null;
    }
}
