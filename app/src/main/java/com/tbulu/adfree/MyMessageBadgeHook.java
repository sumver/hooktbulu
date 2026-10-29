package com.tbulu.adfree;

import android.app.AndroidAppHelper;
import android.app.Application;
import android.view.View;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 屏蔽「我的」页右上角消息红点（未读数角标），保留消息入口可点击。
 *
 * 调用链（两步路 9.2.8）：
 *   SetUpFragment（ui.fragment.main.o0000）#o0000O0()
 *     → BoltsUtil.excuteInBackground(Callable, Continuation)
 *       ├─ Callable o0000$OooOOOO#OooO00o()：未读数 = NoticeMessageUtil.getMessageNum()
 *       │                                    + 已登录时 ChatMessageDB.getUnreadMessageNum()
 *       └─ Continuation o0000$OooOo#then(...)：未读数 > 0 → tvMessageNum（红点 TextView）
 *          置 VISIBLE 并显示数字（>99 显示 "99+"）；否则置 GONE。
 *   该回调是红点唯一的显示入口（EventMessageUpdate 等事件、登录/返回刷新均汇聚到 o0000O0()）；
 *   红点 TextView 被 Fragment 缓存在字段 OooOo00。
 *
 * 处理原则：只拦"显示红点"这条链路，不改动未读消息数据本身；
 * 消息入口 llMyMessages 不做处理，点击仍进入 MyMessageListActivity（消息中心），
 * 消息中心 / 聊天列表里的未读状态不受影响。
 *
 * 开关：受设置界面「隐藏消息未读数」({@link Prefs#K_MY_MESSAGE_BADGE}) 控制，**默认关闭**（需手动开启）。
 *
 * 两层保险（任一层生效即可）：
 *   1) o0000$OooOo.then：数据刷新回调执行后，把红点强制置 GONE；
 *   2) Fragment 生命周期（OooOo0 / onResume / onHiddenChanged）：
 *      按资源 id 兜底查找红点并置 GONE，覆盖进入/返回"我的"页等场景。
 */
final class MyMessageBadgeHook {

    /** Continuation 持有的 Fragment 字段名。 */
    private static final String CONTINUATION_FRAGMENT_FIELD = "OooO00o";
    /** Fragment 缓存的红点 TextView 字段名（App 更新红点的同一实例）。 */
    private static final String DOT_FIELD = "OooOo00";
    /** 红点控件资源名（9.2.8 全工程仅 fragment_setup.xml 使用）。 */
    private static final String DOT_ID_NAME = "tvMessageNum";

    /** 红点资源 id 缓存：0 = 尚未解析成功（下次重试）。 */
    private static volatile int sDotId;
    /** "已隐藏"日志只输出一次（避免随消息刷新刷屏）。 */
    private static volatile boolean sHideLogged;

    private MyMessageBadgeHook() {
    }

    /** 设置开关：隐藏「我的」页消息红点（默认开启）。 */
    private static boolean enabled() {
        return Prefs.isEnabled(Prefs.K_MY_MESSAGE_BADGE);
    }

    static void install(ClassLoader cl, TargetProfile profile) {
        // 1) 红点展示回调：未读数刷新后强制隐藏
        Class<?> continuation = findClassAny(cl, profile.msgBadgeContinuationNames);
        if (continuation != null) {
            hookAllSafe(continuation, "then", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!enabled()) {
                        return;
                    }
                    try {
                        Object fragment = XposedHelpers.getObjectField(
                                param.thisObject, CONTINUATION_FRAGMENT_FIELD);
                        hideDot(fragment);
                    } catch (Throwable t) {
                        XposedInit.log("MyMessageBadgeHook: then() hide failed: " + t);
                    }
                }
            });
        }

        // 2) 生命周期兜底：进入/返回"我的"页时确保红点隐藏
        for (String fragmentName : profile.fragmentNames) {
            final Class<?> fragment;
            try {
                fragment = XposedHelpers.findClass(fragmentName, cl);
            } catch (Throwable ignored) {
                continue;
            }
            for (String method : new String[]{"OooOo0", "onResume", "onHiddenChanged"}) {
                hookAllSafe(fragment, method, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        hideDot(param.thisObject);
                    }
                });
            }
        }

        XposedInit.log("MyMessageBadgeHook installed.");
    }

    /** 把红点 TextView 置 GONE（优先取 Fragment 缓存字段，失败时按资源 id 兜底查找）。 */
    private static void hideDot(Object fragment) {
        if (!enabled() || fragment == null) {
            return;
        }
        try {
            Object dot = XposedHelpers.getObjectField(fragment, DOT_FIELD);
            if (dot instanceof View) {
                hide((View) dot);
                return;
            }
        } catch (Throwable ignored) {
            // 字段被混淆改名时走下面的 id 兜底
        }
        try {
            Object viewObj = XposedHelpers.callMethod(fragment, "getView");
            if (!(viewObj instanceof View)) {
                return;
            }
            int id = resolveDotId();
            if (id != 0) {
                hide(((View) viewObj).findViewById(id));
            }
        } catch (Throwable t) {
            XposedInit.log("MyMessageBadgeHook: hideDot failed: " + t);
        }
    }

    private static void hide(View dot) {
        if (dot == null || dot.getVisibility() == View.GONE) {
            return;
        }
        dot.setVisibility(View.GONE);
        if (!sHideLogged) {
            sHideLogged = true;
            XposedInit.log("MyMessageBadgeHook: message red dot hidden");
        }
    }

    /** 运行时解析红点资源 id（成功后缓存；Application 未就绪时下次重试）。 */
    private static int resolveDotId() {
        int id = sDotId;
        if (id != 0) {
            return id;
        }
        synchronized (MyMessageBadgeHook.class) {
            id = sDotId;
            if (id != 0) {
                return id;
            }
            try {
                Application app = AndroidAppHelper.currentApplication();
                if (app != null) {
                    id = app.getResources().getIdentifier(DOT_ID_NAME, "id", XposedInit.TARGET_PKG);
                    if (id != 0) {
                        sDotId = id;
                        XposedInit.log("MyMessageBadgeHook: resolved " + DOT_ID_NAME
                                + " id = 0x" + Integer.toHexString(id));
                    }
                }
            } catch (Throwable t) {
                XposedInit.log("MyMessageBadgeHook: resolveDotId failed: " + t);
            }
            return id;
        }
    }

    private static Class<?> findClassSafe(ClassLoader cl, String name) {
        try {
            return XposedHelpers.findClass(name, cl);
        } catch (Throwable t) {
            XposedInit.log("MyMessageBadgeHook: class not found (skip): " + name);
            return null;
        }
    }

    /** 按候选顺序解析类，全部 miss 返回 null（不打印每个 miss，避免刷屏）。 */
    private static Class<?> findClassAny(ClassLoader cl, String... names) {
        for (String name : names) {
            try {
                return XposedHelpers.findClass(name, cl);
            } catch (Throwable ignored) {
            }
        }
        XposedInit.log("MyMessageBadgeHook: continuation class not found: "
                + String.join("/", names));
        return null;
    }

    private static void hookAllSafe(Class<?> cls, String method, XC_MethodHook cb) {
        try {
            XposedBridge.hookAllMethods(cls, method, cb);
            XposedInit.log("hooked " + cls.getSimpleName() + "." + method);
        } catch (Throwable t) {
            XposedInit.log("MyMessageBadgeHook hook failed: " + cls.getSimpleName() + "." + method + " " + t);
        }
    }
}
