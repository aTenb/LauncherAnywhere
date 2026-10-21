package la.launcheranywhere;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Authentic Apple TV tvOS style settings:
 * Left inspector pane with vertically centered Sony TV silhouette artwork and live contextual description,
 * Right pane with clean, single-line focusable capsule rows.
 */
public final class LauncherSettingsActivity extends Activity {
    private SharedPreferences choices;

    private ImageView ivTvSilhouette;
    private TextView tvInspectorDescription;

    private View rowColumns;
    private TextView columnsBadge;
    private int columns;

    private View rowSmb;
    private TextView smbBadge;
    private boolean showSmb;

    private View rowTopBarLabels;
    private TextView topBarLabelsBadge;
    private boolean topBarLabels;

    private View rowAppLibrary;
    private TextView appLibraryBadge;
    private boolean appLibrary;

    private View rowShowStats;
    private TextView showStatsBadge;
    private boolean showStats;

    private View rowAnimations;
    private TextView animationsBadge;
    private boolean animations;

    private View rowAdvanced;
    private TextView advancedBadge;
    private boolean advancedEffects;

    private View rowFocusScale;
    private TextView focusScaleBadge;
    private float focusScale;

    private View rowSonyTheme;
    private TextView sonyThemeBadge;
    private boolean sonyTheme;

    private View rowSettingsKeyPanel;
    private TextView settingsKeyPanelBadge;
    private boolean settingsKeyPanel;

    private View rowDefaultHome;
    private View rowTvSettings;

    private View rowFollowTheme;
    private TextView followThemeBadge;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_launcher_settings);
        choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        Config config = Config.load(this);
        columns = config.columns;
        focusScale = config.focusScale;
        showSmb = config.showSmb;
        animations = config.animations;
        advancedEffects = config.animations && config.advancedEffects;
        topBarLabels = config.topBarLabels;
        appLibrary = config.appLibrary;
        showStats = config.showStats;
        settingsKeyPanel = config.settingsKeyPanel;
        sonyTheme = choices.getBoolean("sony_tv_theme", false);

        ivTvSilhouette = findViewById(R.id.ivTvSilhouette);
        tvInspectorDescription = findViewById(R.id.tvInspectorDescription);

        // Columns row (Unified tvOS single-row interaction)
        rowColumns = findViewById(R.id.rowColumns);
        columnsBadge = findViewById(R.id.columnsBadge);
        bindRow(rowColumns, R.string.columns_label, R.string.columns_hint, v -> cycleColumns(true));
        rowColumns.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                setColumns(columns < 7 ? columns + 1 : 4);
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                setColumns(columns > 4 ? columns - 1 : 7);
                return true;
            }
            return false;
        });

        // Top-bar LAN / SMB row
        rowSmb = findViewById(R.id.rowSmb);
        smbBadge = findViewById(R.id.smbBadge);
        bindRow(rowSmb, R.string.show_top_smb, R.string.show_top_smb_hint, v -> {
            showSmb = !showSmb;
            choices.edit().putBoolean("show_top_smb", showSmb).apply();
            setResult(RESULT_OK);
            refresh();
        });

        // Top Bar Labels row
        rowTopBarLabels = findViewById(R.id.rowTopBarLabels);
        topBarLabelsBadge = findViewById(R.id.topBarLabelsBadge);
        bindRow(rowTopBarLabels, R.string.top_bar_labels_title, R.string.top_bar_labels_hint, v -> {
            topBarLabels = !topBarLabels;
            choices.edit().putBoolean("top_bar_labels", topBarLabels).apply();
            setResult(RESULT_OK);
            refresh();
        });

        // App Library row
        rowAppLibrary = findViewById(R.id.rowAppLibrary);
        appLibraryBadge = findViewById(R.id.appLibraryBadge);
        bindRow(rowAppLibrary, R.string.app_library_title, R.string.app_library_hint, v -> {
            appLibrary = !appLibrary;
            choices.edit().putBoolean("app_library", appLibrary).apply();
            setResult(RESULT_OK);
            refresh();
        });

        // System Stats row
        rowShowStats = findViewById(R.id.rowShowStats);
        showStatsBadge = findViewById(R.id.showStatsBadge);
        bindRow(rowShowStats, R.string.show_stats_title, R.string.show_stats_hint, v -> {
            showStats = !showStats;
            choices.edit().putBoolean("show_stats", showStats).apply();
            setResult(RESULT_OK);
            refresh();
        });

        // Animations row
        rowAnimations = findViewById(R.id.rowAnimations);
        animationsBadge = findViewById(R.id.animationsBadge);
        bindRow(rowAnimations, R.string.animations_title, R.string.animations_hint, v -> {
            animations = !animations;
            if (!animations) advancedEffects = false;
            choices.edit().putBoolean("animations", animations)
                    .putBoolean("advanced_effects", advancedEffects).apply();
            setResult(RESULT_OK);
            refresh();
        });

        // Advanced Effects row
        rowAdvanced = findViewById(R.id.rowAdvanced);
        advancedBadge = findViewById(R.id.advancedBadge);
        bindRow(rowAdvanced, R.string.advanced_effects_title, R.string.advanced_effects_warning, v -> {
            if (advancedEffects) {
                setAdvanced(false);
            } else {
                new AlertDialog.Builder(this)
                        .setTitle(R.string.advanced_effects)
                        .setMessage(R.string.advanced_effects_warning)
                        .setPositiveButton(R.string.enable, (dialog, which) -> setAdvanced(true))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            }
        });

        // Focus Scale row
        rowFocusScale = findViewById(R.id.rowFocusScale);
        focusScaleBadge = findViewById(R.id.focusScaleBadge);
        bindRow(rowFocusScale, R.string.focus_scale_title, R.string.focus_scale_hint, v -> cycleFocusScale(true));
        rowFocusScale.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                cycleFocusScale(true);
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                cycleFocusScale(false);
                return true;
            }
            return false;
        });

        // Sony TV Theme row
        rowSonyTheme = findViewById(R.id.rowSonyTheme);
        sonyThemeBadge = findViewById(R.id.sonyThemeBadge);
        bindRow(rowSonyTheme, R.string.sony_theme_title, R.string.sony_theme_hint, v -> {
            sonyTheme = !sonyTheme;
            choices.edit().putBoolean("sony_tv_theme", sonyTheme).apply();
            setResult(RESULT_OK);
            refresh();
        });

        // Settings Key Panel row
        rowSettingsKeyPanel = findViewById(R.id.rowSettingsKeyPanel);
        settingsKeyPanelBadge = findViewById(R.id.settingsKeyPanelBadge);
        bindRow(rowSettingsKeyPanel, R.string.settings_key_panel_title, R.string.settings_key_panel_hint, v -> {
            settingsKeyPanel = !settingsKeyPanel;
            choices.edit().putBoolean("settings_key_panel", settingsKeyPanel).apply();
            setResult(RESULT_OK);
            refresh();
        });

        // Default Home row
        rowDefaultHome = findViewById(R.id.rowDefaultHome);
        bindRow(rowDefaultHome, R.string.home_settings, R.string.home_settings_help, v -> {
            Toast.makeText(this, R.string.home_settings_help, Toast.LENGTH_LONG).show();
            open(new Intent(Settings.ACTION_HOME_SETTINGS));
        });

        // Android TV System Settings row
        rowTvSettings = findViewById(R.id.rowTvSettings);
        bindRow(rowTvSettings, R.string.tv_settings, R.string.tv_settings_hint, v ->
                open(new Intent(Settings.ACTION_SETTINGS)));

        // Follow Theme (Reset Settings) row - Placed below TV System Settings
        rowFollowTheme = findViewById(R.id.rowFollowTheme);
        followThemeBadge = findViewById(R.id.followThemeBadge);
        bindRow(rowFollowTheme, R.string.follow_theme, R.string.follow_theme_hint, v -> {
            if (!hasOnTvChoices()) return;
            choices.edit().remove("columns").remove("focus_scale").remove("animations").remove("advanced_effects")
                    .remove("top_bar_labels").remove("app_library").remove("show_stats")
                    .remove("settings_key_panel").remove("show_top_smb").remove("sony_tv_theme").commit();
            Config current = Config.load(this);
            columns = current.columns;
            focusScale = current.focusScale;
            showSmb = current.showSmb;
            animations = current.animations;
            advancedEffects = current.animations && current.advancedEffects;
            topBarLabels = current.topBarLabels;
            appLibrary = current.appLibrary;
            showStats = current.showStats;
            settingsKeyPanel = current.settingsKeyPanel;
            sonyTheme = false;
            setResult(RESULT_OK);
            refresh();
            Toast.makeText(this, R.string.follow_theme, Toast.LENGTH_SHORT).show();
        });

        refresh();
        showDescription(R.string.columns_label, R.string.columns_hint);
        rowColumns.requestFocus();
    }

    private void cycleColumns(boolean forward) {
        int next;
        if (forward) {
            next = columns >= 7 ? 4 : columns + 1;
        } else {
            next = columns <= 4 ? 7 : columns - 1;
        }
        setColumns(next);
    }

    private void setColumns(int val) {
        if (columns == val) return;
        columns = val;
        choices.edit().putInt("columns", val).apply();
        setResult(RESULT_OK);
        refresh();
    }

    private void cycleFocusScale(boolean forward) {
        int percent = Math.round(focusScale * 100f);
        if (forward) {
            percent = percent >= 120 ? 100 : percent + 1;
        } else {
            percent = percent <= 100 ? 120 : percent - 1;
        }
        setFocusScale(percent / 100f);
    }

    private void setFocusScale(float val) {
        val = Math.max(1.00f, Math.min(1.20f, Math.round(val * 100f) / 100f));
        if (Math.abs(focusScale - val) < 0.001f) return;
        focusScale = val;
        choices.edit().putFloat("focus_scale", val).apply();
        setResult(RESULT_OK);
        refresh();
    }

    private void bindRow(View row, int titleRes, int descRes, View.OnClickListener action) {
        if (row == null) return;
        row.setOnClickListener(action);
        row.setOnFocusChangeListener((v, hasFocus) -> {
            v.animate().scaleX(hasFocus ? 1.02f : 1.0f).scaleY(hasFocus ? 1.02f : 1.0f).setDuration(120).start();
            if (hasFocus) showDescription(titleRes, descRes);
        });
    }

    private void showDescription(int titleRes, int descRes) {
        if (tvInspectorDescription != null) {
            tvInspectorDescription.animate().alpha(0.2f).setDuration(80).withEndAction(() -> {
                tvInspectorDescription.setText(descRes);
                tvInspectorDescription.animate().alpha(1.0f).setDuration(120).start();
            }).start();
        }
    }

    private void setAdvanced(boolean enabled) {
        advancedEffects = enabled;
        choices.edit().putBoolean("advanced_effects", enabled).apply();
        setResult(RESULT_OK);
        refresh();
    }

    private void updateBadge(TextView badge, boolean on) {
        if (badge == null) return;
        badge.setText(on ? R.string.on : R.string.off);
        badge.setBackgroundResource(on ? R.drawable.tvos_badge_on_bg : R.drawable.tvos_badge_off_bg);
        badge.setTextColor(getColorStateList(on ? R.color.tvos_badge_on_color : R.color.tvos_badge_off_color));
    }

    private void refresh() {
        if (columnsBadge != null) {
            columnsBadge.setText(getString(R.string.columns_value, columns));
        }
        if (focusScaleBadge != null) {
            focusScaleBadge.setText(getString(R.string.focus_scale_value, focusScale));
        }
        updateBadge(smbBadge, showSmb);
        updateBadge(topBarLabelsBadge, topBarLabels);
        updateBadge(appLibraryBadge, appLibrary);
        updateBadge(showStatsBadge, showStats);
        updateBadge(animationsBadge, animations);
        updateBadge(advancedBadge, advancedEffects);
        updateBadge(sonyThemeBadge, sonyTheme);
        updateBadge(settingsKeyPanelBadge, settingsKeyPanel);

        if (ivTvSilhouette != null) {
            ivTvSilhouette.setImageResource(sonyTheme ? R.drawable.ic_sony_tv_silhouette : R.drawable.ic_generic_tv_silhouette);
        }

        if (rowAdvanced != null) {
            rowAdvanced.setEnabled(animations);
            rowAdvanced.setAlpha(animations ? 1f : 0.45f);
        }
        if (rowFocusScale != null) {
            rowFocusScale.setEnabled(animations);
            rowFocusScale.setAlpha(animations ? 1f : 0.45f);
        }

        boolean hasCustom = hasOnTvChoices();
        if (rowFollowTheme != null) {
            rowFollowTheme.setEnabled(hasCustom);
            rowFollowTheme.setAlpha(hasCustom ? 1f : 0.45f);
        }
    }

    private boolean hasOnTvChoices() {
        return choices.contains("columns") || choices.contains("focus_scale")
                || choices.contains("animations") || choices.contains("advanced_effects")
                || choices.contains("top_bar_labels") || choices.contains("app_library")
                || choices.contains("show_stats") || choices.contains("settings_key_panel")
                || choices.contains("show_top_smb") || choices.contains("sony_tv_theme");
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
        }
    }
}
