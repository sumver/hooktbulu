package com.tbulu.adfree;

/**
 * 两步路「版本档案」：把每个 App 版本里**易变的锚点**（类名/方法名）集中成数据，
 * 由入口 {@link XposedInit} 读取 versionCode 后一次性分派，各功能内部不再判断版本。
 *
 * 设计原则（见 README-技术文档「多版本兼容」）：
 *  - 新增版本 = 新增一份档案 + 注册区间，而不是改所有 Hook；
 *  - 每个版本的候选名只包含该版本真实存在的名字，避免“新名误命中旧版”；
 *  - 区间覆盖热修复版本；未知版本复用最新档案并标记 known=false，锚点解析失败即降级；
 *  - 与版本无关的逻辑（Prefs/日志/弹窗/通用工具）保持共享，不复制。
 */
final class TargetProfile {

    // 已知版本区间（versionCode）
    static final int V928_MIN = 1650;
    static final int V928_MAX = 1655; // 9.2.8（1652）及其热修复
    static final int V929_MIN = 1656;
    static final int V929_MAX = 1665; // 9.2.9（1656）及其热修复

    /** 档案是否对应一个明确支持的版本（false = 未知版本，走最新档案 + 降级）。 */
    final boolean known;
    final int minVersionCode;
    final int maxVersionCode;
    /** 展示用版本号，例如 9.2.8 / 9.2.9。 */
    final String displayVersion;

    /** 会员判断单例（Kotlin object）。 */
    final String[] authSingletonNames;
    /** 轨迹叠加配额外层类。 */
    final String[] overlayQuotaNames;
    /** 叠加确认链路 continuation：$Oooo000 / $o000oOoO。 */
    final String[] overlayBranch1Names;
    final String[] overlayBranch2Names;
    /** 桌面图标点击监听（匿名内部类）。 */
    final String[] appLogoListenerNames;
    /** 挂件选择权限方法（(int)->boolean）。 */
    final String[] pendantPermissionMethods;
    /** “我的”页 Fragment 类（可多个，逐个安装）。 */
    final String[] fragmentNames;
    /** 消息红点 continuation 类。 */
    final String[] msgBadgeContinuationNames;
    /** 批量导出/分享回调方法（参数为 1 个 Integer）。 */
    final String[] bulkExportMethods;

    private TargetProfile(boolean known, int minVersionCode, int maxVersionCode,
                          String displayVersion,
                          String[] authSingletonNames,
                          String[] overlayQuotaNames,
                          String[] overlayBranch1Names,
                          String[] overlayBranch2Names,
                          String[] appLogoListenerNames,
                          String[] pendantPermissionMethods,
                          String[] fragmentNames,
                          String[] msgBadgeContinuationNames,
                          String[] bulkExportMethods) {
        this.known = known;
        this.minVersionCode = minVersionCode;
        this.maxVersionCode = maxVersionCode;
        this.displayVersion = displayVersion;
        this.authSingletonNames = authSingletonNames;
        this.overlayQuotaNames = overlayQuotaNames;
        this.overlayBranch1Names = overlayBranch1Names;
        this.overlayBranch2Names = overlayBranch2Names;
        this.appLogoListenerNames = appLogoListenerNames;
        this.pendantPermissionMethods = pendantPermissionMethods;
        this.fragmentNames = fragmentNames;
        this.msgBadgeContinuationNames = msgBadgeContinuationNames;
        this.bulkExportMethods = bulkExportMethods;
    }

    private TargetProfile asUnknown() {
        return new TargetProfile(false, -1, -1, displayVersion,
                authSingletonNames, overlayQuotaNames, overlayBranch1Names, overlayBranch2Names,
                appLogoListenerNames, pendantPermissionMethods, fragmentNames,
                msgBadgeContinuationNames, bulkExportMethods);
    }

    /** 已知支持版本的展示串，例如 “9.2.8、9.2.9”。 */
    static String supportedVersions() {
        StringBuilder sb = new StringBuilder();
        TargetProfile[] all = {V928, V929};
        for (TargetProfile p : all) {
            if (!p.known || p.displayVersion == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("、");
            }
            sb.append(p.displayVersion);
        }
        return sb.toString();
    }

    String describe() {
        return known
                ? ("v" + minVersionCode + "-v" + maxVersionCode)
                : "unknown(newest-profile)";
    }

    static TargetProfile forVersion(int versionCode) {
        if (versionCode >= V928_MIN && versionCode <= V928_MAX) {
            return V928;
        }
        if (versionCode >= V929_MIN && versionCode <= V929_MAX) {
            return V929;
        }
        // 未知版本：复用最新档案，但标记 unknown，锚点解析失败会优雅降级
        return V929.asUnknown();
    }

    // ------------------------------------------------------------------
    // 9.2.8（1652）
    // 注意：这里只放 9.2.8 真实存在的名字，不能混入 9.2.9 的新名，
    // 否则会像 o00000oo 那样“新名误命中旧版”。
    // ------------------------------------------------------------------
    private static final TargetProfile V928 = new TargetProfile(
            true, V928_MIN, V928_MAX, "9.2.8",
            new String[]{"o00OOOOo.OooO0O0"},
            new String[]{"o00O.OooO0O0"},
            new String[]{"o00O.OooO0O0$Oooo000"},
            new String[]{"o00O.OooO0O0$o000oOoO"},
            new String[]{"com.lolaage.tbulu.tools.ChangeAppLogoActivity$mAdapter$2$OooO00o"},
            new String[]{"Oooo0O0"},
            new String[]{"com.lolaage.tbulu.tools.ui.fragment.main.o0000"},
            new String[]{"com.lolaage.tbulu.tools.ui.fragment.main.o0000$OooOo"},
            new String[]{"o00000oO"}
    );

    // ------------------------------------------------------------------
    // 9.2.9（1656）
    // ------------------------------------------------------------------
    private static final TargetProfile V929 = new TargetProfile(
            true, V929_MIN, V929_MAX, "9.2.9",
            new String[]{"o00O0oOO.o0OoOo0", "o00OOOOo.OooO0O0"},
            new String[]{"o00O0O0O.OooO0O0", "o00O.OooO0O0"},
            new String[]{"o00O0O0O.OooO0O0$Oooo000", "o00O.OooO0O0$Oooo000"},
            new String[]{"o00O0O0O.OooO0O0$o000oOoO", "o00O.OooO0O0$o000oOoO"},
            new String[]{"com.lolaage.tbulu.tools.ChangeAppLogoActivity$mAdapter$2$1",
                    "com.lolaage.tbulu.tools.ChangeAppLogoActivity$mAdapter$2$OooO00o"},
            new String[]{"Oooo0", "Oooo0O0"},
            new String[]{"com.lolaage.tbulu.tools.ui.fragment.main.o0000",
                    "com.lolaage.tbulu.tools.ui.fragment.main.SetUpFragment"},
            new String[]{"com.lolaage.tbulu.tools.ui.fragment.main.SetUpFragment$OooOo",
                    "com.lolaage.tbulu.tools.ui.fragment.main.o0000$OooOo"},
            new String[]{"o00000oo", "o00000oO"}
    );

    private TargetProfile() {
        throw new AssertionError();
    }
}
