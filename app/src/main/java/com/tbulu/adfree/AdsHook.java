package com.tbulu.adfree;

import android.app.AndroidAppHelper;
import android.content.res.Resources;
import android.view.View;
import android.view.ViewGroup;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 广告位 Hook 集合（两步路 9.2.8 / versionCode 1652）。
 *
 * 设计：
 *  - 通用守卫：按 View 类名 / 广告容器资源 id，拦截 addView 与 setVisibility，
 *    任何广告视图一旦挂到界面上或试图显示，一律 GONE + 高度 0；
 *  - 精确拦截：在各广告位的"加载/绑定"入口直接跳过（避免无谓的网络请求与渲染）。
 *
 * 全部 Hook 均做了容错：类/方法不存在时只记录日志，不影响 App 运行。
 */
final class AdsHook {

    /** 广告视图类的完整类名（含子类）。 */
    private static final Set<String> AD_VIEW_CLASSES = new HashSet<>(Arrays.asList(
            // 首页信息流广告条目
            "com.lolaage.tbulu.tools.ui.views.homepage.HomePageAdvertItemView",
            // 资讯列表广告
            "com.lolaage.tbulu.tools.ui.views.horizontal.NewsListADView",
            // 通用列表广告（TopOn 原生）
            "com.lolaage.tbulu.tools.ui.views.adv.list.AListAdView",
            "com.lolaage.tbulu.tools.ui.views.adv.list.TakuListAdView",
            // 多页面轮播横幅广告
            "com.lolaage.tbulu.tools.ui.views.adv.AdvScrollView",
            // 出游详情顶部广告
            "com.lolaage.tbulu.tools.ui.views.adv.OutingTopImageAdView",
            // 开屏 / 插屏
            "com.lolaage.tbulu.tools.ui.views.adv.splash.ASplashAdView",
            "com.lolaage.tbulu.tools.ui.views.adv.splash.TakuAdView",
            "com.lolaage.tbulu.tools.ui.views.adv.splash.TakuInterstitialAdView",
            "com.lolaage.tbulu.tools.ui.views.TbuluAdView",
            // 运动记录页广告
            "com.lolaage.tbulu.tools.ui.views.SportRecordAdDataView",
            "com.lolaage.tbulu.tools.ui.activity.sport.SportRecordAdView"
    ));

    /** 广告容器资源 id（运行时动态解析，避免硬编码资源编号）。 */
    private static final String[] AD_CONTAINER_ID_NAMES = {
            "flAdContainer", // 开屏广告容器
            "rlTbuluAd",     // 运动记录页广告容器
            "vBannerAD",     // 运动记录页 banner 容器
            "vAdvSroll",     // 运动记录页轮播广告
            "vNativeAD"      // 信息流原生广告容器
    };

    private static volatile Set<Integer> sAdContainerIds;
    private static final Set<String> sLoggedClasses = new HashSet<>();

    private AdsHook() {
    }

    static void install(ClassLoader cl) {
        installViewGuards();
        installSplash(cl);
        installFeedAd(cl);
        installListAds(cl);
        installAdvScroll(cl);
        installNewsAd(cl);
        installSportPage(cl);
        installOutingAd(cl);
        installAdvDialog(cl);
        installInterstitial(cl);
        XposedInit.log("AdsHook installed.");
    }

    /* ============================ 通用守卫 ============================ */

    private static void installViewGuards() {
        // 1) 广告视图一旦被加到任何容器：GONE + 高度 0
        XposedHelpers.findAndHookMethod(ViewGroup.class, "addView",
                View.class, int.class, ViewGroup.LayoutParams.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            if (param.args[0] instanceof View && isAdView((View) param.args[0])) {
                                hideAdView((View) param.args[0]);
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                });

        // 2) 任何试图把广告视图设为可见的调用：改写为 GONE
        XposedHelpers.findAndHookMethod(View.class, "setVisibility", int.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    if (!(param.thisObject instanceof View)) {
                        return;
                    }
                    View v = (View) param.thisObject;
                    int want = (Integer) param.args[0];
                    if (want == View.GONE) {
                        return;
                    }
                    if (isAdView(v) || isAdContainerId(v.getId())) {
                        param.args[0] = View.GONE;
                    }
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private static boolean isAdView(View v) {
        Class<?> c = v.getClass();
        String cn = c.getName();
        if (!cn.startsWith("com.lolaage")) {
            return false;
        }
        while (c != null) {
            if (AD_VIEW_CLASSES.contains(c.getName())) {
                if (sLoggedClasses.add(c.getName())) {
                    XposedInit.log("ad view detected: " + c.getName());
                }
                return true;
            }
            c = c.getSuperclass();
        }
        return false;
    }

    private static boolean isAdContainerId(int id) {
        if (id == 0 || sAdContainerIds == null) {
            resolveAdContainerIds();
        }
        return sAdContainerIds != null && sAdContainerIds.contains(id);
    }

    private static void resolveAdContainerIds() {
        if (sAdContainerIds != null) {
            return;
        }
        try {
            Resources res = AndroidAppHelper.currentApplication().getResources();
            Set<Integer> ids = new HashSet<>();
            for (String name : AD_CONTAINER_ID_NAMES) {
                int id = res.getIdentifier(name, "id", XposedInit.TARGET_PKG);
                if (id != 0) {
                    ids.add(id);
                }
            }
            sAdContainerIds = ids;
            XposedInit.log("ad container ids resolved: " + ids.size());
        } catch (Throwable t) {
            // Application 尚未就绪时保持 null，下次再解析
        }
    }

    /** 隐藏广告视图：GONE + 高度 0（移除 RecyclerView/Flex 布局中占位）。 */
    static void hideAdView(View v) {
        try {
            if (v.getVisibility() != View.GONE) {
                v.setVisibility(View.GONE);
            }
            ViewGroup.LayoutParams lp = v.getLayoutParams();
            if (lp != null && lp.height != 0) {
                lp.height = 0;
                v.setLayoutParams(lp);
            }
        } catch (Throwable ignored) {
        }
    }

    /* ============================ 开屏广告 ============================ */

    private static final String WELCOME_FRAGMENT =
            "com.lolaage.tbulu.tools.ui.fragment.WelcomeNormalFragment";

    private static void installSplash(ClassLoader cl) {
        Class<?> fragment = findClassSafe(cl, WELCOME_FRAGMENT);
        if (fragment != null) {
            // Oooo0O0() = 展示三方开屏广告（TopOn/taku）的调度入口
            hookAllSafe(fragment, "Oooo0O0", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    skipSplash(param);
                }
            });
            // Oooo0() = 展示自家开屏广告（Pangle/Tbulu）
            hookAllSafe(fragment, "Oooo0", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    skipSplash(param);
                }
            });
        }

        // 兜底：广告配置层直接判定"本次不展示开屏广告"
        Class<?> cfg = findClassSafe(cl, "com.lolaage.tbulu.tools.business.managers.AdConfigManager");
        if (cfg != null) {
            hookAllSafe(cfg, "isNeedShowSplashAdThisTime", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    param.setResult(false);
                }
            });
        }
    }

    /**
     * 走 App 自带的"跳过开屏广告"路径（OooOoo()：清理广告视图并回调进入主界面），
     * 等价于用户点击跳过或广告无填充，不影响任何业务功能。
     */
    private static void skipSplash(XC_MethodHook.MethodHookParam param) {
        try {
            XposedHelpers.callMethod(param.thisObject, "OooOoo");
            XposedInit.log("splash ad skipped");
        } catch (Throwable t) {
            XposedInit.log("skipSplash failed: " + t);
        }
        param.setResult(null);
    }

    /* ============================ 首页信息流广告 ============================ */

    private static void installFeedAd(ClassLoader cl) {
        // multitype 适配器条目：convert 时直接跳过并隐藏条目视图
        Class<?> general = findClassSafe(cl, "com.lolaage.tbulu.tools.list.multitype.HomePageAdvertGeneral");
        if (general != null) {
            hookAllSafe(general, "OooO0Oo", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Object holder = param.args[0];
                        Object viewObj = XposedHelpers.callMethod(holder, "OooO0OO");
                        if (viewObj instanceof View) {
                            hideAdView((View) viewObj);
                        }
                    } catch (Throwable ignored) {
                    }
                    param.setResult(null);
                }
            });
        }

        // 条目视图本体：兜底拦截数据绑定
        Class<?> itemView = findClassSafe(cl, "com.lolaage.tbulu.tools.ui.views.homepage.HomePageAdvertItemView");
        if (itemView != null) {
            hookAllSafe(itemView, "setData", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.thisObject instanceof View) {
                        hideAdView((View) param.thisObject);
                    }
                    param.setResult(null);
                }
            });
        }
    }

    /* ============================ 列表广告 ============================ */

    private static void installListAds(ClassLoader cl) {
        // TakuListAdView.OooO0O0() = 检查并加载/展示 TopOn 原生列表广告
        Class<?> takuList = findClassSafe(cl, "com.lolaage.tbulu.tools.ui.views.adv.list.TakuListAdView");
        if (takuList != null) {
            hookAllSafe(takuList, "OooO0O0", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.thisObject instanceof View) {
                        hideAdView((View) param.thisObject);
                    }
                    param.setResult(null);
                }
            });
        }
    }

    /* ============================ 轮播横幅广告（多页面） ============================ */

    private static void installAdvScroll(ClassLoader cl) {
        Class<?> adv = findClassSafe(cl, "com.lolaage.tbulu.tools.ui.views.adv.AdvScrollView");
        if (adv == null) {
            return;
        }
        // 数据入口：跳过 + 自身隐藏
        hookAllSafe(adv, "setDatas", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.thisObject instanceof View) {
                    hideAdView((View) param.thisObject);
                }
                param.setResult(null);
            }
        });
        // 该类重写了 setVisibility，单独守卫
        hookAllSafe(adv, "setVisibility", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                param.args[0] = View.GONE;
            }
        });
    }

    /* ============================ 资讯列表广告 ============================ */

    private static void installNewsAd(ClassLoader cl) {
        Class<?> news = findClassSafe(cl, "com.lolaage.tbulu.tools.ui.views.horizontal.NewsListADView");
        if (news == null) {
            return;
        }
        hookAllSafe(news, "setDatas", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.thisObject instanceof View) {
                    hideAdView((View) param.thisObject);
                }
                param.setResult(null);
            }
        });
        hookAllSafe(news, "setVisibility", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                param.args[0] = View.GONE;
            }
        });
    }

    /* ============================ 运动记录页广告 ============================ */

    private static void installSportPage(ClassLoader cl) {
        Class<?> sportData = findClassSafe(cl, "com.lolaage.tbulu.tools.ui.views.SportRecordAdDataView");
        if (sportData != null) {
            hookAllSafe(sportData, "OooO0O0", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.thisObject instanceof View) {
                        hideAdView((View) param.thisObject);
                    }
                    param.setResult(null);
                }
            });
        }

        Class<?> sportAd = findClassSafe(cl, "com.lolaage.tbulu.tools.ui.activity.sport.SportRecordAdView");
        if (sportAd != null) {
            hookAllSafe(sportAd, "setData", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.thisObject instanceof View) {
                        hideAdView((View) param.thisObject);
                    }
                    param.setResult(null);
                }
            });
        }
    }

    /* ============================ 出游详情顶部广告 ============================ */

    private static void installOutingAd(ClassLoader cl) {
        Class<?> outing = findClassSafe(cl, "com.lolaage.tbulu.tools.ui.views.adv.OutingTopImageAdView");
        if (outing != null) {
            hookAllSafe(outing, "setData", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.thisObject instanceof View) {
                        hideAdView((View) param.thisObject);
                    }
                    param.setResult(null);
                }
            });
        }
    }

    /* ============================ 弹窗广告 ============================ */

    private static void installAdvDialog(ClassLoader cl) {
        Class<?> dialog = findClassSafe(cl, "com.lolaage.tbulu.tools.ui.dialog.AdvDialog");
        if (dialog != null) {
            hookAllSafe(dialog, "show", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    param.setResult(null);
                }
            });
        }
    }

    /* ============================ 主界面插屏广告 ============================ */

    private static void installInterstitial(ClassLoader cl) {
        Class<?> ita = findClassSafe(cl, "com.lolaage.tbulu.tools.ui.views.adv.splash.TakuInterstitialAdView");
        if (ita != null) {
            // Oooo000(Activity): boolean —— 展示插屏广告
            hookAllSafe(ita, "Oooo000", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    param.setResult(Boolean.FALSE);
                }
            });
        }
    }

    /* ============================ 工具 ============================ */

    private static Class<?> findClassSafe(ClassLoader cl, String name) {
        try {
            return XposedHelpers.findClass(name, cl);
        } catch (Throwable t) {
            XposedInit.log("class not found (skip): " + name);
            return null;
        }
    }

    private static void hookAllSafe(Class<?> cls, String method, XC_MethodHook cb) {
        try {
            XposedBridge.hookAllMethods(cls, method, cb);
            XposedInit.log("hooked " + cls.getSimpleName() + "." + method);
        } catch (Throwable t) {
            XposedInit.log("hook failed: " + cls.getSimpleName() + "." + method + " " + t);
        }
    }
}
