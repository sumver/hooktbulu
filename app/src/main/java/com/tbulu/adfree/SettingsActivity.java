package com.tbulu.adfree;

import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.text.method.LinkMovementMethod;
import android.text.util.Linkify;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.widget.TextViewCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.divider.MaterialDivider;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模块设置界面（Material Design 3）。
 *
 * 与 Hook 进程的通信：写入本模块自己的 SharedPreferences（{@code settings}），
 * Hook 进程通过 XSharedPreferences 读取同名文件（见 {@link Prefs}）。
 * 开关保存后最长约 1 秒内在新的交互中生效。
 */
public class SettingsActivity extends AppCompatActivity {

    /** 单个开关项。 */
    private static final class SwitchItem {
        final String key;
        final String title;
        final String summary;

        SwitchItem(String key, String title, String summary) {
            this.key = key;
            this.title = title;
            this.summary = summary;
        }
    }

    private SharedPreferences prefs;
    private LinearLayout container;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(R.string.app_name);
        toolbar.setSubtitle(R.string.settings_subtitle);

        prefs = Prefs.ui(this);
        seedDefaults();

        container = findViewById(R.id.container);
        buildContent();
    }

    /** 首次打开时写入全部默认值（true），保证 Hook 进程总能读到完整配置。 */
    private void seedDefaults() {
        SharedPreferences.Editor editor = prefs.edit();
        boolean changed = false;
        for (String key : Prefs.allKeys()) {
            if (!prefs.contains(key)) {
                editor.putBoolean(key, true);
                changed = true;
            }
        }
        if (changed) {
            editor.apply();
        }
    }

    private void buildContent() {
        container.removeAllViews();

        Map<String, List<SwitchItem>> groups = new LinkedHashMap<>();
        groups.put(getString(R.string.group_purify), Arrays.asList(
                new SwitchItem(Prefs.K_ADS,
                        getString(R.string.switch_ads),
                        getString(R.string.switch_ads_summary))));
        groups.put(getString(R.string.group_mine), Arrays.asList(
                new SwitchItem(Prefs.K_VIP_BANNER,
                        getString(R.string.switch_vip_banner),
                        getString(R.string.switch_vip_banner_summary)),
                new SwitchItem(Prefs.K_VIP_SERVICE,
                        getString(R.string.switch_vip_service),
                        getString(R.string.switch_vip_service_summary)),
                new SwitchItem(Prefs.K_DOCK_BADGE,
                        getString(R.string.switch_dock_badge),
                        getString(R.string.switch_dock_badge_summary)),
                new SwitchItem(Prefs.K_UPGRADE,
                        getString(R.string.switch_upgrade),
                        "")));
        groups.put(getString(R.string.group_share), Arrays.asList(
                new SwitchItem(Prefs.K_SHARE_CLEAN,
                        getString(R.string.switch_share_clean),
                        getString(R.string.switch_share_clean_summary)),
                new SwitchItem(Prefs.K_SHARE_CUSTOM,
                        getString(R.string.switch_share_custom),
                        getString(R.string.switch_share_custom_summary))));
        groups.put(getString(R.string.group_interact), Arrays.asList(
                new SwitchItem(Prefs.K_EQUIPMENT,
                        getString(R.string.switch_equipment),
                        getString(R.string.switch_equipment_summary))));
        groups.put(getString(R.string.group_vip), Arrays.asList(
                new SwitchItem(Prefs.K_VIP_WATERMARK,
                        getString(R.string.switch_watermark),
                        getString(R.string.switch_watermark_summary)),
                new SwitchItem(Prefs.K_VIP_DIY_HOME,
                        getString(R.string.switch_diy_home),
                        getString(R.string.switch_diy_home_summary)),
                new SwitchItem(Prefs.K_VIP_PENDANT,
                        getString(R.string.switch_pendant),
                        getString(R.string.switch_pendant_summary)),
                new SwitchItem(Prefs.K_VIP_STYLE,
                        getString(R.string.switch_style),
                        getString(R.string.switch_style_summary)),
                new SwitchItem(Prefs.K_VIP_SHARE,
                        getString(R.string.switch_share),
                        getString(R.string.switch_share_summary))));

        for (Map.Entry<String, List<SwitchItem>> entry : groups.entrySet()) {
            addGroup(entry.getKey(), entry.getValue());
        }
        addHintCard();
    }

    /** 一组开关 = 分组标题 + 一张 MD3 卡片（行之间带分割线）。 */
    private void addGroup(String title, List<SwitchItem> items) {
        TextView header = new TextView(this);
        TextViewCompat.setTextAppearance(header,
                com.google.android.material.R.style.TextAppearance_Material3_TitleSmall);
        header.setText(title);
        header.setTextColor(MaterialColors.getColor(header,
                com.google.android.material.R.attr.colorPrimary));
        LinearLayout.LayoutParams headerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        headerParams.topMargin = dp(16);
        headerParams.bottomMargin = dp(8);
        headerParams.leftMargin = dp(4);
        container.addView(header, headerParams);

        MaterialCardView card = new MaterialCardView(this);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        card.setLayoutParams(cardParams);
        card.setCardElevation(0f);
        card.setStrokeWidth(dp(1));
        card.setStrokeColor(ColorStateList.valueOf(MaterialColors.getColor(card,
                com.google.android.material.R.attr.colorOutlineVariant)));
        card.setRadius(dp(12));

        LinearLayout cardInner = new LinearLayout(this);
        cardInner.setOrientation(LinearLayout.VERTICAL);
        card.addView(cardInner, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        for (int i = 0; i < items.size(); i++) {
            SwitchItem item = items.get(i);
            View row = LayoutInflater.from(this).inflate(R.layout.item_switch, cardInner, false);
            TextView tvTitle = row.findViewById(R.id.tv_title);
            TextView tvSummary = row.findViewById(R.id.tv_summary);
            MaterialSwitch switchWidget = row.findViewById(R.id.switch_widget);

            tvTitle.setText(item.title);
            if (item.summary == null || item.summary.isEmpty()) {
                tvSummary.setVisibility(View.GONE);
            } else {
                tvSummary.setText(item.summary);
            }
            switchWidget.setChecked(prefs.getBoolean(item.key, true));
            switchWidget.setOnCheckedChangeListener((buttonView, isChecked) -> {
                prefs.edit().putBoolean(item.key, isChecked).apply();
                // 注意：这里不能用 XposedInit.log —— 模块 App 进程里没有 Xposed API 类
                // （de.robv.android.xposed.* 为 compileOnly），引用会导致 NoClassDefFoundError。
                Log.i("TbuluAdFree", "settings: " + item.key + " -> " + isChecked);
            });
            row.setOnClickListener(v -> switchWidget.toggle());
            cardInner.addView(row);

            if (i != items.size() - 1) {
                MaterialDivider divider = new MaterialDivider(this);
                cardInner.addView(divider, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
        }
        container.addView(card);
    }

    private void addHintCard() {
        TextView header = new TextView(this);
        TextViewCompat.setTextAppearance(header,
                com.google.android.material.R.style.TextAppearance_Material3_TitleSmall);
        header.setText(R.string.settings_hint_title);
        header.setTextColor(MaterialColors.getColor(header,
                com.google.android.material.R.attr.colorPrimary));
        LinearLayout.LayoutParams headerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        headerParams.topMargin = dp(16);
        headerParams.bottomMargin = dp(8);
        headerParams.leftMargin = dp(4);
        container.addView(header, headerParams);

        MaterialCardView card = new MaterialCardView(this);
        card.setCardElevation(0f);
        card.setStrokeWidth(dp(1));
        card.setStrokeColor(ColorStateList.valueOf(MaterialColors.getColor(card,
                com.google.android.material.R.attr.colorOutlineVariant)));
        card.setRadius(dp(12));

        TextView hint = new TextView(this);
        TextViewCompat.setTextAppearance(hint,
                com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        hint.setText(R.string.settings_hint);
        hint.setTextColor(MaterialColors.getColor(hint,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        hint.setLineSpacing(dp(2), 1f);
        hint.setPadding(dp(16), dp(14), dp(16), dp(14));
        // 开源地址转成可点击链接（点击用浏览器打开）
        hint.setMovementMethod(LinkMovementMethod.getInstance());
        hint.setLinkTextColor(MaterialColors.getColor(hint,
                com.google.android.material.R.attr.colorPrimary));
        Linkify.addLinks(hint, Linkify.WEB_URLS);
        card.addView(hint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        container.addView(card);
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
