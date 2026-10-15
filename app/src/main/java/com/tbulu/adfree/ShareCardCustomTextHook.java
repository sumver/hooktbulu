package com.tbulu.adfree;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 自定义分享卡底部文案（v1.16 新增，独立开关「分享卡净化」分组第二项）。
 *
 * 背景（JADX 反编译 + apktool/dexdump 核对，两步路 9.2.8 / versionCode 1652）：
 *   「打卡/累计」分享卡底部那段话（例："做最好的自己，才能遇见最好的别人"）是
 *   布局 view_sport_punch_grand_total_share.xml 的 tvDescription，
 *   App 唯一的写入/清空出口是 SportPunchOrGrandTotalShareView#OooOo0O(String)
 *   （空/null → 隐藏该文本）。
 *   文案由服务端 watermark/reference 字段下发；打卡卡点击文案会请求下一条
 *   （onClick 里 type==0 才走 OooOo()），累计卡点击无反应 —— 与用户观察一致。
 *   卡片的导出图是整张 View 截图（getBitmapFromView），改文本即改图。
 *
 * 本 Hook 的行为（方案1）：
 *   1) setData 执行后，按资源 id `tvDescription`（运行时解析）找到 TextView，
 *      给它挂长按监听：弹输入框（预填当前文案）→「确定」写入自定义文案；
 *      「确定」在输入为空（纯空白）时禁用 —— 不允许自定义文案为空；
 *      「恢复默认」还原为最近一次服务端文案（若服务端从未下发则回占位文案）。
 *   2) 文案不允许为空、保证长按入口始终可用：
 *      - App 调 OooOo0O(空) 时：有自定义 → 保留自定义；无自定义 → 显示占位文案；
 *      - setData 后延迟（约 0.8s，等服务端首次回包）检查，仍为空则显示占位文案。
 *   3) 方案1（让位策略）：App 刷新文案（点击换文案 / 换背景 / 服务端回调）时，
 *      自定义内容被还原为服务端文案；不做跨页面/跨进程持久化。
 *
 * 开关：Prefs.K_SHARE_CUSTOM（设置界面「分享卡净化 → 自定义分享卡文案」，默认开启；
 *   修改后需重启两步路才生效）。关闭时不挂监听、不做非空兜底。
 *
 * 类名/方法名与 9.2.8 混淆结果绑定（dexdump 核实：
 *   Lcom/lolaage/tbulu/tools/ui/views/SportPunchOrGrandTotalShareView;
 *   setData(TtkTrackInfo)V、OooOo0O(String)V）；App 升级后若失效会自动跳过
 *   （仅记录日志），不影响 App 运行。
 */
final class ShareCardCustomTextHook {

    /** 目标类（9.2.8 运行时类名）。 */
    private static final String TARGET_CLASS =
            "com.lolaage.tbulu.tools.ui.views.SportPunchOrGrandTotalShareView";
    /** 卡片数据装载入口（挂长按监听 + 延迟非空兜底）。 */
    private static final String M_SET_DATA = "setData";
    /** App 写入/清空底部文案的唯一出口。 */
    private static final String M_SET_DESC = "OooOo0O";
    /** 底部文案控件资源名（布局 view_sport_punch_grand_total_share.xml）。 */
    private static final String TV_DESC_ID_NAME = "tvDescription";
    /** 服务端未下发文案时的占位提示（保证长按入口可用）。 */
    private static final String PLACEHOLDER = "长按输入自定义文案";
    /** setData 后延迟检查时长（给服务端首次回包留时间）。 */
    private static final long ENSURE_DELAY_MS = 800L;

    /** 每张卡片的运行状态（WeakHashMap：卡片销毁后自动释放）。 */
    private static final Map<View, CardState> sStates =
            Collections.synchronizedMap(new WeakHashMap<View, CardState>());

    /** tvDescription 的运行时 id（0=未解析/解析失败）。 */
    private static volatile int sTvDescId;

    /** 已记录过的日志 key（避免刷屏）。 */
    private static final Set<String> sLogged = ConcurrentHashMap.newKeySet();

    private ShareCardCustomTextHook() {
    }

    /** 每张卡片的状态。 */
    private static final class CardState {
        /** 底部文案控件（懒解析）。 */
        TextView tv;
        /** 最近一次服务端下发的文案（非空）。 */
        String appText;
        /** 当前生效的自定义文案（null=无）。 */
        String customText;
        /** 是否已调度过"延迟非空兜底"。 */
        boolean ensureScheduled;
        /** 是否已挂过长按监听。 */
        boolean listenerAttached;
    }

    static void install(ClassLoader cl) {
        try {
            Class<?> cls = XposedHelpers.findClass(TARGET_CLASS, cl);
            XposedBridge.hookAllMethods(cls, M_SET_DATA, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.getThrowable() != null || !Prefs.isEnabled(Prefs.K_SHARE_CUSTOM)) {
                        return;
                    }
                    Object self = param.thisObject;
                    if (!(self instanceof View)) {
                        return;
                    }
                    attach((View) self);
                }
            });
            XposedBridge.hookAllMethods(cls, M_SET_DESC, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.getThrowable() != null || !Prefs.isEnabled(Prefs.K_SHARE_CUSTOM)) {
                        return;
                    }
                    Object self = param.thisObject;
                    if (!(self instanceof View)) {
                        return;
                    }
                    // 只处理 OooOo0O(String) 这一个重载；其他重载（若未来出现）不处理
                    if (param.args == null || param.args.length != 1
                            || (param.args[0] != null && !(param.args[0] instanceof String))) {
                        return;
                    }
                    onDescriptionSet((View) self, (String) param.args[0]);
                }
            });
            XposedInit.log("ShareCardCustomTextHook: hooked " + cls.getSimpleName() + "."
                    + M_SET_DATA + "/" + M_SET_DESC + " (custom bottom text)");
        } catch (Throwable t) {
            XposedInit.log("ShareCardCustomTextHook: hook failed: " + t);
        }
    }

    // ------------------------------------------------------------------
    // setData：挂长按监听 + 延迟非空兜底
    // ------------------------------------------------------------------

    private static void attach(final View card) {
        CardState state = stateOf(card);
        final TextView tv = findTv(card, state);
        if (tv == null) {
            return;
        }
        if (!state.listenerAttached) {
            state.listenerAttached = true;
            tv.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    // 必须消费该事件：否则松手后还会触发卡片自带的点击（打卡换文案）
                    showDialog(tv, state);
                    logOnce("long-press dialog shown");
                    return true;
                }
            });
        }
        if (!state.ensureScheduled) {
            state.ensureScheduled = true;
            tv.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!Prefs.isEnabled(Prefs.K_SHARE_CUSTOM)) {
                        return;
                    }
                    CardState st = stateOf(card);
                    if (st.customText != null) {
                        return;
                    }
                    if (!isBlank(tv.getText())) {
                        return;
                    }
                    applyText(tv, PLACEHOLDER);
                    logOnce("placeholder applied (no server text after delay)");
                }
            }, ENSURE_DELAY_MS);
        }
    }

    // ------------------------------------------------------------------
    // OooOo0O：App 写入/清空文案时的处理（方案1：让位；空文案兜底）
    // ------------------------------------------------------------------

    private static void onDescriptionSet(View card, String desc) {
        CardState state = stateOf(card);
        TextView tv = findTv(card, state);
        if (tv == null) {
            return;
        }
        if (!isBlank(desc)) {
            // 服务端文案：记录之；自定义内容让位（方案1）
            state.appText = desc;
            state.customText = null;
            return;
        }
        // App 想清空/隐藏：不允许文案为空
        state.appText = null;
        if (state.customText != null) {
            applyText(tv, state.customText);
            logOnce("custom kept against empty description");
        } else {
            applyText(tv, PLACEHOLDER);
            logOnce("placeholder applied (empty description)");
        }
    }

    // ------------------------------------------------------------------
    // 长按输入弹窗
    // ------------------------------------------------------------------

    private static void showDialog(final TextView tv, final CardState state) {
        Context ctx = tv.getContext();
        final EditText input = new EditText(ctx);
        String current = tv.getText() == null ? "" : tv.getText().toString();
        if (!PLACEHOLDER.equals(current)) {
            input.setText(current);
            input.setSelection(current.length());
        }
        input.setHint("请输入自定义文案");
        input.setMaxLines(2);

        AlertDialog.Builder builder = new AlertDialog.Builder(ctx);
        builder.setTitle("自定义文案");
        builder.setView(input);
        builder.setPositiveButton("确定", null);
        builder.setNeutralButton("恢复默认", null);
        builder.setNegativeButton("取消", null);
        final AlertDialog dialog = builder.create();
        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface d) {
                final Button ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                // 不允许自定义文案为空：空白输入时禁用「确定」
                ok.setEnabled(!isBlank(input.getText()));
                input.addTextChangedListener(new TextWatcher() {
                    @Override
                    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                    }

                    @Override
                    public void onTextChanged(CharSequence s, int start, int before, int count) {
                    }

                    @Override
                    public void afterTextChanged(Editable s) {
                        ok.setEnabled(!isBlank(s));
                    }
                });
                ok.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        String text = input.getText().toString().trim();
                        if (text.length() == 0) {
                            return; // 防御：空输入不生效（按钮已禁用）
                        }
                        state.customText = text;
                        applyText(tv, text);
                        logOnce("custom text applied");
                        dialog.dismiss();
                    }
                });
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                // 恢复默认：还原最近一次服务端文案；服务端从未下发则用占位文案
                                String restore = isBlank(state.appText) ? PLACEHOLDER : state.appText;
                                state.customText = null;
                                applyText(tv, restore);
                                dialog.dismiss();
                            }
                        });
                input.requestFocus();
                dialog.getWindow().setSoftInputMode(
                        WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
            }
        });
        dialog.show();
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private static void applyText(TextView tv, String text) {
        tv.setVisibility(View.VISIBLE);
        tv.setText(text);
    }

    /** 取/建卡片状态。 */
    private static CardState stateOf(View card) {
        synchronized (sStates) {
            CardState state = sStates.get(card);
            if (state == null) {
                state = new CardState();
                sStates.put(card, state);
            }
            return state;
        }
    }

    /** 在卡片内按资源 id 找底部文案 TextView（懒解析并缓存）。 */
    private static TextView findTv(View card, CardState state) {
        if (state.tv != null) {
            return state.tv;
        }
        int id = sTvDescId;
        if (id == 0) {
            try {
                id = card.getContext().getResources()
                        .getIdentifier(TV_DESC_ID_NAME, "id", card.getContext().getPackageName());
            } catch (Throwable ignored) {
            }
            if (id == 0) {
                logOnce("tvDescription id not found, skip");
                return null;
            }
            sTvDescId = id;
        }
        View v = card.findViewById(id);
        if (v instanceof TextView) {
            state.tv = (TextView) v;
            return state.tv;
        }
        logOnce("tvDescription view not found, skip");
        return null;
    }

    private static boolean isBlank(CharSequence cs) {
        return cs == null || cs.toString().trim().length() == 0;
    }

    private static void logOnce(String msg) {
        if (sLogged.add(msg)) {
            XposedInit.log("ShareCardCustomTextHook: " + msg);
        }
    }
}
