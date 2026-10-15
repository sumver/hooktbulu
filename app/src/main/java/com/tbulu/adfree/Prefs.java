package com.tbulu.adfree;

import android.app.AndroidAppHelper;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 功能开关配置（模块设置界面 ↔ Hook 进程的桥梁）。
 *
 * 通道协议（v1.21 起，替代 LSPosed 已废弃的「New XSharedPreferences」）：
 *  - 模块 App 进程：设置界面以 {@link Context#MODE_PRIVATE} 正常读写偏好文件（settings），
 *    并通过只读 ContentProvider（{@link PrefsProvider}，authority 见 {@link #PROVIDER_AUTHORITY}）
 *    把 Boolean 开关暴露给外部查询；
 *  - Hook（两步路）进程：启动后**异步**经 ContentResolver 查询
 *    content://com.sumver.tbulu.prefs/settings（首次查询会拉起模块 App 进程），
 *    成功后把整份配置缓存到进程内存；查询期间 / 查询失败时按内置默认值行事
 *    （普通开关开启，仅「隐藏坚持运动天数」默认关闭），不阻塞两步路启动。
 *  - 该通道是标准 Android IPC，不依赖任何 LSPosed 私有机制：
 *    LSPosed 的 New XSharedPreferences 已被官方标记废弃（计划 2.3.0 移除），故弃用；
 *    1.9.x / 2.x 及以后版本通用。
 *  - 读取策略：**每个进程只加载一次**。因此开关修改后必须重启（强制停止）两步路才生效
 *    —— 刻意设计，避免运行中状态错乱。
 */
final class Prefs {

    /** 与 app/build.gradle 的 applicationId 保持一致（不依赖 BuildConfig 的开关）。 */
    static final String MODULE_PKG = "com.sumver.tbulu";
    /** 设置界面与 Hook 共用同一偏好文件名。 */
    static final String FILE = "settings";

    /**
     * 配置桥（只读 ContentProvider）authority：= applicationId + ".prefs"。
     * 必须与 AndroidManifest.xml 中 provider 的 android:authorities 完全一致。
     */
    static final String PROVIDER_AUTHORITY = MODULE_PKG + ".prefs";
    /** Hook 进程查询用的 URI。 */
    static final Uri PROVIDER_URI = Uri.parse("content://" + PROVIDER_AUTHORITY + "/" + FILE);
    /** 游标列名（与 {@link PrefsProvider} 约定）。 */
    static final String COLUMN_KEY = "key";
    static final String COLUMN_VALUE = "value";

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

    /** Hook 进程的配置快照；null = 尚未加载完成（此时按默认值行事）。 */
    private static volatile Map<String, Boolean> sSnapshot;

    private static final Object sLoadLock = new Object();
    /** 是否已发起加载（每个进程只发起一轮；失败会固定为空快照，不再重试）。 */
    private static boolean sLoadStarted;
    /** provider 错误日志只输出一次（避免刷屏）。 */
    private static boolean sProviderErrorLogged;

    /** 等待目标应用 Application 创建的上限与轮询间隔（毫秒）。 */
    private static final long CONTEXT_WAIT_MS = 1500L;
    private static final long CONTEXT_POLL_MS = 50L;
    /** provider 查询的最大尝试次数。 */
    private static final int MAX_LOAD_ATTEMPTS = 3;

    private Prefs() {
    }

    /**
     * 模块 App 进程使用：读写设置文件（MODE_PRIVATE）。
     * 偏好文件即 {@link PrefsProvider} 对外暴露的数据源，无需任何跨进程权限处理。
     */
    static SharedPreferences ui(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /**
     * Hook 进程使用：开关是否开启（快照未就绪 / 读取失败 = 内置默认值）。
     * 不阻塞：首次调用会发起异步加载，快照就绪后各次调用即返回真实配置。
     */
    static boolean isEnabled(String key) {
        Map<String, Boolean> snapshot = sSnapshot;
        if (snapshot == null) {
            ensureLoadStarted();
            return defaultOf(key);
        }
        Boolean value = snapshot.get(key);
        return value == null ? defaultOf(key) : value;
    }

    /** 供 XposedInit 在进程启动时尽早触发配置加载（异步、不阻塞）。 */
    static void prime() {
        ensureLoadStarted();
    }

    /** 幂等：每个进程只发起一轮异步加载。 */
    private static void ensureLoadStarted() {
        synchronized (sLoadLock) {
            if (sLoadStarted || sSnapshot != null) {
                return;
            }
            sLoadStarted = true;
        }
        Thread loader = new Thread(new Runnable() {
            @Override
            public void run() {
                loadFromProvider();
            }
        }, "TbuluAdFree-Prefs");
        loader.setDaemon(true);
        loader.start();
    }

    /**
     * 后台加载：等 Application 就绪 → 查询模块 App 的只读 ContentProvider → 填充快照。
     * 失败会重试有限次；最终失败时固定为空快照（此后一律用内置默认值），并明确记录日志。
     */
    private static void loadFromProvider() {
        for (int attempt = 1; attempt <= MAX_LOAD_ATTEMPTS; attempt++) {
            Application app = waitForApplication();
            if (app == null) {
                // Application 尚未创建（进程启动很早）：稍等再试
                sleepQuietly(400L);
                continue;
            }
            Map<String, Boolean> loaded = queryProvider(app);
            if (loaded != null) {
                sSnapshot = loaded;
                XposedInit.log("Prefs: loaded " + loaded.size()
                        + " switches via PrefsProvider (read once per process; restart target app to apply)");
                return;
            }
            sleepQuietly(300L * attempt);
        }
        sSnapshot = Collections.emptyMap();
        XposedInit.log("Prefs: provider unavailable after " + MAX_LOAD_ATTEMPTS
                + " attempts; 回退内置默认值（普通开关开启，仅「隐藏坚持运动天数」默认关闭）。"
                + "请确认模块 App 已安装，并打开过一次模块设置界面");
    }

    /** 等待目标应用 Application 创建（Hook 进程启动早期可能为 null）。 */
    private static Application waitForApplication() {
        long deadline = System.currentTimeMillis() + CONTEXT_WAIT_MS;
        do {
            try {
                Application app = AndroidAppHelper.currentApplication();
                if (app != null) {
                    return app;
                }
            } catch (Throwable ignored) {
                // ActivityThread 尚未就绪等场景：继续等待
            }
            sleepQuietly(CONTEXT_POLL_MS);
        } while (System.currentTimeMillis() < deadline);
        return null;
    }

    /** 查询模块 App 的只读 ContentProvider；失败返回 null（由调用方决定是否重试）。 */
    private static Map<String, Boolean> queryProvider(Context context) {
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(PROVIDER_URI, null, null, null, null);
            if (cursor == null) {
                logProviderError("query returned null");
                return null;
            }
            int keyIndex = cursor.getColumnIndex(COLUMN_KEY);
            int valueIndex = cursor.getColumnIndex(COLUMN_VALUE);
            if (keyIndex < 0 || valueIndex < 0) {
                logProviderError("unexpected cursor columns");
                return null;
            }
            Map<String, Boolean> loaded = new HashMap<>();
            while (cursor.moveToNext()) {
                String key = cursor.getString(keyIndex);
                if (key != null) {
                    loaded.put(key, cursor.getInt(valueIndex) != 0);
                }
            }
            return loaded;
        } catch (Throwable t) {
            logProviderError(String.valueOf(t));
            return null;
        } finally {
            if (cursor != null) {
                try {
                    cursor.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void logProviderError(String reason) {
        if (sProviderErrorLogged) {
            return;
        }
        sProviderErrorLogged = true;
        XposedInit.log("Prefs: provider query failed: " + reason);
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
