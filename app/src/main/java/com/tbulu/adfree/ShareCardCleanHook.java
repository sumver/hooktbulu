package com.tbulu.adfree;

import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 分享卡顶部文案净化（v1.15 新增，独立开关「分享卡净化」）。
 *
 * 背景（JADX 反编译 + apktool/dexdump 核对，两步路 9.2.8 / versionCode 1652）：
 *   "我的记录 → 分享记录"进入分享页（TrackSportShareActivity），页内 tab：
 *   轨迹链接 / 图片 / 打卡 / 累计 / 进阶 / 轨迹文件。
 *   「打卡」tab 首次选中 → SportPunchOrGrandTotalShareView(type=0).setData(记录)；
 *   「累计」tab 首次选中 → 同类的 type=1，走同一份 setData 代码。
 *
 *   setData 里这行是"日期时间 + 品牌后缀 + 运动类型"的唯一拼接点
 *   （整个 9.2.8 全 dex 只此一处引用字符串"在两步路app"）：
 *     binding.OooOo0.setText(日期("MM月dd日 HH:mm") + " 在两步路app" + 运动类型名);
 *   其中 binding.OooOo0 ↔ 布局 view_sport_punch_grand_total_share.xml 的 tvTypeAndTime
 *   （卡片左上、昵称下方那行，即分享图最顶部的文案）。
 *
 *   分享/保存图片是把整张卡片 View 直接截图（getBitmapFromView）成 PNG，
 *   所以这里改写文本后，页面预览与导出的分享图会同时生效。
 *
 * 开关（设置界面「分享卡净化」分组，默认开启；修改后需重启两步路才生效）：
 *   开启：把该行文案截断为仅"日期时间"（去掉"在两步路app+运动类型"后缀）；
 *   关闭：不处理，保持 App 原样。
 *
 * 实现方式：hook setData() 执行后，在卡片视图树里深度优先找到文本包含锚点
 *   "在两步路app"的 TextView（全 App 只有这一处会出现该串，不会误伤），
 *   截断到锚点之前并去除尾部空白。不依赖混淆的 binding 字段名。
 *
 * 类名/方法名与 9.2.8 混淆结果绑定（dexdump 核实：
 *   Lcom/lolaage/tbulu/tools/ui/views/SportPunchOrGrandTotalShareView;
 *   public final setData(Lcom/lolaage/tbulu/tools/business/models/TtkTrackInfo;)V）；
 *   App 升级后若失效会自动跳过（仅记录日志），不影响 App 运行。
 */
final class ShareCardCleanHook {

    /** 目标类（9.2.8 运行时类名）。 */
    private static final String TARGET_CLASS =
            "com.lolaage.tbulu.tools.ui.views.SportPunchOrGrandTotalShareView";
    /** 目标方法（打卡/累计分享卡数据装载的公共入口）。 */
    private static final String TARGET_METHOD = "setData";
    /** 文案锚点：品牌后缀的起始片段（全 dex 唯一）。 */
    private static final String MARKER = "在两步路app";
    /** 本进程只记录一次清理日志（避免刷屏）。 */
    private static volatile boolean sLogged;

    private ShareCardCleanHook() {
    }

    static void install(ClassLoader cl) {
        try {
            Class<?> cls = XposedHelpers.findClass(TARGET_CLASS, cl);
            XposedBridge.hookAllMethods(cls, TARGET_METHOD, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.getThrowable() != null || !Prefs.isEnabled(Prefs.K_SHARE_CLEAN)) {
                        return;
                    }
                    Object self = param.thisObject;
                    if (!(self instanceof View)) {
                        return;
                    }
                    final View root = (View) self;
                    if (Looper.myLooper() == Looper.getMainLooper()) {
                        apply(root);
                    } else {
                        root.post(new Runnable() {
                            @Override
                            public void run() {
                                apply(root);
                            }
                        });
                    }
                }
            });
            XposedInit.log("ShareCardCleanHook: hooked " + cls.getSimpleName() + "."
                    + TARGET_METHOD + " (punch/grand-total share card)");
        } catch (Throwable t) {
            XposedInit.log("ShareCardCleanHook: hook failed: " + t);
        }
    }

    /** 截断卡片顶部文案：只保留锚点之前的日期时间部分。 */
    private static void apply(View root) {
        TextView tv = findMarkedTextView(root);
        if (tv == null) {
            return;
        }
        CharSequence cs = tv.getText();
        String text = cs == null ? "" : cs.toString();
        int idx = text.indexOf(MARKER);
        if (idx < 0) {
            return;
        }
        String cleaned = text.substring(0, idx).trim();
        if (cleaned.equals(text)) {
            return;
        }
        tv.setText(cleaned);
        if (!sLogged) {
            sLogged = true;
            XposedInit.log("ShareCardCleanHook: card top text cleaned -> \"" + cleaned + "\"");
        }
    }

    /** 深度优先查找文本包含锚点的 TextView（卡片内唯一）。 */
    private static TextView findMarkedTextView(View view) {
        if (view instanceof TextView) {
            CharSequence cs = ((TextView) view).getText();
            if (cs != null && cs.toString().contains(MARKER)) {
                return (TextView) view;
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = findMarkedTextView(group.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
