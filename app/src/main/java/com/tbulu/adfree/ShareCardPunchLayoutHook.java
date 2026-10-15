package com.tbulu.adfree;

import android.view.View;
import android.view.ViewGroup;
import android.widget.RelativeLayout;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 打卡分享卡布局净化：隐藏「坚持运动 xx天」，把「本次运动 xxxkm」移到左侧（v1.17 新增）。
 *
 * 背景（JADX 反编译 + apktool/dexdump 核对，两步路 9.2.8 / versionCode 1652）：
 *   「打卡/累计」分享卡底部数据区布局 view_sport_punch_grand_total_share.xml：
 *     rlBottomData
 *       ├─ tvDatas（左侧数据块：tvData 大数字 + tvDataUnit 单位 + tvDataTitle 小标题）
 *       ├─ tvThisSport（右侧数据块大数字，layout_toRightOf=tvDatas，marginLeft=10dp）
 *       ├─ tvThisSportUnit（layout_toRightOf=tvThisSport）
 *       ├─ tvThisSportTitle（layout_alignLeft=tvThisSport，"本次运动"）
 *       └─ tvDescription（底部文案）
 *   打卡卡（type=0）：左侧块 =「坚持运动 xx 天」+「天」；右侧块 = 本次运动距离 + km +「本次运动」。
 *   累计卡（type=1）：左侧块 =「累计xx」总数据 —— 本 Hook 不处理（用户要求只改打卡页）。
 *
 * 本 Hook 的行为（仅当开关开启，且卡片 type==0 打卡卡）：
 *   1) 左侧「坚持运动 xx天」数据块（tvDatas）置 GONE；
 *   2) 「本次运动 xxxkm」数据块移到左侧位置：
 *      去掉对 tvDatas 的 layout_toRightOf 锚定，改锚定 alignParentStart，
 *      并把 marginLeft(10dp) 归零（与左侧原有内边距对齐）。
 *      单位/标题均锚定在 tvThisSport 上，会自动跟随。
 *   ⚠ 卡片文字/布局的其余代码不触碰这两个块（已核对），改动不会被覆盖；
 *   导出图是整张卡片截图，布局变化同样生效。
 *
 * 开关：Prefs.K_SHARE_HIDE_DAYS（设置界面「分享卡净化 → 隐藏「坚持运动」天数」，
 *   默认开启；修改后需重启两步路才生效）。关闭时不做任何处理（保持 App 原样）。
 *
 * 类名/字段名与 9.2.8 混淆结果绑定（dexdump 核实：
 *   Lcom/lolaage/tbulu/tools/ui/views/SportPunchOrGrandTotalShareView;
 *   setData(TtkTrackInfo)V；实例字段 OooO00o:int = 卡片类型）；
 *   App 升级后若失效会自动跳过（仅记录日志），不影响 App 运行。
 */
final class ShareCardPunchLayoutHook {

    /** 目标类（9.2.8 运行时类名）。 */
    private static final String TARGET_CLASS =
            "com.lolaage.tbulu.tools.ui.views.SportPunchOrGrandTotalShareView";
    /** 卡片数据装载入口（执行后布局已就绪）。 */
    private static final String M_SET_DATA = "setData";
    /** 卡片类型字段（运行时字段名，int：0=打卡卡 / 1=累计卡）。 */
    private static final String F_TYPE = "OooO00o";
    /** 左侧「坚持运动 xx天」数据块容器。 */
    private static final String ID_TV_DATAS = "tvDatas";
    /** 右侧「本次运动 xxxkm」数据块大数字。 */
    private static final String ID_TV_THIS_SPORT = "tvThisSport";

    /** 已记录过的日志 key（避免刷屏）。 */
    private static final Set<String> sLogged = ConcurrentHashMap.newKeySet();

    private ShareCardPunchLayoutHook() {
    }

    static void install(ClassLoader cl) {
        try {
            Class<?> cls = XposedHelpers.findClass(TARGET_CLASS, cl);
            XposedBridge.hookAllMethods(cls, M_SET_DATA, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.getThrowable() != null || !Prefs.isEnabled(Prefs.K_SHARE_HIDE_DAYS)) {
                        return;
                    }
                    Object self = param.thisObject;
                    if (self instanceof View) {
                        apply((View) self);
                    }
                }
            });
            XposedInit.log("ShareCardPunchLayoutHook: hooked " + cls.getSimpleName() + "."
                    + M_SET_DATA + " (punch day-stat hide)");
        } catch (Throwable t) {
            XposedInit.log("ShareCardPunchLayoutHook: hook failed: " + t);
        }
    }

    private static void apply(View card) {
        int type;
        try {
            type = XposedHelpers.getIntField(card, F_TYPE);
        } catch (Throwable t) {
            logOnce("read type field failed, skip: " + t);
            return;
        }
        if (type != 0) {
            return; // 只处理打卡卡；累计卡（type=1）保持原样
        }
        View dayStat = findById(card, ID_TV_DATAS);
        View thisSport = findById(card, ID_TV_THIS_SPORT);
        if (dayStat == null || thisSport == null) {
            return;
        }
        // 1) 隐藏左侧「坚持运动 xx天」数据块
        dayStat.setVisibility(View.GONE);
        // 2) 「本次运动」数据块从 tvDatas 右侧改为锚定父容器左侧（去掉 10dp 左间距）
        ViewGroup.LayoutParams base = thisSport.getLayoutParams();
        if (base instanceof RelativeLayout.LayoutParams) {
            RelativeLayout.LayoutParams lp = (RelativeLayout.LayoutParams) base;
            lp.removeRule(RelativeLayout.RIGHT_OF);
            lp.addRule(RelativeLayout.ALIGN_PARENT_START);
            lp.leftMargin = 0;
            lp.setMarginStart(0);
            thisSport.setLayoutParams(lp);
        }
        logOnce("punch card: day-stat hidden, this-sport moved to start");
    }

    /** 在卡片内按资源名找视图（每次调用都解析资源 id；每张卡仅执行一次，代价可忽略）。 */
    private static View findById(View card, String resourceName) {
        try {
            int id = card.getContext().getResources()
                    .getIdentifier(resourceName, "id", card.getContext().getPackageName());
            if (id == 0) {
                logOnce("id not found, skip: " + resourceName);
                return null;
            }
            View v = card.findViewById(id);
            if (v == null) {
                logOnce("view not found, skip: " + resourceName);
            }
            return v;
        } catch (Throwable t) {
            logOnce("find " + resourceName + " failed, skip: " + t);
            return null;
        }
    }

    private static void logOnce(String msg) {
        if (sLogged.add(msg)) {
            XposedInit.log("ShareCardPunchLayoutHook: " + msg);
        }
    }
}
