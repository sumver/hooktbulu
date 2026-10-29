package com.tbulu.adfree;

import android.app.AndroidAppHelper;
import android.app.Application;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
 *    （普通开关开启，「隐藏坚持运动天数」「隐藏消息未读数」默认关闭），不阻塞两步路启动。
 *  - 该通道是标准 Android IPC，不依赖任何 LSPosed 私有机制：
 *    LSPosed 的 New XSharedPreferences 已被官方标记废弃（计划 2.3.0 移除），故弃用；
 *    1.9.x / 2.x 及以后版本通用。
 *  - 读取策略：**每个进程只加载一次**。因此开关修改后必须重启（强制停止）两步路才生效
 *    —— 刻意设计，避免运行中状态错乱。
 */
final class Prefs {

    /** 与 app/build.gradle 的 applicationId 保持一致（不依赖 BuildConfig 的开关）。 */
    static final String MODULE_PKG = "com.sumver.tbulu";
    /** 被 Hook 的目标应用包名（供模块 App 进程校验写入来源，避免直接引用 Xposed API）。 */
    static final String TARGET_PKG = "com.lolaage.tbulu.tools";
    /** 设置界面与 Hook 共用同一偏好文件名。 */
    static final String FILE = "settings";

    /**
     * 并入单个 APK（LSPatch 本地模式等：模块与目标同进程、无独立模块 App）后，
     * 配置落在**宿主应用自己**的偏好文件；独立文件名以避免与宿主同名文件冲突。
     */
    static final String HOST_FILE = "tbulu_adfree_settings";

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
    /**
     * 「我的」页右上角消息红点（未读数角标；消息入口保留，仍可点击进消息中心）。默认关闭。
     * key 末尾带 2：v1.22 测试期该开关曾默认开启，存量配置里已被写成 true；
     * 换 key 让旧值作废，保证"默认关闭"对新装/已装用户都生效（想开启在设置界面手动打开）。
     */
    static final String K_MY_MESSAGE_BADGE = "my_message_badge2";
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
    /** 会员权益：轨迹叠加上限（非会员 10 条，解锁按会员上限 100 条）。 */
    static final String K_VIP_OVERLAY_TRACKS = "vip_overlay_tracks";
    /** 会员权益：轨迹列表「合并/导出」的批量导出、批量分享（非会员弹开通引导）。 */
    static final String K_VIP_BULK_EXPORT_SHARE = "vip_bulk_export_share";
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
                K_MY_MESSAGE_BADGE,
                K_UPGRADE,
                K_EQUIPMENT,
                K_VIP_WATERMARK,
                K_VIP_DIY_HOME,
                K_VIP_PENDANT,
                K_VIP_STYLE,
                K_VIP_SHARE,
                K_VIP_OVERLAY_TRACKS,
                K_VIP_BULK_EXPORT_SHARE,
                K_SHARE_CLEAN,
                K_SHARE_CUSTOM,
                K_SHARE_HIDE_DAYS,
                K_SHARE_LOGO,
        };
    }

    /** 未配置时的默认值：仅下列 key 默认关闭，其余全部默认开启。 */
    private static final Set<String> DEFAULT_OFF = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(K_SHARE_HIDE_DAYS, K_MY_MESSAGE_BADGE)));

    /** 开关的默认值（设置界面写入默认值、Hook 侧读取回退共用）。 */
    static boolean defaultOf(String key) {
        return !DEFAULT_OFF.contains(key);
    }

    /** Hook 进程的配置快照；null = 尚未加载完成（此时按默认值行事）。 */
    private static volatile Map<String, Boolean> sSnapshot;

    /**
     * 本进程内被就地改写的开关（来源：两步路内嵌的「模块设置」弹窗，见 {@link SidebarSettingsHook}）。
     * 优先级高于 {@link #sSnapshot}：同一进程的 Hook 读取时立即生效、无需重启；
     * 同时会持久化到配置源（独立模块 App → PrefsProvider；并入单 APK 后 → 本地偏好），
     * 因此下一次启动仍然保留。
     */
    private static final Map<String, Boolean> sOverrides = new ConcurrentHashMap<>();

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
     * 宿主应用本地配置（并入单 APK / LSPatch 本地模式的持久化位置）。
     * 模块 App 进程不使用它（模块 App 用 {@link #ui}）。
     */
    static SharedPreferences hostPrefs(Context context) {
        return context.getSharedPreferences(HOST_FILE, Context.MODE_PRIVATE);
    }

    /**
     * Hook 进程使用：开关是否开启（快照未就绪 / 读取失败 = 内置默认值）。
     * 不阻塞：首次调用会发起异步加载，快照就绪后各次调用即返回真实配置。
     */
    static boolean isEnabled(String key) {
        Boolean override = sOverrides.get(key);
        if (override != null) {
            return override;
        }
        Map<String, Boolean> snapshot = sSnapshot;
        if (snapshot == null) {
            ensureLoadStarted();
            return defaultOf(key);
        }
        Boolean value = snapshot.get(key);
        return value == null ? defaultOf(key) : value;
    }

    /**
     * 就地修改开关（供两步路内嵌的「模块设置」弹窗使用）：
     *  1) 同步更新本进程内存值 → 同进程的 Hook 立即按新值行事（无需重启）；
     *  2) 后台线程持久化到配置源 → 下次启动仍然保留：
     *     - 独立模块 App 形态：经 {@link PrefsProvider} 写入模块 App 的偏好文件；
     *     - 并入单个 APK（LSPatch 本地模式等，无独立模块 App）：写宿主应用本地 {@link #HOST_FILE}。
     *
     * 持久化放后台线程，避免跨进程写入在 UI 线程阻塞（首次写入会拉起模块 App 进程）。
     */
    static void setEnabled(Context context, String key, boolean value) {
        sOverrides.put(key, value);
        if (context == null) {
            return;
        }
        final Context appContext = context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
        final String fKey = key;
        final boolean fValue = value;
        Thread writer = new Thread(new Runnable() {
            @Override
            public void run() {
                persist(appContext, fKey, fValue);
            }
        }, "TbuluAdFree-PrefsWrite");
        writer.setDaemon(true);
        writer.start();
    }

    /** 持久化单个开关到配置源（后台线程调用）。 */
    private static void persist(Context context, String key, boolean value) {
        try {
            if (MODULE_PKG.equals(context.getPackageName())) {
                ui(context).edit().putBoolean(key, value).apply();
                XposedInit.log("Prefs: " + key + " = " + value + " persisted (local)");
                return;
            }
            if (!isModuleAppInstalled(context)) {
                // 并入单 APK / LSPatch 本地模式：无独立模块 App，直接写宿主本地
                hostPrefs(context).edit().putBoolean(key, value).apply();
                XposedInit.log("Prefs: " + key + " = " + value + " persisted (host-local)");
                return;
            }
            ContentValues values = new ContentValues();
            values.put(COLUMN_KEY, key);
            values.put(COLUMN_VALUE, value ? 1 : 0);
            int rows = context.getContentResolver().update(PROVIDER_URI, values, null, null);
            if (rows > 0) {
                XposedInit.log("Prefs: " + key + " = " + value + " persisted (rows=" + rows + ")");
                return;
            }
            // provider 写不进去（并入单 APK / LSPatch 本地模式：无独立模块 App 进程）
            hostPrefs(context).edit().putBoolean(key, value).apply();
            XposedInit.log("Prefs: " + key + " = " + value + " persisted (host-local; provider unavailable)");
        } catch (Throwable t) {
            // provider 不可达等：兜底写宿主本地，保证重启后仍保留
            try {
                hostPrefs(context).edit().putBoolean(key, value).apply();
                XposedInit.log("Prefs: " + key + " = " + value
                        + " persisted (host-local fallback after error)");
            } catch (Throwable t2) {
                XposedInit.log("Prefs: persist failed for " + key + "=" + value + ": " + t2);
            }
        }
    }

    /** key 是否属于已知开关（用于 Provider 写入口的合法性校验）。 */
    static boolean isKnownKey(String key) {
        if (key == null) {
            return false;
        }
        for (String known : allKeys()) {
            if (known.equals(key)) {
                return true;
            }
        }
        return false;
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
     * provider 不可用时（例如并入单个 APK / LSPatch 本地模式：没有独立模块 App 进程），
     * 回退读取**宿主应用本地**的 {@link #HOST_FILE}；仍无配置则用内置默认值。
     */
    private static void loadFromProvider() {
        Application lastApp = null;
        // 先判断是否存在独立模块 App：不存在（并入单 APK / LSPatch 本地模式）则直接读宿主本地，
        // 避免在无 provider 的环境里白白重试、拖慢配置就绪。
        Application app0 = waitForApplication();
        if (app0 != null && !isModuleAppInstalled(app0)) {
            Map<String, Boolean> local = readHostPrefs(app0);
            sSnapshot = local;
            XposedInit.log("Prefs: module app absent; loaded " + local.size()
                    + " switches via host-local prefs (merged single-APK mode)");
            return;
        }
        lastApp = app0;

        for (int attempt = 1; attempt <= MAX_LOAD_ATTEMPTS; attempt++) {
            Application app = waitForApplication();
            if (app == null) {
                // Application 尚未创建（进程启动很早）：稍等再试
                sleepQuietly(400L);
                continue;
            }
            lastApp = app;
            Map<String, Boolean> loaded = queryProvider(app);
            if (loaded != null) {
                sSnapshot = loaded;
                XposedInit.log("Prefs: loaded " + loaded.size()
                        + " switches via PrefsProvider (read once per process; restart target app to apply)");
                return;
            }
            sleepQuietly(300L * attempt);
        }

        // provider 不可用：尝试宿主应用本地配置（并入单 APK / LSPatch 本地模式的场景）
        Map<String, Boolean> local = lastApp == null ? null : readHostPrefs(lastApp);
        if (local != null && !local.isEmpty()) {
            sSnapshot = local;
            XposedInit.log("Prefs: loaded " + local.size()
                    + " switches via host-local prefs (no module provider; merged single-APK mode)");
            return;
        }

        sSnapshot = Collections.emptyMap();
        XposedInit.log("Prefs: provider unavailable after " + MAX_LOAD_ATTEMPTS
                + " attempts; 回退内置默认值（普通开关开启，「隐藏坚持运动天数」「隐藏消息未读数」默认关闭）。"
                + "请确认模块 App 已安装，并打开过一次模块设置界面");
    }

    /** 读取宿主应用本地配置（并入单 APK 场景；只认已知 switch key）。 */
    private static Map<String, Boolean> readHostPrefs(Context context) {
        Map<String, Boolean> out = new HashMap<>();
        try {
            for (Map.Entry<String, ?> entry : hostPrefs(context).getAll().entrySet()) {
                Object value = entry.getValue();
                if (value instanceof Boolean && isKnownKey(entry.getKey())) {
                    out.put(entry.getKey(), (Boolean) value);
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 是否存在独立的模块 App（并入单 APK / LSPatch 本地模式时不存在）。 */
    private static boolean isModuleAppInstalled(Context context) {
        try {
            context.getPackageManager().getPackageInfo(MODULE_PKG, 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
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
