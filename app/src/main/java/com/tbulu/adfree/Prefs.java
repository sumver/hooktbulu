package com.tbulu.adfree;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import de.robv.android.xposed.XSharedPreferences;

/**
 * 功能开关配置（模块设置界面 ↔ Hook 进程的桥梁）。
 *
 * 读写协议遵循 LSPosed 官方「New XSharedPreferences」：
 *  - 模块 App 进程：用 {@link Context#MODE_WORLD_READABLE} 打开偏好文件
 *    （LSPosed 会放行该调用并保证 Hook 进程可读；
 *    未在 LSPosed 启用/加载的环境会抛 SecurityException，此时回退 MODE_PRIVATE，仅本进程可读）；
 *  - Hook（两步路）进程：{@link XSharedPreferences} 只读同一文件，
 *    使用前先检查 {@code getFile().canRead()}（官方推荐做法）；
 *  - 读取策略：**每个进程启动后只读一次并缓存**。
 *    因此开关修改后必须重启（强制停止）两步路才会生效 —— 这是刻意设计，避免运行中状态错乱。
 *  - 读不到文件时回退"全部开启"（与旧版本行为一致），并输出明确错误日志便于排查。
 */
final class Prefs {

    /** 与 app/build.gradle 的 applicationId 保持一致（不依赖 BuildConfig 的开关）。 */
    static final String MODULE_PKG = "com.sumver.tbulu";
    /** 设置界面与 Hook 共用同一偏好文件名。 */
    static final String FILE = "settings";

    private static final String LOG_TAG = "TbuluAdFree";

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
    /** 分享卡顶部文案净化（打卡/累计分享图）。 */
    static final String K_SHARE_CLEAN = "share_card_clean";
    /** 自定义分享卡底部文案（长按输入）。 */
    static final String K_SHARE_CUSTOM = "share_card_custom";
    /**
     * 打卡分享卡：隐藏「坚持运动」天数并把「本次运动」移到左侧（v1.20 起默认关闭）。
     * key 末尾带 2：v1.17~v1.19 期间该开关默认开启，存量配置里已被写成 true；
     * 换 key 让旧值作废，保证"默认关闭"对新装/已装用户都生效（想开启在设置界面手动打开）。
     */
    static final String K_SHARE_HIDE_DAYS = "share_card_hide_days2";
    /** 隐藏分享卡右上角「两步路」logo 水印。 */
    static final String K_SHARE_LOGO = "share_card_logo";

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
                K_SHARE_CLEAN,
                K_SHARE_CUSTOM,
                K_SHARE_HIDE_DAYS,
                K_SHARE_LOGO,
        };
    }

    /** 未配置时的默认值：仅下列 key 默认关闭，其余全部默认开启。 */
    private static final Set<String> DEFAULT_OFF =
            Collections.singleton(K_SHARE_HIDE_DAYS);

    /** 开关的默认值（设置界面写入默认值、Hook 侧读取回退共用）。 */
    static boolean defaultOf(String key) {
        return !DEFAULT_OFF.contains(key);
    }

    /** Hook 进程的配置快照；null = 尚未读取（每个进程只读一次）。 */
    private static volatile Map<String, Boolean> sSnapshot;

    private Prefs() {
    }

    /**
     * 模块 App 进程使用：读写设置文件。
     *
     * 必须优先使用 MODE_WORLD_READABLE —— 这是 LSPosed「New XSharedPreferences」
     * 的约定：模块以此模式打开后，LSPosed 会让被 Hook 的应用进程能够读取该文件。
     * 非 LSPosed 环境（或模块未启用）会抛 SecurityException，此时退回 MODE_PRIVATE。
     */
    static SharedPreferences ui(Context context) {
        try {
            return context.getSharedPreferences(FILE, Context.MODE_WORLD_READABLE);
        } catch (SecurityException e) {
            Log.w(LOG_TAG, "MODE_WORLD_READABLE not allowed (module not enabled in LSPosed?), "
                    + "fallback to MODE_PRIVATE; 两步路进程将读不到开关", e);
            return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        }
    }

    /**
     * Hook 进程使用：开关是否开启（未配置 / 读取失败 = 开启）。
     * 每个进程只在首次调用时读取一次；修改开关需重启目标应用。
     */
    static boolean isEnabled(String key) {
        Map<String, Boolean> snapshot = sSnapshot;
        if (snapshot == null) {
            synchronized (Prefs.class) {
                snapshot = sSnapshot;
                if (snapshot == null) {
                    snapshot = readOnce();
                }
            }
        }
        Boolean value = snapshot.get(key);
        return value == null ? defaultOf(key) : value;
    }

    /** 供 XposedInit 在进程启动时预读并输出日志（复用同一份快照，不会产生第二次 I/O）。 */
    static void prime() {
        isEnabled("__prime__");
    }

    /** 读取一次并缓存（仅 Hook 进程调用）。 */
    private static Map<String, Boolean> readOnce() {
        Map<String, Boolean> snapshot = Collections.emptyMap();
        try {
            XSharedPreferences x = new XSharedPreferences(MODULE_PKG, FILE);
            File file = x.getFile();
            if (file == null || !file.canRead()) {
                XposedInit.log("Prefs: settings unreadable ("
                        + (file == null ? "no file" : file.getAbsolutePath())
                        + "); 回退为内置默认值（普通开关开启，仅「隐藏坚持运动天数」默认关闭）。请先打开模块设置界面并切换一次开关（触发 LSPosed 重写权限），"
                        + "再强制停止两步路后重新打开");
            } else {
                x.reload();
                Map<String, ?> all = x.getAll();
                Map<String, Boolean> next = new HashMap<>();
                if (all != null) {
                    for (Map.Entry<String, ?> entry : all.entrySet()) {
                        Object value = entry.getValue();
                        if (value instanceof Boolean) {
                            next.put(entry.getKey(), (Boolean) value);
                        }
                    }
                }
                snapshot = next;
                XposedInit.log("Prefs: loaded " + next.size()
                        + " switches (read once per process; restart target app to apply)");
            }
        } catch (Throwable t) {
            XposedInit.log("Prefs: read failed: " + t);
        }
        sSnapshot = snapshot;
        return snapshot;
    }
}
