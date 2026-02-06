package com.sumver.tbulu;

import android.app.Activity;
import android.content.Intent;
import android.util.Log;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;


public class hackvip implements IXposedHookLoadPackage {

    private static final String TAG = "TbuluBlock";
    private static final String TARGET_PACKAGE = "com.lolaage.tbulu.tools";

    // 类名
    private static final String WELCOME_ACTIVITY = "com.lolaage.tbulu.tools.ui.activity.WelcomeActivity";
    private static final String MAIN_ACTIVITY = "com.lolaage.tbulu.tools.ui.activity.main.MainActivity";
    private static final String BASE_ACTIVITY = "com.lolaage.tbulu.tools.ui.activity.common.BaseActivity";
    private static final String UPGRADE_DIALOG_CLASS = "com.lolaage.tbulu.tools.upgrade.UpgradeInfoDialog";

    // 状态标志
    private static volatile boolean sHasSkippedAd = false;
    private static volatile boolean sHasHookedMall = false;
    private static volatile boolean sHasHookedUpdate = false; // 升级弹窗

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!lpparam.packageName.equals(TARGET_PACKAGE)) return;


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
                            if (!sHasHookedMall) {
                                try {
                                    XposedHelpers.findAndHookMethod(
                                            activity.getClass(),
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
                                    sHasHookedMall = true;
                                    Log.i(TAG, "成功 Hook 商城拦截: " + className);
                                } catch (Exception ignored) {
                                    Log.w(TAG, "拦截商城跳转失败！");
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
    }
}