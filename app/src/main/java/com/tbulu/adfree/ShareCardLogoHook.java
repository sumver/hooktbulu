package com.tbulu.adfree;

import android.view.View;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 隐藏分享卡右上角「两步路」水印（v1.18 新增，独立开关「分享卡净化」分组第四项）。
 *
 * 背景（JADX 反编译 + apktool 资源核对，两步路 9.2.8 / versionCode 1652）：
 *   「打卡/累计」分享卡布局 view_sport_punch_grand_total_share.xml 右上角的
 *   ImageView `ivLogo`（@mipmap/share_logo_watermarking_white：两步路 logo 图标 + “两步路”文字，
 *   白色 PNG），是纯客户端渲染的图片水印，不是服务端烧录。
 *   卡片类内唯一的显隐逻辑在截图流程 OooOOo0(isWatermarking, type) 里：
 *     isWatermarking=false → 截图前隐藏、截图后恢复；App 的分享/保存按钮都传 true
 *     （TrackSportShareActivity：分享 OooOOo0(true,1) / 保存 OooOOo0(true,2)），
 *   因此默认预览与导出图都带该水印。
 *
 * 本 Hook 的行为（仅当开关开启）：
 *   1) setData 执行后：把卡片内 `ivLogo` 置 GONE（覆盖预览显示）；
 *   2) OooOOo0 执行前/后：再次确保 GONE —— 截图前隐藏可保证进入导出图的是无水印版本，
 *      after 挂点兜住 App 的“截图后恢复显示”分支（!isWatermarking 路径）。
 *   其余卡片（链接/图片/进阶/轨迹文件）与登录页的同名控件均不受影响。
 *
 * 开关：Prefs.K_SHARE_LOGO（设置界面「分享卡净化 → 隐藏「两步路」水印」，默认开启；
 *   修改后需重启两步路才生效）。关闭时不做任何处理（保持 App 原样）。
 *
 * 类名/方法名与 9.2.8 混淆结果绑定；App 升级后若失效会自动跳过（仅记录日志），不影响 App 运行。
 */
final class ShareCardLogoHook {

    /** 目标类（9.2.8 运行时类名）。 */
    private static final String TARGET_CLASS =
            "com.lolaage.tbulu.tools.ui.views.SportPunchOrGrandTotalShareView";
    /** 卡片数据装载入口（隐藏预览中的水印）。 */
    private static final String M_SET_DATA = "setData";
    /** 保存/分享的截图流程（含水印显隐逻辑）。 */
    private static final String M_CAPTURE = "OooOOo0";
    /** 右上角水印控件资源名（布局 view_sport_punch_grand_total_share.xml）。 */
    private static final String ID_IV_LOGO = "ivLogo";

    /** ivLogo 的运行时时资源 id（0=未解析/解析失败）。 */
    private static volatile int sIvLogoId;

    /** 已记录过的日志 key（避免刷屏）。 */
    private static final Set<String> sLogged = ConcurrentHashMap.newKeySet();

    private ShareCardLogoHook() {
    }

    static void install(ClassLoader cl) {
        try {
            Class<?> cls = XposedHelpers.findClass(TARGET_CLASS, cl);
            XC_MethodHook hideHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.getThrowable() != null || !Prefs.isEnabled(Prefs.K_SHARE_LOGO)) {
                        return;
                    }
                    Object self = param.thisObject;
                    if (self instanceof View) {
                        hideLogo((View) self);
                    }
                }
            };
            XposedBridge.hookAllMethods(cls, M_SET_DATA, hideHook);
            XposedBridge.hookAllMethods(cls, M_CAPTURE, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!Prefs.isEnabled(Prefs.K_SHARE_LOGO)) {
                        return;
                    }
                    Object self = param.thisObject;
                    if (self instanceof View) {
                        hideLogo((View) self);
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    // 兜住 App 的“截图后恢复显示”分支（!isWatermarking 路径）
                    if (param.getThrowable() != null || !Prefs.isEnabled(Prefs.K_SHARE_LOGO)) {
                        return;
                    }
                    Object self = param.thisObject;
                    if (self instanceof View) {
                        hideLogo((View) self);
                    }
                }
            });
            XposedInit.log("ShareCardLogoHook: hooked " + cls.getSimpleName() + "."
                    + M_SET_DATA + "/" + M_CAPTURE + " (top-right logo watermark)");
        } catch (Throwable t) {
            XposedInit.log("ShareCardLogoHook: hook failed: " + t);
        }
    }

    /** 把卡片右上角「两步路」水印置 GONE（幂等）。 */
    private static void hideLogo(View card) {
        try {
            int id = resolveId(card);
            if (id == 0) {
                return;
            }
            View logo = card.findViewById(id);
            if (logo == null) {
                logOnce("ivLogo view not found, skip");
                return;
            }
            if (logo.getVisibility() != View.GONE) {
                logo.setVisibility(View.GONE);
                logOnce("top-right logo watermark hidden");
            }
        } catch (Throwable t) {
            logOnce("hide ivLogo failed, skip: " + t);
        }
    }

    /** 解析并缓存 ivLogo 的运行时时资源 id。 */
    private static int resolveId(View card) {
        int id = sIvLogoId;
        if (id == 0) {
            id = card.getContext().getResources()
                    .getIdentifier(ID_IV_LOGO, "id", card.getContext().getPackageName());
            if (id == 0) {
                logOnce("ivLogo id not found, skip");
                return 0;
            }
            sIvLogoId = id;
        }
        return id;
    }

    private static void logOnce(String msg) {
        if (sLogged.add(msg)) {
            XposedInit.log("ShareCardLogoHook: " + msg);
        }
    }
}
