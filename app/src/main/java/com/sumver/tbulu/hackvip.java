package com.sumver.tbulu;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.util.WeakHashMap;

public class hackvip implements IXposedHookLoadPackage {

    private static final String TAG = "TbuluBlock";
    private static final String TARGET_PACKAGE = "com.lolaage.tbulu.tools";

    // 类名
    private static final String WELCOME_ACTIVITY = "com.lolaage.tbulu.tools.ui.activity.WelcomeActivity";
    private static final String MAIN_ACTIVITY = "com.lolaage.tbulu.tools.ui.activity.main.MainActivity";
    private static final String BASE_ACTIVITY = "com.lolaage.tbulu.tools.ui.activity.common.BaseActivity";
    private static final String UPGRADE_DIALOG_CLASS = "com.lolaage.tbulu.tools.upgrade.UpgradeInfoDialog";
    // VIP banner
    private static final String SETUP_FRAGMENT_CLASS = "com.lolaage.tbulu.tools.ui.fragment.main.SetUpFragment";

    // 状态标志
    // 广告
    private static volatile boolean sHasSkippedAd = false;
    // 商城TAB
    private static volatile boolean sHasHookedMall = false;
    // 升级弹窗
    private static volatile boolean sHasHookedUpdate = false;
    // vip banner 十进制id
    private static final int RL_VIP_RESOURCE_ID = 2131366304;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!lpparam.packageName.equals(TARGET_PACKAGE)) return;

        Log.d(TAG, "开始 Hook 包: " + TARGET_PACKAGE);

        // 启动页广告+屏蔽商品跳转+屏蔽升级弹窗 放在类初始化之前（performLaunchActivity），因为测试发现oncreate里拦不住，所以拦截时机需要提前
        // 另外activity.getClass()是JVM自带的，看起来遍历所有类好像很消耗性能，但实际上是通过指针寻找，不会造成性能问题
        Class<?> activityClientRecordClass = XposedHelpers.findClass(
                "android.app.ActivityThread$ActivityClientRecord",
                lpparam.classLoader
        );

        XposedHelpers.findAndHookMethod(
                "android.app.ActivityThread",
                lpparam.classLoader,
                "performLaunchActivity",
                activityClientRecordClass,
                Intent.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Object record = param.args[0];
                            Activity activity = (Activity) XposedHelpers.getObjectField(record, "activity");
                            if (activity == null || activity.isFinishing()) return;

                            String className = activity.getClass().getName();
                            Log.d(TAG, "Launched Activity: " + className);

                            // 跳过开屏广告
                            if (!sHasSkippedAd && WELCOME_ACTIVITY.equals(className)) {
                                try {
                                    Class<?> mainClazz = XposedHelpers.findClass(MAIN_ACTIVITY, lpparam.classLoader);
                                    Intent intent = new Intent(activity, mainClazz);
                                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                                    activity.startActivity(intent);
                                    activity.finish();
                                    sHasSkippedAd = true;
                                    Log.i(TAG, "成功跳过开屏广告");
                                } catch (Throwable t) {
                                    Log.e(TAG, "跳过广告失败", t);
                                }
                            }

                            // 阻止商城TAB跳转到微信小程序
                            // 只有在尚未 Hook 且当前 Activity 是 MainActivity 时才尝试 Hook
                            if (!sHasHookedMall && MAIN_ACTIVITY.equals(className)) {
                                try {
                                    XposedHelpers.findAndHookMethod(
                                            activity.getClass(), // 关键：使用当前实例的类
                                            "changeTab",
                                            int.class,
                                            new XC_MethodHook() {
                                                @Override
                                                protected void beforeHookedMethod(MethodHookParam hookParam) {
                                                    int index = (int) hookParam.args[0];
                                                    if (index == 3) {
                                                        Log.w(TAG, "拦截商城 Tab (index=3)");
                                                        hookParam.setResult(null);
                                                    }
                                                }
                                            }
                                    );
                                    sHasHookedMall = true; // 设置标志位，只 Hook 一次
                                    Log.i(TAG, "成功 Hook 商城拦截: " + className);
                                } catch (Throwable t) { // 重要：捕获 Throwable，包括 NoSuchMethodError
                                    Log.e(TAG, "拦截商城跳转失败！尝试 Hook " + className + ".changeTab 失败", t);
                                    // 不设置 sHasHookedMall = true;，以便下次遇到 MainActivity 时重试，这里先预留
                                    // 或者，如果确定方法就在那里，可以设置标志位防止无限尝试
                                    // sHasHookedMall = true;
                                }
                            }


                            // 阻止升级弹窗
                            if (!sHasHookedUpdate) {
                                try {
                                    Class<?> upgradeManagerClass = XposedHelpers.findClass(
                                            "com.lolaage.tbulu.tools.upgrade.OooO0o",
                                            lpparam.classLoader
                                    );

                                    // Hook: private void OooO0Oo(BaseActivity)
                                    XposedHelpers.findAndHookMethod(
                                            upgradeManagerClass,
                                            "OooO0Oo",
                                            XposedHelpers.findClass(BASE_ACTIVITY, lpparam.classLoader),
                                            new XC_MethodHook() {
                                                @Override
                                                protected void beforeHookedMethod(MethodHookParam param) {
                                                    Log.w(TAG, "拦截升级弹窗触发: OooO0o.OooO0Oo(BaseActivity)");
                                                    param.setResult(null); // 直接返回，不执行后续逻辑
                                                }
                                            }
                                    );
                                    sHasHookedUpdate = true;
                                    Log.i(TAG, "成功 Hook 升级弹窗触发方法");
                                } catch (Throwable t) {
                                    Log.e(TAG, "Hook OooO0o.OooO0Oo 失败", t);
                                }
                            }


                        } catch (Throwable t) {
                            Log.e(TAG, "performLaunchActivity 异常", t);
                        }
                    }
                }
        );

        // hook vip banner，不使用classLoader hook是因为加载时机不对总是hook不到，所以改为findViewById，通配所有的view,再从view中实时找到目标ID
        final WeakHashMap<View, Boolean> hookedViews = new WeakHashMap<>();
        XposedHelpers.findAndHookMethod(
                View.class,
                "findViewById",
                int.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        int requestedId = (int) param.args[0];
                        View foundView = (View) param.getResult();

                        if (requestedId == RL_VIP_RESOURCE_ID && foundView != null && !hookedViews.containsKey(foundView)) {
                            Log.d(TAG, "Vip Banner 拦截：rlVip (ID: " + RL_VIP_RESOURCE_ID + "). Type: " + foundView.getClass().getName() + ". Initial Vis: " + foundView.getVisibility());

                            // 标记此 View 已被 Hook
                            hookedViews.put(foundView, true);

                            // 立即隐藏。这里先不启用，因为进入我的TAB时，这个vip banner渲染了两次，所以这次设置为隐藏实际是没用的
//                            foundView.setVisibility(View.GONE);
//                            Log.d(TAG, "Vip Banner 修改 rlVip to GONE. New Vis: " + foundView.getVisibility());

                            // Hook View 类的 setVisibility 方法，但只拦截目标 View 实例的调用
                            XposedHelpers.findAndHookMethod(
                                    View.class, // Hook View 父类
                                    "setVisibility",
                                    int.class,
                                    new XC_MethodHook() {
                                        @Override
                                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                            View viewBeingModified = (View) param.thisObject; // 获取调用 setVisibility 的 View 实例

                                            // 检查是否是我们的目标 View
                                            if (viewBeingModified == foundView) {
                                                int visibility = (int) param.args[0];
                                                if (visibility == View.VISIBLE) {
                                                    Log.w(TAG, "VIP Banner拦截成功 rlVip (" + viewBeingModified.hashCode() + ") from being set to VISIBLE! (Attempted Vis: " + visibility + ")");
                                                    param.args[0] = View.GONE;
                                                } else {
                                                    Log.d(TAG, "VIP Banner拦截成功失败 rlVip (" + viewBeingModified.hashCode() + ") to be set to: " + visibility);
                                                }
                                            }
                                        }
                                    }
                            );
                        }
                    }
                }
        );


        Log.d(TAG, "完成 Hook 包: " + TARGET_PACKAGE);
    }


}