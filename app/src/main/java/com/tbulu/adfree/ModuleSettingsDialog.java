package com.tbulu.adfree;

import android.app.Dialog;
import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/**
 * 两步路进程内的「模块设置」弹窗（从「我的」页左上角侧滑菜单进入）。
 *
 * 设计目标（见 README-技术文档「侧滑菜单模块设置入口」）：
 *  - **不跳转模块 App**：完全在被 Hook 的两步路进程内渲染；
 *  - **不依赖模块资源**：只用 Android 框架控件（{@link LinearLayout}/{@link TextView}/{@link Switch}），
 *    既避免在宿主进程里加载模块的 Material 依赖与 R 资源，也便于后续并入单个 APK；
 *  - **改动即时生效**：开关写入 {@link Prefs#setEnabled} —— 更新本进程内存值（同进程 Hook 立即生效）
 *    并持久化到配置源（当前经 {@link PrefsProvider} 写回模块 App 偏好；单 APK 时直接写本地）。
 *
 * 分组与文案对齐模块 App 的设置界面（{@link SettingsActivity}）。
 */
final class ModuleSettingsDialog {

    private static final int COLOR_BG = 0xFFFFFFFF;
    private static final int COLOR_TITLE = 0xFF212121;
    private static final int COLOR_HEADER = 0xFF1976D2;
    private static final int COLOR_ITEM = 0xFF212121;
    private static final int COLOR_SUMMARY = 0xFF757575;
    private static final int COLOR_DIVIDER = 0xFFE0E0E0;
    private static final int COLOR_HINT = 0xFF9E9E9E;
    private static final int COLOR_WARN = 0xFFFF1744;
    private static final int COLOR_WARN_BG = 0xFFFFE4E9; // 浅红底，衬托红色提醒

    private ModuleSettingsDialog() {
    }

    /** 单个开关项。 */
    private static final class Item {
        final String key;
        final String title;
        final String summary;

        Item(String key, String title, String summary) {
            this.key = key;
            this.title = title;
            this.summary = summary;
        }
    }

    /** 分组。 */
    private static final class Group {
        final String title;
        final Item[] items;

        Group(String title, Item[] items) {
            this.title = title;
            this.items = items;
        }
    }

    private static final Group[] GROUPS = new Group[]{
            new Group("广告净化", new Item[]{
                    new Item(Prefs.K_ADS, "隐藏各类广告",
                            "开屏、信息流、轮播横幅、资讯列表、运动记录页、出游详情、插屏与弹窗广告"),
            }),
            new Group("「我的」页净化", new Item[]{
                    new Item(Prefs.K_VIP_BANNER, "隐藏会员促销横幅", "隐藏VIP开通横幅"),
                    new Item(Prefs.K_VIP_SERVICE, "隐藏 VIP 客服按钮", "移除「我的」页顶部「专属客服」入口"),
                    new Item(Prefs.K_DOCK_BADGE, "屏蔽 dock 栏红点",
                            "底部「我的」图标不再显示未读红点（不影响消息数据本身）"),
                    new Item(Prefs.K_MY_MESSAGE_BADGE, "隐藏消息未读数", "隐藏右上角未读消息数"),
            }),
            new Group("增强分享卡功能", new Item[]{
                    new Item(Prefs.K_SHARE_CLEAN, "分享卡文案净化",
                            "打卡/累计预览图分享页，去掉两步路软广"),
                    new Item(Prefs.K_SHARE_CUSTOM, "分享卡文案自定义",
                            "长按打卡/累计预览图底部的文案，弹出自定义窗口"),
                    new Item(Prefs.K_SHARE_HIDE_DAYS, "隐藏「坚持运动」天数",
                            "打卡预览图隐藏运动天数（我感觉这个天数真的很多余）"),
                    new Item(Prefs.K_SHARE_LOGO, "隐藏「两步路」水印",
                            "隐藏打卡/累计预览图右上角的两步路水印"),
            }),
            new Group("交互优化", new Item[]{
                    new Item(Prefs.K_EQUIPMENT, "装备（商城）tab防误触",
                            "点击底部「装备」先确认「是否打开商城？」"),
                    new Item(Prefs.K_UPGRADE, "屏蔽升级弹窗", ""),
            }),
            new Group("解锁会员功能", new Item[]{
                    new Item(Prefs.K_VIP_WATERMARK, "图片水印", "解锁图片水印"),
                    new Item(Prefs.K_VIP_DIY_HOME, "DIY首页", "可将地图页设为「启动后默认界面」"),
                    new Item(Prefs.K_VIP_PENDANT, "头像挂件",
                            "解锁所有挂件，装饰后可能需要重启一次APP才能完全生效"),
                    new Item(Prefs.K_VIP_STYLE, "专属样式", "解锁 记录/导航/桌面图标皮肤"),
                    new Item(Prefs.K_VIP_SHARE, "个性化分享", "解锁 分享记录-进阶数据"),
                    new Item(Prefs.K_VIP_OVERLAY_TRACKS, "叠加轨迹", "轨迹叠加从10条，解锁为100条"),
                    new Item(Prefs.K_VIP_BULK_EXPORT_SHARE, "轨迹批量导出/分享", "解锁 轨迹批量导出、批量分享"),
            }),
    };

    /** 弹出设置弹窗。 */
    static void show(Context context) {
        if (context == null) {
            return;
        }
        try {
            Dialog dialog = new Dialog(context, android.R.style.Theme_Material_Light_Dialog_Alert);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            dialog.setContentView(buildContentView(context, dialog));

            Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new ColorDrawable(COLOR_BG));
                DisplayMetrics dm = context.getResources().getDisplayMetrics();
                window.setLayout((int) (dm.widthPixels * 0.92f), (int) (dm.heightPixels * 0.8f));
            }
            dialog.show();
        } catch (Throwable t) {
            XposedInit.log("ModuleSettingsDialog: show failed: " + t);
        }
    }

    private static View buildContentView(Context context, final Dialog dialog) {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_BG);

        TextView title = new TextView(context);
        title.setText("模块设置");
        title.setTextColor(COLOR_TITLE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setPadding(dp(context, 20), dp(context, 16), dp(context, 20), dp(context, 12));
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(makeDivider(context),
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 1)));

        // 顶部红色提醒：改动需手动重启两步路生效
        TextView notice = new TextView(context);
        notice.setText("改动后，请手动重启两步路APP，模块功能才会生效。");
        notice.setTextColor(COLOR_WARN);
        notice.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        notice.setTypeface(notice.getTypeface(), android.graphics.Typeface.BOLD);
        notice.setGravity(Gravity.CENTER_VERTICAL);
        notice.setBackgroundColor(COLOR_WARN_BG);
        notice.setPadding(dp(context, 20), dp(context, 12), dp(context, 20), dp(context, 12));
        root.addView(notice, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(makeDivider(context),
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 1)));

        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0);
        scrollParams.weight = 1f;
        root.addView(scroll, scrollParams);

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(context, 16), dp(context, 4), dp(context, 16), dp(context, 8));
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        for (Group group : GROUPS) {
            content.addView(makeGroupHeader(context, group.title));
            for (Item item : group.items) {
                content.addView(makeItemRow(context, item));
            }
        }

        Button close = new Button(context);
        close.setText("关闭");
        close.setOnClickListener(v -> dialog.dismiss());
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        closeParams.setMargins(dp(context, 16), dp(context, 4), dp(context, 16), dp(context, 12));
        root.addView(close, closeParams);

        return root;
    }

    private static View makeGroupHeader(Context context, String text) {
        TextView header = new TextView(context);
        header.setText(text);
        header.setTextColor(COLOR_HEADER);
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        header.setTypeface(header.getTypeface(), android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(context, 16);
        lp.bottomMargin = dp(context, 6);
        header.setLayoutParams(lp);
        return header;
    }

    private static View makeItemRow(Context context, final Item item) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(context, 56));
        row.setPadding(0, dp(context, 8), 0, dp(context, 8));

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textsParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        textsParams.weight = 1f;
        texts.setLayoutParams(textsParams);

        TextView tvTitle = new TextView(context);
        tvTitle.setText(item.title);
        tvTitle.setTextColor(COLOR_ITEM);
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        texts.addView(tvTitle);

        if (item.summary != null && !item.summary.isEmpty()) {
            TextView tvSummary = new TextView(context);
            tvSummary.setText(item.summary);
            tvSummary.setTextColor(COLOR_SUMMARY);
            tvSummary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            LinearLayout.LayoutParams summaryParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            summaryParams.topMargin = dp(context, 2);
            tvSummary.setLayoutParams(summaryParams);
            texts.addView(tvSummary);
        }

        row.addView(texts);

        Switch toggle = new Switch(context);
        toggle.setChecked(Prefs.isEnabled(item.key));
        toggle.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Prefs.setEnabled(context, item.key, isChecked);
            XposedInit.log("sidebar settings: " + item.key + " -> " + isChecked);
        });
        row.addView(toggle);

        // 点击整行切换，扩大可点区域
        row.setClickable(true);
        row.setOnClickListener(v -> toggle.toggle());

        LinearLayout wrapper = new LinearLayout(context);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        wrapper.addView(makeDivider(context), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 1)));
        return wrapper;
    }

    private static View makeDivider(Context context) {
        View divider = new View(context);
        divider.setBackgroundColor(COLOR_DIVIDER);
        return divider;
    }

    private static int dp(Context context, float value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }
}
