package com.tbulu.adfree;

import android.app.AndroidAppHelper;
import android.content.pm.PackageInfo;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
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
    /** 9.2.8 => versionCode 1652；9.2.9 => 1656；仅作提示，不做硬性拦截。 */
    public static final int TARGET_VERSION_CODE = 1656;

    /**
     * 9.2.9 起目标 App 使用百度加固（StubApplication + assets/baiduprotect*.dex），
     * 业务类在 handleLoadPackage 阶段不在 lpparam.classLoader 里，直接 findClass 必然 CNFE。
     * 因此 hook 安装改为"探测类可解析后再装"，并在 Application/Activity onCreate 后重试。
     */
    private static final String PROBE_CLASS =
            "com.lolaage.tbulu.tools.ui.views.adv.AdvScrollView";

    private static volatile boolean sHooksInstalled = false;
    private static boolean sLoggedLoaded = false;

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

        if (!sLoggedLoaded) {
            sLoggedLoaded = true;
            log("loaded target: " + lpparam.packageName + " process=" + lpparam.processName);
        }

        // 先触发配置加载（v1.21 起异步、经模块自带只读 ContentProvider 读取，见 Prefs）：
        // 每个进程只加载一次；开关修改后需重启（强制停止）目标应用才生效
        try {
            Prefs.prime();
            DexDumpHook.install(lpparam.classLoader);
        } catch (Throwable t) {
            log("Prefs prime failed: " + t);
        }

        // 无壳场景（9.2.8）：此刻直接安装即可命中
        tryInstallHooks();

        // 有壳场景（9.2.9）：业务类要等壳解密加载后才有，延迟重试
        if (!sHooksInstalled) {
            try {
                XposedHelpers.findAndHookMethod(android.app.Application.class, "onCreate",
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(XC_MethodHook.MethodHookParam param) {
                                tryInstallHooks();
                            }
                        });
                XposedHelpers.findAndHookMethod(android.app.Activity.class, "onCreate",
                        android.os.Bundle.class, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(XC_MethodHook.MethodHookParam param) {
                                tryInstallHooks();
                            }
                        });
                log("packed app detected; hooks deferred until classes load");
            } catch (Throwable t) {
                log("deferred install setup failed: " + t);
            }
        }
    }

    /** 用探测类确认真实 ClassLoader 里已有业务类后，安装全部 hook（幂等）。 */
    private static void tryInstallHooks() {
        if (sHooksInstalled) {
            return;
        }
        // 版本分派：必须拿到 Application 才能可靠读取 versionCode；拿不到就等 onCreate 重试。
        android.app.Application app = resolveApplication();
        if (app == null) {
            return;
        }
        int versionCode;
        try {
            versionCode = app.getPackageManager().getPackageInfo(TARGET_PKG, 0).versionCode;
        } catch (Throwable t) {
            log("read versionCode failed: " + t);
            return;
        }
        TargetProfile profile = TargetProfile.forVersion(versionCode);
        ClassLoader cl = app.getClassLoader();
        if (cl == null) {
            return;
        }
        try {
            Class<?> probe = XposedHelpers.findClassIfExists(PROBE_CLASS, cl);
            if (probe == null) {
                return;
            }
        } catch (Throwable t) {
            log("tryInstallHooks probe failed: " + t);
            return;
        }
        sHooksInstalled = true;
        // ScanHook 为临时运行时类扫描工具，9.2.9 定位完成后不再执行（保留代码备用）。
        // try { ScanHook.scan(cl); } catch (Throwable t) { log("scan failed: " + t); }
        log("probe class resolved, installing hooks with classLoader=" + cl);
        log("target app versionCode=" + versionCode + ", profile=" + profile.describe()
                + (profile.known ? "" : " (unsupported version, best-effort)"));
        installAll(cl, profile);
    }

    /** 取目标进程的 Application（优先 AndroidAppHelper，其次 ActivityThread）。 */
    private static android.app.Application resolveApplication() {
        try {
            android.app.Application app = AndroidAppHelper.currentApplication();
            if (app != null) {
                return app;
            }
        } catch (Throwable ignored) {
        }
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = at.getMethod("currentActivityThread").invoke(null);
            Object app = at.getMethod("getApplication").invoke(thread);
            if (app instanceof android.app.Application) {
                return (android.app.Application) app;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void installAll(ClassLoader cl, TargetProfile profile) {
        try {
            VipBannerHook.install(cl, profile);
        } catch (Throwable t) {
            log("VipBannerHook install failed: " + t);
        }
        try {
            VipGateUnlockHook.install(cl, profile);
        } catch (Throwable t) {
            log("VipGateUnlockHook install failed: " + t);
        }
        try {
            DockBadgeHook.install(cl);
        } catch (Throwable t) {
            log("DockBadgeHook install failed: " + t);
        }
        try {
            MyMessageBadgeHook.install(cl, profile);
        } catch (Throwable t) {
            log("MyMessageBadgeHook install failed: " + t);
        }
        try {
            EquipmentTabHook.install(cl);
        } catch (Throwable t) {
            log("EquipmentTabHook install failed: " + t);
        }
        try {
            UpgradeDialogHook.install(cl);
        } catch (Throwable t) {
            log("UpgradeDialogHook install failed: " + t);
        }
        try {
            AdsHook.install(cl);
        } catch (Throwable t) {
            log("AdsHook install failed: " + t);
        }
        try {
            ShareCardCleanHook.install(cl);
        } catch (Throwable t) {
            log("ShareCardCleanHook install failed: " + t);
        }
        try {
            ShareCardCustomTextHook.install(cl);
        } catch (Throwable t) {
            log("ShareCardCustomTextHook install failed: " + t);
        }
        try {
            ShareCardPunchLayoutHook.install(cl);
        } catch (Throwable t) {
            log("ShareCardPunchLayoutHook install failed: " + t);
        }
        try {
            ShareCardLogoHook.install(cl);
        } catch (Throwable t) {
            log("ShareCardLogoHook install failed: " + t);
        }
        try {
            SidebarSettingsHook.install(cl);
        } catch (Throwable t) {
            log("SidebarSettingsHook install failed: " + t);
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
