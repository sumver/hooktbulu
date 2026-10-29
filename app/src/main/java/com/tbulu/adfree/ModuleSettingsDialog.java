package com.tbulu.adfree;

import android.animation.ValueAnimator;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 两步路进程内的「模块设置」弹窗（从「我的」页左上角侧滑菜单进入）。
 *
 * 视觉：Material Design 3（MD3）风格，但**完全用 Android 框架控件 + Canvas 程序化绘制**，
 * 不引入任何 UI 库、不依赖模块资源（R/Material），避免在宿主进程里加载跨 ClassLoader 资源。
 *
 * 设计要点：
 *  - 底部弹层（bottom sheet）+ 顶部圆角 + 上滑淡入；
 *  - 分组卡片（圆角 16dp、色调表面 surfaceContainer、轻阴影）；
 *  - 自绘 MD3 拨动开关（Canvas + ValueAnimator）；
 *  - 行点击 Ripple 水波纹；深色模式自动配色；
 *  - 改动即时生效（{@link Prefs#setEnabled} 更新本进程内存值并异步持久化）。
 */
final class ModuleSettingsDialog {

    private ModuleSettingsDialog() {
    }

    // ------------------------------------------------------------------
    // 数据模型（开关分组）
    // ------------------------------------------------------------------

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
                    new Item(Prefs.K_VIP_BANNER, "隐藏会员促销横幅", "隐藏 VIP 开通横幅"),
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
                    new Item(Prefs.K_EQUIPMENT, "装备（商城）tab 防误触",
                            "点击底部「装备」先确认「是否打开商城？」"),
                    new Item(Prefs.K_UPGRADE, "屏蔽升级弹窗", ""),
            }),
            new Group("解锁会员功能", new Item[]{
                    new Item(Prefs.K_VIP_WATERMARK, "图片水印", "解锁图片水印"),
                    new Item(Prefs.K_VIP_DIY_HOME, "DIY首页", "可将地图页设为「启动后默认界面」"),
                    new Item(Prefs.K_VIP_PENDANT, "头像挂件",
                            "解锁所有挂件，装饰后可能需要重启一次 APP 才能完全生效"),
                    new Item(Prefs.K_VIP_STYLE, "专属样式", "解锁 记录/导航/桌面图标皮肤"),
                    new Item(Prefs.K_VIP_SHARE, "个性化分享", "解锁 分享记录-进阶数据"),
                    new Item(Prefs.K_VIP_OVERLAY_TRACKS, "叠加轨迹", "轨迹叠加从 10 条，解锁为 100 条"),
                    new Item(Prefs.K_VIP_BULK_EXPORT_SHARE, "轨迹批量导出/分享", "解锁 轨迹批量导出、批量分享"),
            }),
    };

    // ------------------------------------------------------------------
    // 弹窗入口
    // ------------------------------------------------------------------

    static void show(Context context) {
        if (context == null) {
            return;
        }
        try {
            Palette palette = Palette.of(context);
            Dialog dialog = new Dialog(context, android.R.style.Theme_Material_Light_Dialog_Alert);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            View content = buildContentView(context, dialog, palette);
            dialog.setContentView(content);

            Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                window.setGravity(Gravity.BOTTOM);
                window.setDimAmount(0.42f);
                DisplayMetrics dm = context.getResources().getDisplayMetrics();
                window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                        (int) (dm.heightPixels * 0.9f));
            }
            dialog.show();

            // 上滑 + 淡入
            content.setTranslationY(dp(context, 56));
            content.setAlpha(0f);
            content.animate().translationY(0f).alpha(1f)
                    .setDuration(260L)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        } catch (Throwable t) {
            XposedInit.log("ModuleSettingsDialog: show failed: " + t);
        }
    }

    private static View buildContentView(Context context, final Dialog dialog, Palette p) {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(rounded(p.surface, dp(context, 28), dp(context, 28), 0, 0));

        // 顶部拖拽条
        View handle = new View(context);
        handle.setBackground(rounded(p.surfaceVariant, dp(context, 2), dp(context, 2),
                dp(context, 2), dp(context, 2)));
        LinearLayout.LayoutParams handleParams = new LinearLayout.LayoutParams(
                dp(context, 32), dp(context, 4));
        handleParams.gravity = Gravity.CENTER_HORIZONTAL;
        handleParams.topMargin = dp(context, 12);
        root.addView(handle, handleParams);

        // 标题
        TextView title = new TextView(context);
        title.setText("模块设置");
        title.setTextColor(p.onSurface);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setPadding(dp(context, 24), dp(context, 14), dp(context, 24), 0);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView subtitle = new TextView(context);
        subtitle.setText("两步路去广告模块 · 本地开关");
        subtitle.setTextColor(p.onSurfaceVariant);
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        subtitle.setPadding(dp(context, 24), dp(context, 4), dp(context, 24), 0);
        root.addView(subtitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 顶部提醒（error container 色调）
        TextView notice = new TextView(context);
        notice.setText("改动后，请手动重启两步路APP，模块功能才会生效。");
        notice.setTextColor(p.onErrorContainer);
        notice.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        notice.setTypeface(notice.getTypeface(), android.graphics.Typeface.BOLD);
        notice.setGravity(Gravity.CENTER_VERTICAL);
        notice.setBackground(rounded(p.errorContainer, dp(context, 14)));
        notice.setPadding(dp(context, 16), dp(context, 12), dp(context, 16), dp(context, 12));
        LinearLayout.LayoutParams noticeParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        noticeParams.setMargins(dp(context, 16), dp(context, 14), dp(context, 16), dp(context, 4));
        root.addView(notice, noticeParams);

        // 可滚动内容
        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0);
        scrollParams.weight = 1f;
        scrollParams.topMargin = dp(context, 4);
        root.addView(scroll, scrollParams);

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, 0, 0, dp(context, 8));
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        scroll.post(() -> scroll.scrollTo(0, 0));

        for (Group group : GROUPS) {
            content.addView(makeSectionHeader(context, group.title, p));
            content.addView(makeCard(context, group.items, p));
        }

        // 底部关闭按钮（MD3 filled tonal）
        TextView close = new TextView(context);
        close.setText("关闭");
        close.setTextColor(p.onPrimary);
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        close.setTypeface(close.getTypeface(), android.graphics.Typeface.BOLD);
        close.setGravity(Gravity.CENTER);
        close.setClickable(true);
        close.setFocusable(true);
        close.setBackground(filledButton(p.primary, p.rippleOnPrimary));
        close.setOnClickListener(v -> dialog.dismiss());
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 48));
        closeParams.setMargins(dp(context, 24), dp(context, 10), dp(context, 24), dp(context, 20));
        root.addView(close, closeParams);

        return root;
    }

    private static View makeSectionHeader(Context context, String text, Palette p) {
        TextView header = new TextView(context);
        header.setText(text);
        header.setTextColor(p.primary);
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        header.setTypeface(header.getTypeface(), android.graphics.Typeface.BOLD);
        header.setLetterSpacing(0.06f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(context, 28);
        lp.rightMargin = dp(context, 28);
        lp.topMargin = dp(context, 18);
        lp.bottomMargin = dp(context, 8);
        header.setLayoutParams(lp);
        return header;
    }

    private static View makeCard(Context context, Item[] items, Palette p) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(p.surfaceContainer, dp(context, 16)));
        card.setElevation(dp(context, 1));
        card.setPadding(0, dp(context, 6), 0, dp(context, 6));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.leftMargin = dp(context, 16);
        cardParams.rightMargin = dp(context, 16);
        card.setLayoutParams(cardParams);

        for (int i = 0; i < items.length; i++) {
            card.addView(makeItemRow(context, items[i], p));
            if (i < items.length - 1) {
                View divider = new View(context);
                divider.setBackgroundColor(p.outlineVariant);
                LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 1);
                dlp.leftMargin = dp(context, 16);
                dlp.rightMargin = dp(context, 16);
                card.addView(divider, dlp);
            }
        }
        return card;
    }

    private static View makeItemRow(Context context, final Item item, Palette p) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(context, 16), dp(context, 12), dp(context, 12), dp(context, 12));
        row.setBackground(ripple(p.ripple, null));
        row.setClickable(true);
        row.setFocusable(false);

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textsParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        textsParams.weight = 1f;
        textsParams.rightMargin = dp(context, 12);
        texts.setLayoutParams(textsParams);

        TextView tvTitle = new TextView(context);
        tvTitle.setText(item.title);
        tvTitle.setTextColor(p.onSurface);
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        texts.addView(tvTitle);

        if (item.summary != null && !item.summary.isEmpty()) {
            TextView tvSummary = new TextView(context);
            tvSummary.setText(item.summary);
            tvSummary.setTextColor(p.onSurfaceVariant);
            tvSummary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            tvSummary.setLineSpacing(dp(context, 2), 1f);
            LinearLayout.LayoutParams summaryParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            summaryParams.topMargin = dp(context, 3);
            tvSummary.setLayoutParams(summaryParams);
            texts.addView(tvSummary);
        }
        row.addView(texts);

        final MdSwitch toggle = new MdSwitch(context, p);
        toggle.setChecked(Prefs.isEnabled(item.key), false);
        toggle.setOnCheckedChangeListener((button, isChecked) -> {
            Prefs.setEnabled(context, item.key, isChecked);
            XposedInit.log("sidebar settings: " + item.key + " -> " + isChecked);
        });
        row.addView(toggle);

        row.setOnClickListener(v -> toggle.toggle());
        return row;
    }

    // ------------------------------------------------------------------
    // 绘制工具
    // ------------------------------------------------------------------

    private static int dp(Context context, float value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static GradientDrawable rounded(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    private static GradientDrawable rounded(int color, int tl, int tr, int bl, int br) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadii(new float[]{
                tl, tl, tr, tr,
                br, br, bl, bl,
        });
        return d;
    }

    private static Drawable ripple(int color, Drawable content) {
        return new RippleDrawable(ColorStateList.valueOf(color), content, content);
    }

    private static Drawable filledButton(int color, int rippleColor) {
        GradientDrawable bg = rounded(color, 1000);
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), bg, bg);
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    private static int blend(int a, int b, float t) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int r = (int) (ar + (br - ar) * t);
        int g = (int) (ag + (bg - ag) * t);
        int bl = (int) (ab + (bb - ab) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    // ------------------------------------------------------------------
    // MD3 配色
    // ------------------------------------------------------------------

    private static final class Palette {
        final int surface;
        final int surfaceContainer;
        final int surfaceVariant;
        final int onSurface;
        final int onSurfaceVariant;
        final int outline;
        final int outlineVariant;
        final int primary;
        final int onPrimary;
        final int primaryContainer;
        final int errorContainer;
        final int onErrorContainer;
        final int ripple;
        final int rippleOnPrimary;

        private Palette(int surface, int surfaceContainer, int surfaceVariant,
                        int onSurface, int onSurfaceVariant, int outline, int outlineVariant,
                        int primary, int onPrimary, int primaryContainer,
                        int errorContainer, int onErrorContainer) {
            this.surface = surface;
            this.surfaceContainer = surfaceContainer;
            this.surfaceVariant = surfaceVariant;
            this.onSurface = onSurface;
            this.onSurfaceVariant = onSurfaceVariant;
            this.outline = outline;
            this.outlineVariant = outlineVariant;
            this.primary = primary;
            this.onPrimary = onPrimary;
            this.primaryContainer = primaryContainer;
            this.errorContainer = errorContainer;
            this.onErrorContainer = onErrorContainer;
            this.ripple = withAlpha(onSurface, 0x1F);
            this.rippleOnPrimary = withAlpha(onPrimary, 0x33);
        }

        static Palette of(Context context) {
            // 固定浅色 + 绿色点缀（MD3 浅色方案，开关/按钮/分组标题统一用绿色主色）
            return new Palette(
                    0xFFFBFDF9, // surface
                    0xFFF1F4F1, // surfaceContainer
                    0xFFE0E6E0, // surfaceVariant
                    0xFF1A1C19, // onSurface
                    0xFF414941, // onSurfaceVariant
                    0xFF6F7A70, // outline
                    0xFFC2CBC3, // outlineVariant
                    0xFF16A34A, // primary（绿）
                    0xFFFFFFFF, // onPrimary
                    0xFFB7F0C9, // primaryContainer
                    0xFFF9DEDC, // errorContainer
                    0xFF410E0B  // onErrorContainer
            );
        }
    }

    // ------------------------------------------------------------------
    // 自绘 MD3 拨动开关
    // ------------------------------------------------------------------

    static final class MdSwitch extends View {

        interface OnCheckedChangeListener {
            void onCheckedChanged(MdSwitch view, boolean checked);
        }

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF track = new RectF();
        private final Palette p;

        private final float width;
        private final float height;
        private final float thumbSmall;
        private final float thumbLarge;
        private final float trackStroke;

        private boolean checked;
        private float progress;
        private ValueAnimator animator;
        private OnCheckedChangeListener listener;

        MdSwitch(Context context, Palette palette) {
            super(context);
            this.p = palette;
            this.width = dp(context, 52);
            this.height = dp(context, 32);
            this.thumbSmall = dp(context, 8);
            this.thumbLarge = dp(context, 12);
            this.trackStroke = dp(context, 2);
            setClickable(true);
            setFocusable(false);
            setOnClickListener(v -> toggle());
        }

        void setOnCheckedChangeListener(OnCheckedChangeListener l) {
            this.listener = l;
        }

        void setChecked(boolean value, boolean animate) {
            if (checked == value) {
                progress = value ? 1f : 0f;
                invalidate();
                return;
            }
            checked = value;
            if (!animate) {
                progress = value ? 1f : 0f;
                invalidate();
                return;
            }
            startAnimation(value ? 1f : 0f);
        }

        void toggle() {
            boolean next = !checked;
            checked = next;
            startAnimation(next ? 1f : 0f);
            if (listener != null) {
                listener.onCheckedChanged(this, next);
            }
        }

        private void startAnimation(float target) {
            if (animator != null) {
                animator.cancel();
            }
            animator = ValueAnimator.ofFloat(progress, target);
            animator.setDuration(180L);
            animator.setInterpolator(new DecelerateInterpolator());
            animator.addUpdateListener(a -> {
                progress = (float) a.getAnimatedValue();
                invalidate();
            });
            animator.start();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            setMeasuredDimension((int) Math.ceil(width), (int) Math.ceil(height));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            float r = h / 2f;

            // 轨道
            int trackColor = blend(p.surfaceVariant, p.primary, progress);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(trackColor);
            track.set(0, 0, w, h);
            canvas.drawRoundRect(track, r, r, paint);

            // 关闭态描边
            if (progress < 1f) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(trackStroke);
                paint.setColor(blend(p.outline, p.outline, 1f));
                paint.setAlpha((int) (255 * (1f - progress)));
                canvas.drawRoundRect(track, r, r, paint);
                paint.setAlpha(255);
            }

            // 圆点
            float thumbR = thumbSmall + (thumbLarge - thumbSmall) * progress;
            float offCenter = h / 2f;
            float onCenter = w - thumbLarge - dp(getContext(), 4);
            float cx = offCenter + (onCenter - offCenter) * progress;
            float cy = h / 2f;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(blend(p.outline, p.onPrimary, progress));
            canvas.drawCircle(cx, cy, thumbR, paint);
        }
    }
}
