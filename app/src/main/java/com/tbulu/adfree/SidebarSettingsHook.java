package com.tbulu.adfree;

import android.app.AndroidAppHelper;
import android.app.Dialog;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RelativeLayout;
import android.widget.TextView;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 把「模块设置」入口注入「我的」页左上角侧滑菜单（{@code MyViewSidebarDialog}）。
 *
 * 目标布局：{@code dialog_my_view_sidebar.xml}
 *  - 顶部图标行：btnSetUp / btnHelp / btnScanIt；
 *  - 分隔线 vLine 之下是竖排列表（tvMyQrCode、tvRealNameAuthentication …）。
 *
 * 注入方式：在 {@code MyViewSidebarDialog} 构造完成后，向内容容器 clContent 追加一行
 * 「模块设置」TextView，放在分隔线 vLine 与 tvMyQrCode 之间（即竖排列表最顶部），
 * 并把 tvMyQrCode 的相对锚点由 vLine 改到新行上，后续各行的 layout_below 链自然下移。
 *
 * 点击「模块设置」→ 关闭侧滑菜单 → 在两步路进程内弹出 {@link ModuleSettingsDialog}
 * （不跳转模块 App，见该类的设计说明）。
 *
 * 所有资源 id 均通过 {@code getIdentifier} 运行时解析，避免版本间资源编号变化；
 * 类/方法/字段与 9.2.8 绑定，App 升级后失效会自动跳过（仅记录日志），不影响 App 运行。
 */
final class SidebarSettingsHook {

    private static final String DIALOG_CLASS = "com.lolaage.tbulu.tools.ui.dialog.MyViewSidebarDialog";
    /** MyViewSidebarDialog 里的 ViewBinding 字段（ViewBinding.getRoot() 是接口方法，名字稳定）。 */
    private static final String BINDING_FIELD = "binding";

    private static final String ID_CL_CONTENT = "clContent";   // 侧滑菜单内容容器（RelativeLayout）
    private static final String ID_V_LINE = "vLine";           // 顶部图标行与竖排列表之间的分隔线
    private static final String ID_MY_QR_CODE = "tvMyQrCode";  // 竖排列表第一项（新入口插在它上面）
    private static final String MIPMAP_ICON = "ic_sidebar_set_up"; // 复用「设置」图标（齿轮）

    /** 资源 id 缓存（name -> id）。 */
    private static final Map<String, Integer> sIdCache = new ConcurrentHashMap<>();

    private SidebarSettingsHook() {
    }

    static void install(ClassLoader cl) {
        Class<?> dialogClass;
        try {
            dialogClass = XposedHelpers.findClass(DIALOG_CLASS, cl);
        } catch (Throwable t) {
            XposedInit.log("SidebarSettingsHook: dialog class not found, skip. " + t);
            return;
        }
        try {
            XposedBridge.hookAllConstructors(dialogClass, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    injectEntry(param.thisObject);
                }
            });
            XposedInit.log("SidebarSettingsHook installed (MyViewSidebarDialog).");
        } catch (Throwable t) {
            XposedInit.log("SidebarSettingsHook: hook failed: " + t);
        }
    }

    private static void injectEntry(Object dialogObj) {
        try {
            if (!(dialogObj instanceof Dialog)) {
                return;
            }
            final Dialog dialog = (Dialog) dialogObj;
            final Context context = dialog.getContext();

            int clId = resolveId(context, "id", ID_CL_CONTENT);
            int vLineId = resolveId(context, "id", ID_V_LINE);
            int qrCodeId = resolveId(context, "id", ID_MY_QR_CODE);
            if (clId == 0 || vLineId == 0 || qrCodeId == 0) {
                return;
            }

            View contentObj = findContentContainer(dialog, clId);
            if (!(contentObj instanceof RelativeLayout)) {
                XposedInit.log("SidebarSettingsHook: clContent not found, skip.");
                return;
            }
            RelativeLayout container = (RelativeLayout) contentObj;

            View vLine = container.findViewById(vLineId);
            View qrCode = container.findViewById(qrCodeId);
            if (vLine == null || qrCode == null) {
                XposedInit.log("SidebarSettingsHook: anchor views missing, skip.");
                return;
            }

            TextView entry = buildEntryView(context, qrCode);
            final int entryId = View.generateViewId();
            entry.setId(entryId);
            final Context ctx = context;
            entry.setOnClickListener(v -> {
                try {
                    dialog.dismiss();
                } catch (Throwable ignored) {
                }
                ModuleSettingsDialog.show(ctx);
            });

            RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            // 放在分隔线 vLine 与竖排列表第一项之间 → 即竖排列表最顶部
            lp.addRule(RelativeLayout.BELOW, vLineId);
            container.addView(entry, lp);

            // tvMyQrCode 原本 layout_below=vLine，改锚到新行上，后续各行随之整体下移
            ViewGroup.LayoutParams qrParams = qrCode.getLayoutParams();
            if (qrParams instanceof RelativeLayout.LayoutParams) {
                RelativeLayout.LayoutParams rqlp = (RelativeLayout.LayoutParams) qrParams;
                rqlp.removeRule(RelativeLayout.BELOW);
                rqlp.addRule(RelativeLayout.BELOW, entryId);
                qrCode.setLayoutParams(rqlp);
            }

            XposedInit.log("SidebarSettingsHook: module settings entry injected.");
        } catch (Throwable t) {
            XposedInit.log("SidebarSettingsHook: inject failed: " + t);
        }
    }

    /**
     * 取得 clContent 容器：优先用 Dialog.findViewById（侧滑菜单根视图已加入 Dialog 内容区），
     * 失败时退回经 ViewBinding.getRoot() 查找。
     */
    private static View findContentContainer(Dialog dialog, int clId) {
        try {
            View found = dialog.findViewById(clId);
            if (found != null) {
                return found;
            }
        } catch (Throwable ignored) {
            // 继续走 binding 兜底
        }
        try {
            Object binding = XposedHelpers.getObjectField(dialog, BINDING_FIELD);
            View root = (View) XposedHelpers.callMethod(binding, "getRoot");
            if (root != null) {
                return root.findViewById(clId);
            }
        } catch (Throwable t) {
            XposedInit.log("SidebarSettingsHook: binding fallback failed: " + t);
        }
        return null;
    }

    /** 按竖排列表项（tvMyQrCode）的样式克隆一个「模块设置」TextView。 */
    private static TextView buildEntryView(Context context, View template) {
        TextView entry = new TextView(context);
        entry.setText("模块设置");
        entry.setClickable(true);
        entry.setGravity(Gravity.CENTER_VERTICAL);

        if (template instanceof TextView) {
            TextView tv = (TextView) template;
            entry.setTextSize(TypedValue.COMPLEX_UNIT_PX, tv.getTextSize());
            entry.setTextColor(tv.getCurrentTextColor());
            entry.setGravity(tv.getGravity());
            entry.setCompoundDrawablePadding(tv.getCompoundDrawablePadding());
            entry.setPadding(tv.getPaddingLeft(), tv.getPaddingTop(),
                    tv.getPaddingRight(), tv.getPaddingBottom());
        } else {
            entry.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
            entry.setPadding(dp(context, 10), dp(context, 7), dp(context, 10), dp(context, 7));
        }

        int iconId = resolveId(context, "mipmap", MIPMAP_ICON);
        if (iconId != 0) {
            Drawable icon;
            try {
                icon = context.getResources().getDrawable(iconId, context.getTheme());
            } catch (Throwable t) {
                icon = context.getResources().getDrawable(iconId);
            }
            entry.setCompoundDrawablesWithIntrinsicBounds(icon, null, null, null);
        }

        // 点击反馈（复用系统 selectableItemBackground）
        try {
            TypedValue out = new TypedValue();
            if (context.getTheme().resolveAttribute(
                    android.R.attr.selectableItemBackground, out, true) && out.resourceId != 0) {
                entry.setBackgroundResource(out.resourceId);
            }
        } catch (Throwable ignored) {
        }

        return entry;
    }

    private static int dp(Context context, float value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 运行时解析资源 id（成功后缓存；Application 未就绪时下次重试）。 */
    private static int resolveId(Context context, String type, String name) {
        Integer cached = sIdCache.get(name);
        if (cached != null && cached != 0) {
            return cached;
        }
        try {
            Context app = context;
            if (app == null) {
                app = AndroidAppHelper.currentApplication();
            }
            if (app == null) {
                return 0;
            }
            int id = app.getResources().getIdentifier(name, type, XposedInit.TARGET_PKG);
            if (id != 0) {
                sIdCache.put(name, id);
                XposedInit.log("resolved " + name + " id = 0x" + Integer.toHexString(id));
            }
            return id;
        } catch (Throwable t) {
            XposedInit.log("resolveId(" + name + ") failed: " + t);
            return 0;
        }
    }
}
