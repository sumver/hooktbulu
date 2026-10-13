package com.tbulu.adfree;

import android.app.AndroidAppHelper;
import android.view.View;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * "我的"页专属清理 Hook。
 *
 * 当前处理两类控件（均为固定资源 id 名，运行时解析不硬编码）：
 *
 * 1) rlVip —— 会员促销横幅（fragment_setup.xml，含 tvVipSuptitle / tvVipButton / tvVipTime）。
 *    数据链路：o000OOo0() → UserAPI.getVipBuyEntranceInfo() → 回调 o0000$OooO0O0.OooO00o() 控制显隐。
 *
 * 2) btnCustomerService —— 顶部 VIP 客服按钮（图标 ic_vip_4）。
 *    点击（o0000.onClick :sswitch_18）会弹出 ExclusiveCustomerServiceDialog
 *    （"专属客服"底部弹层：稍后再说 / 立即开通），按钮隐藏后该弹窗不再出现。
 *
 * 多重保险：
 *  1) hook 我的页 Fragment 的 o000OOo0 / OooOo0 / onResume / onHiddenChanged 之后，把目标控件置 GONE；
 *  2) 全局 View.setVisibility 守卫：任何对目标 id 的可见性设置都被改写为 GONE；
 *  资源 id 全部通过 getIdentifier 运行时解析，避免版本间资源编号变化。
 */
final class VipBannerHook {

    private static final String FRAGMENT_CLASS = "com.lolaage.tbulu.tools.ui.fragment.main.o0000";

    /** 需要在"我的"页隐藏的控件资源名。 */
    private static final String[] HIDDEN_ID_NAMES = {
            "rlVip",              // 会员促销横幅
            "btnCustomerService"  // 顶部 VIP 客服按钮（icon = ic_vip_4）
    };

    /** id 解析缓存（name -> id），0 表示尚未解析成功。 */
    private static final Map<String, Integer> sIdCache = new ConcurrentHashMap<>();

    private VipBannerHook() {
    }

    static void install(ClassLoader cl) {
        Class<?> fragment;
        try {
            fragment = XposedHelpers.findClass(FRAGMENT_CLASS, cl);
        } catch (Throwable t) {
            XposedInit.log("VipBannerHook: fragment class not found, skip. " + t);
            return;
        }

        // 1) 数据刷新后隐藏
        safeHookAll(fragment, "o000OOo0", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                hidePromoViews(param.thisObject);
            }
        });

        // 2) 视图创建 / 回到前台（含从其它页返回）/ 切换隐藏时隐藏
        safeHookAll(fragment, "OooOo0", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                hidePromoViews(param.thisObject);
            }
        });
        safeHookAll(fragment, "onResume", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                hidePromoViews(param.thisObject);
            }
        });
        safeHookAll(fragment, "onHiddenChanged", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                hidePromoViews(param.thisObject);
            }
        });

        // 全局守卫：任何代码试图把目标控件设为可见都会被改写为 GONE。
        // 注意：btnCustomerService 是本 App 中 ExclusiveCustomerServiceDialog 的唯一入口，
        // 按钮隐藏后该"稍后再说/立即开通"弹窗不会再出现，无需额外 hook Dialog.show()
        // （Dialog.show 是继承方法，hook 子类会误伤全局所有弹窗）。
        XposedHelpers.findAndHookMethod(View.class, "setVisibility", int.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.thisObject instanceof View) {
                    View v = (View) param.thisObject;
                    int id = v.getId();
                    if (id != 0 && isHiddenId(id)) {
                        param.args[0] = View.GONE;
                    }
                }
            }
        });

        XposedInit.log("VipBannerHook installed.");
    }

    private static void safeHookAll(Class<?> cls, String method, XC_MethodHook cb) {
        try {
            XposedBridge.hookAllMethods(cls, method, cb);
            XposedInit.log("hooked " + cls.getSimpleName() + "." + method);
        } catch (Throwable t) {
            XposedInit.log("hook failed: " + cls.getSimpleName() + "." + method + " " + t);
        }
    }

    /** 把"我的"页需要隐藏的控件全部置 GONE。 */
    private static void hidePromoViews(Object fragment) {
        try {
            Object viewObj = XposedHelpers.callMethod(fragment, "getView");
            if (!(viewObj instanceof View)) {
                return;
            }
            View root = (View) viewObj;
            for (String name : HIDDEN_ID_NAMES) {
                int id = resolveId(name);
                if (id == 0) {
                    continue;
                }
                View target = root.findViewById(id);
                if (target != null && target.getVisibility() != View.GONE) {
                    target.setVisibility(View.GONE);
                    XposedInit.log(name + " hidden by fragment hook");
                }
            }
        } catch (Throwable t) {
            XposedInit.log("hidePromoViews failed: " + t);
        }
    }

    private static boolean isHiddenId(int id) {
        for (String name : HIDDEN_ID_NAMES) {
            int target = resolveId(name);
            if (target != 0 && target == id) {
                return true;
            }
        }
        return false;
    }

    /** 运行时解析资源 id（成功后缓存；Application 未就绪时下次重试）。 */
    private static int resolveId(String name) {
        Integer cached = sIdCache.get(name);
        if (cached != null && cached != 0) {
            return cached;
        }
        synchronized (VipBannerHook.class) {
            cached = sIdCache.get(name);
            if (cached != null && cached != 0) {
                return cached;
            }
            try {
                android.app.Application app = AndroidAppHelper.currentApplication();
                if (app != null) {
                    int rid = app.getResources().getIdentifier(name, "id", XposedInit.TARGET_PKG);
                    if (rid != 0) {
                        sIdCache.put(name, rid);
                        XposedInit.log("resolved " + name + " id = 0x" + Integer.toHexString(rid));
                        XposedInit.logTargetVersionOnce();
                    }
                }
            } catch (Throwable t) {
                XposedInit.log("resolveId(" + name + ") failed: " + t);
            }
        }
        return 0;
    }
}
