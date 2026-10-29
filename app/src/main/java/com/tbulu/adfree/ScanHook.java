package com.tbulu.adfree;

import java.lang.reflect.Method;
import java.util.Enumeration;

import de.robv.android.xposed.XposedHelpers;

/**
 * 临时调试工具：9.2.9 混淆名漂移后，在运行时按方法指纹扫描定位新类名。
 * 通过反射读取 PathClassLoader 的 DexPathList/DexFile.entries() 枚举壳解密后的全部类。
 */
final class ScanHook {

    private ScanHook() {
    }

    static void scan(ClassLoader cl) {
        // 1) 会员单例：9.2.8 指纹 = 方法集含 OooOooO(VIP||SVIP) + OooOoo0(SVIP) [+ OooOoOO(VIP)]
        scanByMethods(cl, new String[]{"OooOooO", "OooOoo0"}, "AUTH");

        // 2) 升级管理器：com.lolaage.tbulu.tools.upgrade 包内含 OooOOOo 的类
        scanPackage(cl, "com.lolaage.tbulu.tools.upgrade.", "UPGRADE");

        // 3) 消息红点回调：主 fragment 的内部类及方法
        try {
            Class<?> frag = XposedHelpers.findClassIfExists(
                    "com.lolaage.tbulu.tools.ui.fragment.main.o0000", cl);
            if (frag != null) {
                StringBuilder sb = new StringBuilder("SCAN MSG fragment inners:");
                for (Class<?> inner : frag.getDeclaredClasses()) {
                    sb.append("\n  ").append(inner.getName()).append(" -> ").append(methodNames(inner));
                }
                XposedInit.log(sb.toString());
            }
        } catch (Throwable t) {
            XposedInit.log("SCAN MSG failed: " + t);
        }

        // 4) 轨迹叠加：o00O.OooO0O0 及内部类是否仍在
        probe(cl, "o00O.OooO0O0", "OVERLAY-outer");
        probe(cl, "o00O.OooO0O0$Oooo000", "OVERLAY-c1");
        probe(cl, "o00O.OooO0O0$o000oOoO", "OVERLAY-c2");
    }

    private static void probe(ClassLoader cl, String name, String tag) {
        Class<?> c = XposedHelpers.findClassIfExists(name, cl);
        XposedInit.log("SCAN " + tag + " " + name + " -> " + (c == null ? "MISSING"
                : "FOUND methods=" + methodNames(c)));
    }

    private static String methodNames(Class<?> c) {
        StringBuilder sb = new StringBuilder("[");
        try {
            Method[] ms = c.getDeclaredMethods();
            for (int i = 0; i < ms.length && i < 25; i++) {
                if (i > 0) {
                    sb.append(",");
                }
                sb.append(ms[i].getName());
            }
        } catch (Throwable ignored) {
        }
        return sb.append("]").toString();
    }

    private static void scanByMethods(ClassLoader cl, String[] required, String tag) {
        int n = 0;
        for (Enumeration<String> e = enumerate(cl); e != null && e.hasMoreElements(); ) {
            String name = e.nextElement();
            if (!isObfName(name)) {
                continue;
            }
            try {
                Class<?> c = cl.loadClass(name);
                int hit = 0;
                for (Method m : c.getDeclaredMethods()) {
                    for (String r : required) {
                        if (m.getName().equals(r)) {
                            hit++;
                            break;
                        }
                    }
                }
                if (hit == required.length) {
                    XposedInit.log("SCAN " + tag + " HIT: " + name + " methods=" + methodNames(c));
                    n++;
                }
            } catch (Throwable ignored) {
            }
        }
        XposedInit.log("SCAN " + tag + " done, hits=" + n);
    }

    private static void scanPackage(ClassLoader cl, String prefix, String tag) {
        int n = 0;
        for (Enumeration<String> e = enumerate(cl); e != null && e.hasMoreElements(); ) {
            String name = e.nextElement();
            if (!name.startsWith(prefix)) {
                continue;
            }
            try {
                Class<?> c = cl.loadClass(name);
                XposedInit.log("SCAN " + tag + " class: " + name + " methods=" + methodNames(c));
                n++;
            } catch (Throwable ignored) {
            }
        }
        XposedInit.log("SCAN " + tag + " done, classes=" + n);
    }

    private static boolean isObfName(String name) {
        int dot = name.lastIndexOf('.');
        int dollar = name.indexOf('$');
        String pkg = name.substring(0, dot < 0 ? 0 : dot);
        String cls = name.substring(dot + 1);
        // 无包名或单层混淆包（形如 o00OOOOo / o00O）下的类
        return pkg.isEmpty() || pkg.matches("[a-zA-Z0-9_$]+")
                && cls.matches("[a-zA-Z0-9_$]+")
                && (dollar < 0 || name.length() < 60);
    }

    private static Enumeration<String> enumerate(ClassLoader cl) {
        try {
            Object pathList = XposedHelpers.getObjectField(cl, "pathList");
            Object[] elements = (Object[]) XposedHelpers.getObjectField(pathList, "dexElements");
            java.util.List<Enumeration<String>> enums = new java.util.ArrayList<>();
            for (Object el : elements) {
                Object dexFile = XposedHelpers.getObjectField(el, "dexFile");
                if (dexFile == null) {
                    continue;
                }
                Object entries = XposedHelpers.callMethod(dexFile, "entries");
                if (entries instanceof Enumeration) {
                    enums.add((Enumeration<String>) entries);
                }
            }
            final java.util.List<Enumeration<String>> list = enums;
            return new Enumeration<String>() {
                private int idx;

                @Override
                public boolean hasMoreElements() {
                    while (idx < list.size() && !list.get(idx).hasMoreElements()) {
                        idx++;
                    }
                    return idx < list.size();
                }

                @Override
                public String nextElement() {
                    hasMoreElements();
                    return list.get(idx).nextElement();
                }
            };
        } catch (Throwable t) {
            XposedInit.log("SCAN enumerate failed: " + t);
            return null;
        }
    }
}
