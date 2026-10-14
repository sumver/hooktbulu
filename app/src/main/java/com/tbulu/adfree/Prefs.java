package com.tbulu.adfree;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import de.robv.android.xposed.XSharedPreferences;

/**
 * 功能开关配置（模块设置界面 ↔ Hook 进程的桥梁）。
 *
 * 模块 App 进程：普通 {@link SharedPreferences} 读写（{@link #ui}）。
 * Hook（两步路）进程：{@link XSharedPreferences} 只读同一份文件；
 * 以 1 秒为冷却周期，用 {@code hasFileChanged()} 检测变化后 reload；
 * 读取失败时保持上一次快照，且默认值一律为 true —— 保证"从未打开过设置界面"
 * 时行为与加入开关之前完全一致（全部功能开启）。
 */
final class Prefs {

    /** 与 app/build.gradle 的 applicationId 保持一致（不依赖 BuildConfig 的开关）。 */
    static final String MODULE_PKG = "com.sumver.tbulu";
    /** 设置界面与 Hook 共用同一偏好文件名。 */
    static final String FILE = "settings";

    // ---- 开关 key（设置界面 / Hook 共用）----
    /** 隐藏各类广告。 */
    static final String K_ADS = "ads";
    /** "我的"页会员促销横幅（含遗留空档压缩）。 */
    static final String K_VIP_BANNER = "vip_banner";
    /** "我的"页顶部 VIP 客服按钮。 */
    static final String K_VIP_SERVICE = "vip_service";
    /** 底部 dock 栏红点。 */
    static final String K_DOCK_BADGE = "dock_badge";
    /** 升级弹窗（版本详情 + 我的页更新提示）。 */
    static final String K_UPGRADE = "upgrade_dialog";
    /** 装备 tab 防误触。 */
    static final String K_EQUIPMENT = "equipment_confirm";
    /** 会员权益：图片水印。 */
    static final String K_VIP_WATERMARK = "vip_watermark";
    /** 会员权益：DIY首页（地图设为启动界面）。 */
    static final String K_VIP_DIY_HOME = "vip_diy_home";
    /** 会员权益：头像挂件。 */
    static final String K_VIP_PENDANT = "vip_pendant";
    /** 会员权益：专属样式（记录/导航皮肤 + 桌面图标）。 */
    static final String K_VIP_STYLE = "vip_style";
    /** 会员权益：个性化分享（高级数据分享图）。 */
    static final String K_VIP_SHARE = "vip_share";

    /** 全部开关 key（设置界面用于写默认值）。 */
    static String[] allKeys() {
        return new String[]{
                K_ADS,
                K_VIP_BANNER,
                K_VIP_SERVICE,
                K_DOCK_BADGE,
                K_UPGRADE,
                K_EQUIPMENT,
                K_VIP_WATERMARK,
                K_VIP_DIY_HOME,
                K_VIP_PENDANT,
                K_VIP_STYLE,
                K_VIP_SHARE,
        };
    }

    /** Hook 进程读取开关时的最小刷新间隔。 */
    private static final long TTL_MS = 1000L;

    private static volatile XSharedPreferences sXPrefs;
    private static volatile Map<String, Boolean> sSnapshot = Collections.emptyMap();
    private static volatile long sNextRefreshAt;
    private static volatile boolean sLoggedFailure;

    private Prefs() {
    }

    /** 模块 App 进程使用：直接读写设置文件。 */
    static SharedPreferences ui(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** Hook 进程使用：开关是否开启（未配置 / 读取失败 = 开启）。 */
    static boolean isEnabled(String key) {
        refreshIfNeeded();
        Boolean value = sSnapshot.get(key);
        return value == null || value;
    }

    private static void refreshIfNeeded() {
        long now = SystemClock.elapsedRealtime();
        if (now < sNextRefreshAt) {
            return;
        }
        sNextRefreshAt = now + TTL_MS;
        try {
            XSharedPreferences x = sXPrefs;
            if (x == null) {
                x = new XSharedPreferences(MODULE_PKG, FILE);
                sXPrefs = x;
            }
            if (x.hasFileChanged()) {
                x.reload();
                Map<String, Boolean> next = new HashMap<>();
                Map<String, ?> all = x.getAll();
                if (all != null) {
                    for (Map.Entry<String, ?> entry : all.entrySet()) {
                        Object value = entry.getValue();
                        if (value instanceof Boolean) {
                            next.put(entry.getKey(), (Boolean) value);
                        }
                    }
                }
                sSnapshot = next;
            }
        } catch (Throwable t) {
            if (!sLoggedFailure) {
                sLoggedFailure = true;
                XposedInit.log("Prefs: read settings failed (keep defaults/current): " + t);
            }
        }
    }
}
