package com.tbulu.adfree;

import android.app.AndroidAppHelper;
import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 临时调试工具：dump 壳解密后的内存 dex。
 *
 * 9.2.9 百度加固（com.sagittarius.v6）的运行链路（已由明文 classes.dex 反编译确认）：
 *   StubApplication.attachBaseContext
 *     -> A.n1(...)                     （native，解密 assets/baiduprotect*.i.dex）
 *     -> A.a1(ByteBuffer) / A.a2(ByteBuffer[])        （native 回调到 Java）
 *     -> h.a(Context, ByteBuffer/[])                  （明文壳类 com.sagittarius.v6.b.h）
 *     -> p.a(ClassLoader, ByteBuffer[])               （明文壳类 com.sagittarius.v6.b.p）
 *        -> 反射调用现有 PathClassLoader 的 DexPathList.makeInMemoryDexElements(ByteBuffer[], List)
 *        -> 把返回值写回 pathList.dexElements（不新建 ClassLoader）
 *
 * 因此最可靠的抓取点是明文壳类的两个静态交接方法：
 *   com.sagittarius.v6.b.h.a(Context, ByteBuffer/[])   —— 带 Context 参数，可直接定位输出目录
 *   com.sagittarius.v6.b.p.a(ClassLoader, ByteBuffer[]) —— 交给 DexPathList 前的最后一步
 * 以及 native 回调入口 com.sagittarius.v6.A.a1/a2。
 *
 * 备选（框架层，可能不命中）：dalvik.system.DexFile / InMemoryDexFile 构造器。
 * 产物写到 App 自己的 external files 目录（无需存储权限，root 可 pull）。
 */
final class DexDumpHook {

    /**
     * 临时脱壳工具开关。9.2.9 定位完成后已关闭（置 false）；如需再次 dump 内存 dex，
     * 改回 true 并在 XposedInit 中传入 lpparam.classLoader 调用 install()。
     */
    private static final boolean ENABLED = false;
    private static final java.util.Set<String> sDumped = ConcurrentHashMap.newKeySet();
    private static volatile File sDumpDir;

    private DexDumpHook() {
    }

    static void install(ClassLoader cl) {
        if (!ENABLED) {
            return;
        }
        // 1) 明文壳类交接点（9.2.9 专属，handleLoadPackage 阶段即可解析）
        hookAllNamed(cl, "com.sagittarius.v6.A", "a1", "shell A.a1");
        hookAllNamed(cl, "com.sagittarius.v6.A", "a2", "shell A.a2");
        hookAllNamed(cl, "com.sagittarius.v6.b.h", "a", "shell b.h.a");
        hookAllNamed(cl, "com.sagittarius.v6.b.p", "a", "shell b.p.a");

        // 2) 框架层兜底：InMemoryDexFile / DexFile 构造器（若壳直接 new 会命中）
        hookCtors("dalvik.system.InMemoryDexFile", "InMemoryDexFile ctor");
        hookCtors("dalvik.system.DexFile", "DexFile ctor");
        XposedInit.log("DexDumpHook installed.");
    }

    /** hook 类上所有名为 method 的方法，在 after 里扫描 ByteBuffer / ByteBuffer[] 参数。 */
    private static void hookAllNamed(final ClassLoader cl, String className, final String method, final String label) {
        try {
            Class<?> cls = XposedHelpers.findClass(className, cl);
            XposedBridge.hookAllMethods(cls, method, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        StringBuilder sb = new StringBuilder("dexdump " + label + " called args[");
                        for (int i = 0; i < param.args.length; i++) {
                            Object a = param.args[i];
                            sb.append(i).append("=").append(a == null ? "null" : a.getClass().getSimpleName()).append(";");
                        }
                        sb.append("]");
                        XposedInit.log(sb.toString());
                        for (Object a : param.args) {
                            if (a instanceof ByteBuffer[]) {
                                dumpAll((ByteBuffer[]) a);
                            } else if (a instanceof ByteBuffer) {
                                dumpOne((ByteBuffer) a);
                            }
                        }
                        if (param.args.length > 0 && param.args[0] instanceof Context) {
                            rememberDir((Context) param.args[0]);
                        }
                    } catch (Throwable t) {
                        XposedInit.log("dexdump hook callback failed: " + t);
                    }
                }
            });
            XposedInit.log("DexDumpHook: " + label + " hooked (" + cls.getName() + "." + method + ")");
        } catch (Throwable t) {
            XposedInit.log("DexDumpHook: " + label + " hook failed: " + t);
        }
    }

    private static void hookCtors(String className, String label) {
        try {
            Class<?> cls = Class.forName(className);
            XposedBridge.hookAllConstructors(cls, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        for (Object a : param.args) {
                            if (a instanceof ByteBuffer) {
                                dumpOne((ByteBuffer) a);
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
            XposedInit.log("DexDumpHook: " + label + " hooked");
        } catch (Throwable t) {
            XposedInit.log("DexDumpHook: " + label + " hook failed: " + t);
        }
    }

    private static void rememberDir(Context ctx) {
        try {
            File dir = ctx.getExternalFilesDir(null);
            if (dir != null) {
                sDumpDir = new File(dir, "dexdump");
                XposedInit.log("dexdump dir = " + sDumpDir);
            }
        } catch (Throwable ignored) {
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02x", x & 0xff));
        }
        return sb.toString();
    }

    private static void dumpAll(ByteBuffer[] bufs) {
        for (ByteBuffer b : bufs) {
            dumpOne(b);
        }
    }

    private static void dumpOne(ByteBuffer buf) {
        try {
            if (buf == null || buf.remaining() < 8) {
                XposedInit.log("dexdump skip: remaining="
                        + (buf == null ? "null" : String.valueOf(buf.remaining())));
                return;
            }
            ByteBuffer ro = buf.duplicate();
            ro.order(ByteOrder.LITTLE_ENDIAN);
            ro.position(0);
            byte[] head = new byte[4];
            ro.get(head);
            // dex 魔数 = 64 65 78 0a ("dex\n")，直接按字节判断（String 比较易错）
            if (head[0] != 0x64 || head[1] != 0x65 || head[2] != 0x78 || head[3] != 0x0A) {
                XposedInit.log("dexdump skip: bad magic " + hex(head)
                        + " cap=" + buf.capacity() + " rem=" + buf.remaining());
                return; // 非 dex 魔数
            }
            int size = ro.getInt(32); // file_size
            if (size <= 0 || size > buf.capacity()) {
                size = buf.capacity();
            }
            String key = size + "_" + (ro.getInt(56)); // file_size + signature 前 4 字节做去重
            if (!sDumped.add(key)) {
                return;
            }
            byte[] out = new byte[size];
            ro.position(0);
            ro.get(out, 0, Math.min(size, ro.remaining()));
            File dir = sDumpDir;
            if (dir == null) {
                // 兜底：应用私有 external 目录或公开 Android/data 路径
                try {
                    Context ctx = AndroidAppHelper.currentApplication();
                    if (ctx != null && ctx.getExternalFilesDir(null) != null) {
                        dir = new File(ctx.getExternalFilesDir(null), "dexdump");
                    }
                } catch (Throwable ignored) {
                }
            }
            if (dir == null) {
                dir = new File(
                        android.os.Environment.getExternalStorageDirectory(),
                        "Android/data/" + XposedInit.TARGET_PKG + "/files/dexdump");
            }
            dir.mkdirs();
            File f = new File(dir, "dump_" + System.currentTimeMillis() + "_" + size + "_" + sDumped.size() + ".dex");
            FileOutputStream fos = new FileOutputStream(f);
            FileChannel ch = fos.getChannel();
            ch.write(ByteBuffer.wrap(out));
            ch.close();
            fos.close();
            XposedInit.log("dexdump wrote " + f.getAbsolutePath() + " (" + size + " bytes)");
        } catch (Throwable t) {
            XposedInit.log("dexdump one failed: " + t);
        }
    }
}
