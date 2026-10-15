package com.tbulu.adfree;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.util.Log;

import java.util.Map;

/**
 * 只读配置桥（模块 App 进程 ↔ Hook 进程）。v1.21 新增。
 *
 * 背景：LSPosed 的「New XSharedPreferences」（模块 Manifest 的 xposedsharedprefs 声明）
 * 已被官方标记为废弃、计划 2.3.0 移除；且非该机制下，Hook 进程跨应用直读
 * /data/data/<模块>/shared_prefs/ 会被 SELinux/权限隔离阻断，不可行。
 * 因此配置通道改为标准 Android IPC：
 *
 *  - 设置界面（模块 App 进程）用 MODE_PRIVATE 正常写 `settings` 偏好文件（见 {@link Prefs#ui}）；
 *  - 本 Provider 对外暴露该文件的 Boolean 开关（authority = applicationId + ".prefs"，
 *    与 {@link Prefs#PROVIDER_AUTHORITY} 及 AndroidManifest.xml 中的声明保持一致）；
 *  - Hook（两步路）进程通过 ContentResolver 查询 content://<authority>/settings 读取（见 {@link Prefs}）。
 *
 * 该通道不依赖任何 LSPosed 私有机制：1.9.x / 2.x 及以后版本通用。
 * 数据面仅包含开关布尔值，不涉及用户敏感信息；Provider 只读（增删改一律拒绝）。
 */
public final class PrefsProvider extends ContentProvider {

    private static final String LOG_TAG = "TbuluAdFree";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        MatrixCursor cursor = new MatrixCursor(new String[]{Prefs.COLUMN_KEY, Prefs.COLUMN_VALUE});
        Context context = getContext();
        if (context == null) {
            return cursor;
        }
        try {
            SharedPreferences prefs = Prefs.ui(context);
            for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
                Object value = entry.getValue();
                if (value instanceof Boolean) {
                    cursor.addRow(new Object[]{entry.getKey(), ((Boolean) value) ? 1 : 0});
                }
            }
            Log.i(LOG_TAG, "PrefsProvider: served " + cursor.getCount()
                    + " switches to " + getCallingPackage());
        } catch (Throwable t) {
            // 读取失败返回空游标：Hook 侧会按内置默认值行事
            Log.w(LOG_TAG, "PrefsProvider: query failed", t);
        }
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("read-only provider");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read-only provider");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read-only provider");
    }
}
