package com.tbulu.adfree;

import android.app.AndroidAppHelper;
import android.content.pm.PackageInfo;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * LSPosed / EdXposed 模块入口。
 *
 * 目标：com.lolaage.tbulu.tools（两步路）v9.2.8 (versionCode 1652)
 * 作用：隐藏信息流/列表/详情页的广告位、"我的"页的会员促销横幅；
 *       v1.6 起新增"会员本地门控解锁"（DIY首页/图片水印/头像挂件/专属样式/个性化分享）。
 *       不伪造账号 VIP 状态、不拦截网络请求、不动购买流程（详见各 Hook 类注释）。
 */
public class XposedInit implements IXposedHookLoadPackage {

    public static final String TAG = "TbuluAdFree";
    public static final String TARGET_PKG = "com.lolaage.tbulu.tools";
    /** 9.2.8 => versionCode 1652；仅作提示，不做硬性拦截。 */
    public static final int TARGET_VERSION_CODE = 1652;

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        if (!TARGET_PKG.equals(lpparam.packageName)) {
            return;
        }
        // 仅在主进程安装 Hook（避免 :push / :downloader 等广告 SDK 进程重复安装）
        if (lpparam.processName != null && lpparam.processName.contains(":")) {
            log("skip secondary process: " + lpparam.processName);
            return;
        }

        log("loaded target: " + lpparam.packageName + " process=" + lpparam.processName);

        try {
            VipBannerHook.install(lpparam.classLoader);
        } catch (Throwable t) {
            log("VipBannerHook install failed: " + t);
        }
        try {
            VipGateUnlockHook.install(lpparam.classLoader);
        } catch (Throwable t) {
            log("VipGateUnlockHook install failed: " + t);
        }
        try {
            DockBadgeHook.install(lpparam.classLoader);
        } catch (Throwable t) {
            log("DockBadgeHook install failed: " + t);
        }
        try {
            EquipmentTabHook.install(lpparam.classLoader);
        } catch (Throwable t) {
            log("EquipmentTabHook install failed: " + t);
        }
        try {
            UpgradeDialogHook.install(lpparam.classLoader);
        } catch (Throwable t) {
            log("UpgradeDialogHook install failed: " + t);
        }
        try {
            AdsHook.install(lpparam.classLoader);
        } catch (Throwable t) {
            log("AdsHook install failed: " + t);
        }
        try {
            ShareCardCleanHook.install(lpparam.classLoader);
        } catch (Throwable t) {
            log("ShareCardCleanHook install failed: " + t);
        }
        try {
            ShareCardCustomTextHook.install(lpparam.classLoader);
        } catch (Throwable t) {
            log("ShareCardCustomTextHook install failed: " + t);
        }

        // 预读功能开关：每个进程只读一次；开关修改后需重启（强制停止）目标应用才生效
        try {
            Prefs.prime();
        } catch (Throwable t) {
            log("Prefs prime failed: " + t);
        }
    }

    public static void log(String msg) {
        XposedBridge.log(TAG + ": " + msg);
    }

    /** 延迟输出目标 App 版本（handleLoadPackage 时 Application 可能尚未创建）。 */
    private static volatile boolean sVersionLogged = false;

    public static void logTargetVersionOnce() {
        if (sVersionLogged) {
            return;
        }
        sVersionLogged = true;
        try {
            android.content.Context ctx = AndroidAppHelper.currentApplication();
            if (ctx == null) {
                return;
            }
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(TARGET_PKG, 0);
            log("target app version: " + pi.versionName + " (" + pi.versionCode + "), expected "
                    + TARGET_VERSION_CODE);
        } catch (Throwable ignored) {
        }
    }
}
