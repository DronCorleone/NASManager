package io.github.nasmanager;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.time.ZoneId;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final long PING_INTERVAL_MILLIS = 10_000L;

    private enum Tab { OVERVIEW, APPS, ALERTS, SETTINGS }

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ExecutorService pingExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private SecureConfigStore store;
    private AppConfig config;
    private DashboardData dashboard;
    private LinearLayout content;
    private LinearLayout navigation;
    private TextView titleView;
    private ScrollView mainScroll;
    private TextView serverDotView;
    private TextView serverStateView;
    private TextView lastPingView;
    private Button shutdownButton;
    private Tab currentTab = Tab.OVERVIEW;
    private boolean refreshing;
    private boolean pingInFlight;
    private boolean pingLoopActive;
    private Boolean pingReachable;
    private long lastPingEpochMillis;
    private long lastPingLatencyMillis = -1L;
    private float pullStartY;
    private boolean pullFromTop;
    private boolean exactAlarmSettingsOpened;
    private boolean dark;
    private int background;
    private int surface;
    private int text;
    private int muted;
    private int accent;
    private int border;
    private final Runnable pingTick = new Runnable() {
        @Override
        public void run() {
            probeServer();
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        store = new SecureConfigStore(this);
        applyLanguage(store.getLanguage());
        applyTheme(store.getTheme());
        super.onCreate(state);
        config = store.load();
        configurePalette();
        buildShell();
        selectTab(config.isApiConfigured() ? Tab.OVERVIEW : Tab.SETTINGS);
        if (config.isApiConfigured()) refreshDashboard();
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacks(pingTick);
        pingExecutor.shutdownNow();
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (exactAlarmSettingsOpened && config != null) {
            exactAlarmSettingsOpened = false;
            config = store.load();
            if (currentTab == Tab.SETTINGS) renderCurrentTab();
        }
        if (config != null && (config.wakeScheduleEnabled || config.shutdownScheduleEnabled)
                && ScheduleManager.canScheduleExactAlarms(this)) {
            ScheduleManager.sync(this, config);
        }
        startPingLoop();
    }

    @Override
    protected void onPause() {
        stopPingLoop();
        super.onPause();
    }

    private void applyTheme(String value) {
        if ("dark".equals(value)) setTheme(R.style.AppTheme_Dark);
        else if ("light".equals(value)) setTheme(R.style.AppTheme_Light);
        else setTheme(R.style.AppTheme);
    }

    private void applyLanguage(String value) {
        if ("system".equals(value)) return;
        Locale locale = "ru".equals(value) ? new Locale("ru") : Locale.ENGLISH;
        Locale.setDefault(locale);
        Configuration configuration = new Configuration(getResources().getConfiguration());
        configuration.setLocale(locale);
        getResources().updateConfiguration(configuration, getResources().getDisplayMetrics());
    }

    private void configurePalette() {
        String setting = store.getTheme();
        dark = "dark".equals(setting) || ("system".equals(setting)
                && (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES);
        background = Color.parseColor(dark ? "#0B1220" : "#F3F6F8");
        surface = Color.parseColor(dark ? "#162033" : "#FFFFFF");
        text = Color.parseColor(dark ? "#E8EEF7" : "#172033");
        muted = Color.parseColor(dark ? "#9DAAC0" : "#667085");
        accent = Color.parseColor(dark ? "#38D1C0" : "#0F9F91");
        border = Color.parseColor(dark ? "#26344D" : "#DDE3EA");
        getWindow().setStatusBarColor(surface);
        getWindow().setNavigationBarColor(surface);
        getWindow().getDecorView().setSystemUiVisibility(dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
    }

    private void buildShell() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(background);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(20), dp(16), dp(12), dp(12));
        header.setBackgroundColor(surface);
        titleView = label(getString(R.string.app_name), 23, text, true);
        header.addView(titleView, new LinearLayout.LayoutParams(0, dp(48), 1));
        Button refresh = iconButton("↻");
        refresh.setContentDescription(getString(R.string.refresh));
        refresh.setOnClickListener(v -> fullRefresh());
        header.addView(refresh, new LinearLayout.LayoutParams(dp(48), dp(48)));
        root.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mainScroll = new ScrollView(this);
        mainScroll.setFillViewport(true);
        mainScroll.setOverScrollMode(View.OVER_SCROLL_ALWAYS);
        mainScroll.setOnTouchListener((view, event) -> handlePullToRefresh(event));
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(16), dp(16), dp(28));
        mainScroll.addView(content, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(mainScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        navigation = new LinearLayout(this);
        navigation.setPadding(dp(4), dp(4), dp(4), dp(6));
        navigation.setBackgroundColor(surface);
        addNavButton(Tab.OVERVIEW, getString(R.string.overview));
        addNavButton(Tab.APPS, getString(R.string.apps));
        addNavButton(Tab.ALERTS, getString(R.string.alerts));
        addNavButton(Tab.SETTINGS, getString(R.string.settings));
        root.addView(navigation, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(62)));
        setContentView(root);
    }

    private void addNavButton(Tab tab, String caption) {
        Button button = new Button(this);
        button.setText(caption);
        button.setTextSize(11);
        button.setAllCaps(false);
        button.setTextColor(muted);
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setTag(tab);
        button.setOnClickListener(v -> selectTab(tab));
        navigation.addView(button, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
    }

    private void selectTab(Tab tab) {
        currentTab = tab;
        for (int i = 0; i < navigation.getChildCount(); i++) {
            Button button = (Button) navigation.getChildAt(i);
            boolean selected = button.getTag() == tab;
            button.setTextColor(selected ? accent : muted);
            button.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
        }
        titleView.setText(tab == Tab.OVERVIEW ? getString(R.string.app_name)
                : tab == Tab.APPS ? getString(R.string.apps)
                : tab == Tab.ALERTS ? getString(R.string.alerts) : getString(R.string.settings));
        renderCurrentTab();
    }

    private void renderCurrentTab() {
        content.removeAllViews();
        if (currentTab == Tab.SETTINGS) renderSettings();
        else if (currentTab == Tab.APPS) renderApps();
        else if (currentTab == Tab.ALERTS) renderAlerts();
        else renderOverview();
    }

    private void renderOverview() {
        LinearLayout serverCard = card();
        LinearLayout statusRow = row();
        boolean online = isServerOnline();
        serverDotView = label("●", 18, muted, true);
        statusRow.addView(serverDotView, new LinearLayout.LayoutParams(dp(28), ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout statusText = column();
        serverStateView = label("", 18, text, true);
        statusText.addView(serverStateView);
        String subtitle = dashboard != null && dashboard.online
                ? dashboard.hostName + (dashboard.version.isEmpty() ? "" : " · " + dashboard.version)
                : (config.isApiConfigured() ? config.normalizedUrl() : getString(R.string.configure_hint));
        statusText.addView(label(subtitle, 13, muted, false));
        statusRow.addView(statusText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        lastPingView = label("", 12, muted, false);
        lastPingView.setGravity(Gravity.END);
        statusRow.addView(lastPingView, new LinearLayout.LayoutParams(dp(98), ViewGroup.LayoutParams.WRAP_CONTENT));
        serverCard.addView(statusRow);

        LinearLayout actions = row();
        Button wake = actionButton(getString(R.string.wake), true);
        wake.setOnClickListener(v -> wakeServer());
        shutdownButton = actionButton(getString(R.string.shutdown), false);
        shutdownButton.setEnabled(online);
        shutdownButton.setOnClickListener(v -> confirmShutdown());
        actions.addView(wake, weightedButtonParams(true));
        actions.addView(shutdownButton, weightedButtonParams(false));
        serverCard.addView(actions);
        content.addView(serverCard, cardParams());
        updateServerStatusViews();

        if (dashboard == null) {
            emptyState(config.isApiConfigured() ? getString(R.string.no_data) : getString(R.string.configure_hint));
            return;
        }
        if (config.showResources) renderResourceCard();
        if (config.showPools) renderPoolsCard();
        if (config.showApps) renderAppSummary();
        if (config.showAlerts) renderAlertSummary();
    }

    private void renderResourceCard() {
        LinearLayout card = cardWithTitle(getString(R.string.resources));
        double load = dashboard.loadAverage[0];
        int cpuPercent = DashboardUiFormatter.cpuPercent(dashboard.cpuPercent);
        addMetric(card, getString(R.string.cpu_load),
                cpuPercent < 0 ? getString(R.string.no_data) : cpuPercent + "%", cpuPercent);
        card.addView(label(getString(R.string.load_average_hint), 12, muted, false));
        addKeyValue(card, getString(R.string.load_average),
                DashboardUiFormatter.formatLoadAverage(load, Locale.getDefault()));
        int memoryPercent = DashboardUiFormatter.percentage(dashboard.memoryUsed, dashboard.memoryTotal);
        String memory = dashboard.memoryTotal <= 0 ? getString(R.string.no_data)
                : memoryPercent < 0 ? getString(R.string.total_memory, formatBytes(dashboard.memoryTotal))
                : getString(R.string.used_of_percent, formatBytes(dashboard.memoryUsed),
                        formatBytes(dashboard.memoryTotal), Math.max(0, memoryPercent));
        addMetric(card, getString(R.string.memory), memory, memoryPercent);
        long hours = dashboard.uptimeSeconds / 3600;
        addKeyValue(card, getString(R.string.uptime), getString(R.string.hours_short, hours, (dashboard.uptimeSeconds % 3600) / 60));
        content.addView(card, cardParams());
    }

    private void renderPoolsCard() {
        LinearLayout card = cardWithTitle(getString(R.string.pools));
        if (dashboard.pools.isEmpty()) card.addView(label(getString(R.string.no_data), 14, muted, false));
        for (DashboardData.PoolInfo pool : dashboard.pools) {
            boolean healthy = "ONLINE".equalsIgnoreCase(pool.status) || "HEALTHY".equalsIgnoreCase(pool.status);
            LinearLayout line = row();
            LinearLayout names = column();
            names.addView(label(pool.name, 16, text, true));
            String usage = pool.size > 0 ? getString(R.string.used_of, formatBytes(pool.used), formatBytes(pool.size)) : pool.status;
            names.addView(label(usage, 13, muted, false));
            line.addView(names, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            TextView badge = badge(healthy ? getString(R.string.healthy) : pool.status,
                    healthy ? color("#16A34A") : color("#F59E0B"));
            line.addView(badge);
            card.addView(line, sectionParams());
        }
        content.addView(card, cardParams());
    }

    private void renderAppSummary() {
        LinearLayout card = cardWithTitle(getString(R.string.apps));
        int running = 0;
        int updates = 0;
        for (DashboardData.AppInfo app : dashboard.apps) {
            if (app.isRunning()) running++;
            if (app.updateAvailable) updates++;
        }
        addKeyValue(card, getString(R.string.running), getString(R.string.active_apps, running, dashboard.apps.size()));
        if (updates > 0) addKeyValue(card, getString(R.string.update_available), String.valueOf(updates));
        card.setOnClickListener(v -> selectTab(Tab.APPS));
        content.addView(card, cardParams());
    }

    private void renderAlertSummary() {
        LinearLayout card = cardWithTitle(getString(R.string.alerts));
        card.addView(label(getString(R.string.active_alerts, filteredAlertCount()), 16,
                filteredAlertCount() == 0 ? muted : color("#F59E0B"), true));
        card.setOnClickListener(v -> selectTab(Tab.ALERTS));
        content.addView(card, cardParams());
    }

    private void renderApps() {
        if (dashboard == null || !dashboard.online) {
            emptyState(config.isApiConfigured() ? getString(R.string.server_offline) : getString(R.string.configure_hint));
            return;
        }
        if (dashboard.apps.isEmpty()) {
            emptyState(getString(R.string.no_apps));
            return;
        }
        for (DashboardData.AppInfo app : dashboard.apps) {
            LinearLayout card = card();
            LinearLayout heading = row();
            LinearLayout names = column();
            names.addView(label(app.displayName, 18, text, true));
            if (!app.displayName.equals(app.name)) names.addView(label(app.name, 12, muted, false));
            heading.addView(names, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            int stateColor = app.isRunning() ? color("#16A34A")
                    : app.state.contains("DEPLOY") ? color("#F59E0B") : muted;
            heading.addView(badge(localizedState(app.state), stateColor));
            card.addView(heading);
            if (app.updateAvailable) {
                card.addView(label("● " + getString(R.string.update_available), 13, color("#F59E0B"), true), sectionParams());
            }
            HorizontalScrollView scroll = new HorizontalScrollView(this);
            scroll.setHorizontalScrollBarEnabled(false);
            LinearLayout actions = row();
            if (app.isRunning()) {
                Button stop = actionButton(getString(R.string.stop), false);
                stop.setOnClickListener(v -> runAppAction(app, "stop"));
                actions.addView(stop, compactButtonParams());
            } else {
                Button start = actionButton(getString(R.string.start), true);
                start.setOnClickListener(v -> runAppAction(app, "start"));
                actions.addView(start, compactButtonParams());
            }
            Button deploy = actionButton(getString(R.string.deploy), false);
            deploy.setOnClickListener(v -> runAppAction(app, "deploy"));
            actions.addView(deploy, compactButtonParams());
            if (app.updateAvailable) {
                Button update = actionButton(getString(R.string.update), true);
                update.setOnClickListener(v -> runAppAction(app, "update"));
                actions.addView(update, compactButtonParams());
            }
            scroll.addView(actions);
            card.addView(scroll, sectionParams());
            content.addView(card, cardParams());
        }
    }

    private void renderAlerts() {
        if (dashboard == null || !dashboard.online) {
            emptyState(config.isApiConfigured() ? getString(R.string.server_offline) : getString(R.string.configure_hint));
            return;
        }
        int visible = 0;
        for (DashboardData.AlertInfo alert : dashboard.alerts) {
            if (!passesSeverity(alert.level)) continue;
            visible++;
            LinearLayout card = card();
            LinearLayout heading = row();
            int severityColor = severityColor(alert.level);
            heading.addView(badge(alert.level, severityColor));
            TextView date = label(DashboardUiFormatter.formatAlertDate(
                    alert.date, Locale.getDefault(), ZoneId.systemDefault()), 12, muted, false);
            date.setGravity(Gravity.END);
            heading.addView(date, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            card.addView(heading);
            TextView title = label(alert.title, 17, text, true);
            card.addView(title, sectionParams());
            String alertMessage = DashboardUiFormatter.cleanAlertText(alert.message);
            if (!alertMessage.isEmpty()) card.addView(label(alertMessage, 14, muted, false));
            content.addView(card, cardParams());
        }
        if (visible == 0) emptyState(getString(R.string.no_alerts));
    }

    private void renderSettings() {
        TextView connectionTitle = label(getString(R.string.server_url), 20, text, true);
        content.addView(connectionTitle, headingParams());
        EditText url = input(getString(R.string.server_url_hint), config.serverUrl, false);
        EditText username = input(getString(R.string.username_hint), config.username, false);
        EditText password = input(getString(R.string.password_hint), config.password, true);
        EditText apiKey = input(getString(R.string.api_key_hint), config.apiKey, true);
        EditText mac = input(getString(R.string.mac_hint), config.macAddress, false);
        EditText broadcast = input(getString(R.string.broadcast_hint), config.broadcastAddress, false);
        addField(getString(R.string.server_url), url);
        addField(getString(R.string.username), username);
        addField(getString(R.string.password), password);
        addField(getString(R.string.api_key), apiKey);
        TextView connectionRequirements = label(getString(R.string.connection_requirements), 12, muted, false);
        connectionRequirements.setPadding(dp(2), dp(8), dp(2), dp(8));
        content.addView(connectionRequirements);
        addField(getString(R.string.mac_address), mac);
        addField(getString(R.string.broadcast_address), broadcast);

        TextView appearanceTitle = label(getString(R.string.theme), 20, text, true);
        content.addView(appearanceTitle, headingParams());
        Spinner theme = spinner(new String[]{getString(R.string.theme_system), getString(R.string.theme_light), getString(R.string.theme_dark)},
                indexOf(config.theme, new String[]{"system", "light", "dark"}));
        Spinner language = spinner(new String[]{getString(R.string.language_system), getString(R.string.language_english), getString(R.string.language_russian)},
                indexOf(config.language, new String[]{"system", "en", "ru"}));
        addField(getString(R.string.theme), theme);
        addField(getString(R.string.language), language);

        content.addView(label(getString(R.string.dashboard_sections), 20, text, true), headingParams());
        CheckBox showPools = checkbox(getString(R.string.show_pools), config.showPools);
        CheckBox showResources = checkbox(getString(R.string.show_resources), config.showResources);
        CheckBox showApps = checkbox(getString(R.string.show_apps), config.showApps);
        CheckBox showAlerts = checkbox(getString(R.string.show_alerts), config.showAlerts);
        content.addView(showPools);
        content.addView(showResources);
        content.addView(showApps);
        content.addView(showAlerts);

        content.addView(label(getString(R.string.notifications), 20, text, true), headingParams());
        CheckBox notifyAlerts = checkbox(getString(R.string.notify_alerts), config.notifyAlerts);
        content.addView(notifyAlerts);
        Spinner severity = spinner(new String[]{getString(R.string.severity_info), getString(R.string.severity_warning), getString(R.string.severity_critical)},
                indexOf(config.minimumSeverity, new String[]{"INFO", "WARNING", "CRITICAL"}));
        addField(getString(R.string.minimum_severity), severity);

        content.addView(label(getString(R.string.power_schedule), 20, text, true), headingParams());
        content.addView(label(getString(R.string.power_schedule_hint), 12, muted, false));
        if ((config.wakeScheduleEnabled || config.shutdownScheduleEnabled)
                && !ScheduleManager.canScheduleExactAlarms(this)) {
            LinearLayout permissionRow = row();
            permissionRow.addView(label(getString(R.string.exact_alarm_required), 13,
                    color("#F59E0B"), true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            Button permissionButton = actionButton(getString(R.string.open_settings), false);
            permissionButton.setOnClickListener(v -> openExactAlarmSettings());
            permissionRow.addView(permissionButton, new LinearLayout.LayoutParams(dp(132), dp(44)));
            content.addView(permissionRow, sectionParams());
        }
        final int[] wakeTime = {config.wakeHour, config.wakeMinute};
        final int[] shutdownTime = {config.shutdownHour, config.shutdownMinute};
        Switch wakeSchedule = scheduleSwitch(getString(R.string.scheduled_wake), config.wakeScheduleEnabled);
        Button wakeTimeButton = timeButton(wakeTime[0], wakeTime[1]);
        wakeTimeButton.setEnabled(wakeSchedule.isChecked());
        wakeSchedule.setOnCheckedChangeListener((button, checked) -> wakeTimeButton.setEnabled(checked));
        wakeTimeButton.setOnClickListener(v -> chooseTime(wakeTime, wakeTimeButton));
        content.addView(scheduleRow(wakeSchedule, wakeTimeButton), sectionParams());
        Switch shutdownSchedule = scheduleSwitch(getString(R.string.scheduled_shutdown), config.shutdownScheduleEnabled);
        Button shutdownTimeButton = timeButton(shutdownTime[0], shutdownTime[1]);
        shutdownTimeButton.setEnabled(shutdownSchedule.isChecked());
        shutdownSchedule.setOnCheckedChangeListener((button, checked) -> shutdownTimeButton.setEnabled(checked));
        shutdownTimeButton.setOnClickListener(v -> chooseTime(shutdownTime, shutdownTimeButton));
        content.addView(scheduleRow(shutdownSchedule, shutdownTimeButton), sectionParams());

        TextView privacy = label(getString(R.string.privacy_note), 12, muted, false);
        privacy.setPadding(0, dp(16), 0, dp(12));
        content.addView(privacy);

        LinearLayout buttons = row();
        Button save = actionButton(getString(R.string.save), true);
        Button test = actionButton(getString(R.string.test_connection), false);
        buttons.addView(save, weightedButtonParams(true));
        buttons.addView(test, weightedButtonParams(false));
        content.addView(buttons);
        TextView version = label(getString(R.string.version_label), 12, muted, false);
        version.setGravity(Gravity.CENTER);
        version.setPadding(0, dp(24), 0, 0);
        content.addView(version);

        Runnable readForm = () -> {
            config.serverUrl = url.getText().toString();
            config.username = username.getText().toString();
            config.password = password.getText().toString();
            config.apiKey = apiKey.getText().toString();
            config.macAddress = mac.getText().toString();
            config.broadcastAddress = broadcast.getText().toString();
            config.theme = new String[]{"system", "light", "dark"}[theme.getSelectedItemPosition()];
            config.language = new String[]{"system", "en", "ru"}[language.getSelectedItemPosition()];
            config.minimumSeverity = new String[]{"INFO", "WARNING", "CRITICAL"}[severity.getSelectedItemPosition()];
            config.showPools = showPools.isChecked();
            config.showResources = showResources.isChecked();
            config.showApps = showApps.isChecked();
            config.showAlerts = showAlerts.isChecked();
            config.notifyAlerts = notifyAlerts.isChecked();
            config.wakeScheduleEnabled = wakeSchedule.isChecked();
            config.wakeHour = wakeTime[0];
            config.wakeMinute = wakeTime[1];
            config.shutdownScheduleEnabled = shutdownSchedule.isChecked();
            config.shutdownHour = shutdownTime[0];
            config.shutdownMinute = shutdownTime[1];
        };
        save.setOnClickListener(v -> {
            readForm.run();
            if (config.wakeScheduleEnabled) {
                try {
                    WakeOnLan.parseMac(config.macAddress);
                } catch (IllegalArgumentException invalidMac) {
                    toast(R.string.schedule_wake_requires_mac);
                    return;
                }
            }
            if (config.shutdownScheduleEnabled && !config.isApiConfigured()) {
                toast(R.string.schedule_shutdown_requires_connection);
                return;
            }
            store.save(config);
            if (config.notifyAlerts && android.os.Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 100);
            }
            toast(R.string.saved);
            if ((config.wakeScheduleEnabled || config.shutdownScheduleEnabled)
                    && !ScheduleManager.canScheduleExactAlarms(this)) {
                requestExactAlarmAccess();
                return;
            }
            recreate();
        });
        test.setOnClickListener(v -> {
            readForm.run();
            test.setEnabled(false);
            executor.execute(() -> {
                try {
                    new TrueNasClient(config).loadDashboard();
                    runOnUiThread(() -> {
                        test.setEnabled(true);
                        toast(R.string.connection_ok);
                    });
                } catch (Exception error) {
                    runOnUiThread(() -> {
                        test.setEnabled(true);
                        showConnectionError(error);
                    });
                }
            });
        });
    }

    private void startPingLoop() {
        pingLoopActive = true;
        mainHandler.removeCallbacks(pingTick);
        mainHandler.post(pingTick);
    }

    private void stopPingLoop() {
        pingLoopActive = false;
        mainHandler.removeCallbacks(pingTick);
    }

    private void probeServer() {
        mainHandler.removeCallbacks(pingTick);
        if (!pingLoopActive) return;
        if (config == null || config.serverUrl == null || config.serverUrl.trim().isEmpty()) {
            pingReachable = null;
            updateServerStatusViews();
            mainHandler.postDelayed(pingTick, PING_INTERVAL_MILLIS);
            return;
        }
        if (pingInFlight) {
            mainHandler.postDelayed(pingTick, PING_INTERVAL_MILLIS);
            return;
        }
        pingInFlight = true;
        AppConfig pingConfig = config;
        pingExecutor.execute(() -> {
            ServerReachabilityProbe.Result result = new ServerReachabilityProbe().probe(pingConfig);
            runOnUiThread(() -> {
                pingInFlight = false;
                if (config == pingConfig) {
                    pingReachable = result.isReachable();
                    lastPingEpochMillis = result.checkedAtEpochMillis();
                    lastPingLatencyMillis = result.latencyMillis();
                    updateServerStatusViews();
                }
                if (pingLoopActive) mainHandler.postDelayed(pingTick, PING_INTERVAL_MILLIS);
            });
        });
    }

    private boolean isServerOnline() {
        return pingReachable != null ? pingReachable : dashboard != null && dashboard.online;
    }

    private void updateServerStatusViews() {
        if (serverDotView == null || serverStateView == null || lastPingView == null) return;
        boolean online = isServerOnline();
        serverDotView.setTextColor(refreshing ? color("#F59E0B") : online ? color("#16A34A") : color("#DC2626"));
        serverStateView.setText(refreshing ? getString(R.string.server_checking)
                : online ? getString(R.string.server_online) : getString(R.string.server_offline));
        if (lastPingEpochMillis <= 0) {
            lastPingView.setText(getString(R.string.last_ping, getString(R.string.never)));
        } else {
            String checkedAt = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date(lastPingEpochMillis));
            String pingText = getString(R.string.last_ping, checkedAt);
            if (lastPingLatencyMillis >= 0) pingText += "\n" + getString(R.string.ping_latency_ms, lastPingLatencyMillis);
            lastPingView.setText(pingText);
        }
        if (shutdownButton != null) shutdownButton.setEnabled(online);
    }

    private boolean handlePullToRefresh(MotionEvent event) {
        if (currentTab == Tab.SETTINGS) return false;
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            pullFromTop = mainScroll.getScrollY() == 0;
            pullStartY = event.getY();
        } else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            if (pullFromTop && mainScroll.getScrollY() == 0 && event.getY() - pullStartY >= dp(72)) {
                mainScroll.performClick();
                fullRefresh();
            }
            pullFromTop = false;
        } else if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            pullFromTop = false;
        }
        return false;
    }

    private void fullRefresh() {
        config = store.load();
        dashboard = null;
        pingReachable = null;
        lastPingEpochMillis = 0L;
        lastPingLatencyMillis = -1L;
        renderCurrentTab();
        refreshDashboard();
        if (pingLoopActive) {
            mainHandler.removeCallbacks(pingTick);
            mainHandler.post(pingTick);
        }
    }

    private Switch scheduleSwitch(String caption, boolean checked) {
        Switch result = new Switch(this);
        result.setText(caption);
        result.setTextColor(text);
        result.setTextSize(14);
        result.setChecked(checked);
        result.setShowText(false);
        result.setThumbTintList(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{accent, muted}));
        result.setTrackTintList(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{withAlpha(accent, 112), withAlpha(muted, 72)}));
        return result;
    }

    private LinearLayout scheduleRow(Switch toggle, Button time) {
        LinearLayout result = row();
        result.addView(toggle, new LinearLayout.LayoutParams(0, dp(52), 1));
        result.addView(time, new LinearLayout.LayoutParams(dp(104), dp(48)));
        return result;
    }

    private Button timeButton(int hour, int minute) {
        Button result = actionButton(formatTime(hour, minute), false);
        result.setContentDescription(getString(R.string.choose_time));
        return result;
    }

    private void chooseTime(int[] value, Button target) {
        new TimePickerDialog(this, (picker, hour, minute) -> {
            value[0] = hour;
            value[1] = minute;
            target.setText(formatTime(hour, minute));
        }, value[0], value[1], true).show();
    }

    private String formatTime(int hour, int minute) {
        return String.format(Locale.getDefault(), "%02d:%02d", hour, minute);
    }

    private void requestExactAlarmAccess() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.exact_alarm_title)
                .setMessage(R.string.exact_alarm_message)
                .setNegativeButton(R.string.later, (dialog, which) -> recreate())
                .setPositiveButton(R.string.open_settings, (dialog, which) -> openExactAlarmSettings())
                .show();
    }

    private void openExactAlarmSettings() {
        Intent intent = ScheduleManager.exactAlarmSettingsIntent(this);
        exactAlarmSettingsOpened = true;
        try {
            startActivity(intent);
        } catch (android.content.ActivityNotFoundException unavailable) {
            exactAlarmSettingsOpened = false;
            toast(R.string.exact_alarm_required);
        }
    }

    private void refreshDashboard() {
        if (refreshing) return;
        if (!config.isApiConfigured()) {
            toast(R.string.not_configured);
            selectTab(Tab.SETTINGS);
            return;
        }
        refreshing = true;
        if (currentTab == Tab.OVERVIEW) renderCurrentTab();
        executor.execute(() -> {
            try {
                DashboardData loaded = new TrueNasClient(config).loadDashboard();
                dashboard = loaded;
                maybeNotify(loaded);
            } catch (Exception error) {
                DashboardData offline = new DashboardData();
                offline.online = false;
                dashboard = offline;
            }
            runOnUiThread(() -> {
                refreshing = false;
                renderCurrentTab();
            });
        });
    }

    private void wakeServer() {
        if (config.macAddress.trim().isEmpty()) {
            toast(R.string.invalid_mac);
            return;
        }
        executor.execute(() -> {
            try {
                WakeOnLan.send(config.macAddress, config.broadcastAddress);
                runOnUiThread(() -> toast(R.string.wake_sent));
            } catch (Exception error) {
                runOnUiThread(() -> toast(R.string.invalid_mac));
            }
        });
    }

    private void confirmShutdown() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.shutdown_title)
                .setMessage(R.string.shutdown_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.confirm, (dialog, which) -> runServerShutdown())
                .show();
    }

    private void runServerShutdown() {
        executor.execute(() -> {
            try {
                new TrueNasClient(config).shutdown();
                runOnUiThread(() -> toast(R.string.action_started));
            } catch (Exception error) {
                runOnUiThread(() -> showActionError(getString(R.string.shutdown), error));
            }
        });
    }

    private void runAppAction(DashboardData.AppInfo app, String action) {
        toast(R.string.action_started);
        executor.execute(() -> {
            try {
                TrueNasClient client = new TrueNasClient(config);
                if ("start".equals(action)) client.startApp(app.name);
                else if ("stop".equals(action)) client.stopApp(app.name);
                else if ("deploy".equals(action)) client.deployApp(app.name);
                else client.updateApp(app.name);
                runOnUiThread(this::refreshDashboard);
            } catch (Exception error) {
                runOnUiThread(() -> showActionError(localizedAction(action) + " — " + app.displayName, error));
            }
        });
    }

    private void maybeNotify(DashboardData data) {
        if (!config.notifyAlerts) return;
        for (DashboardData.AlertInfo alert : data.alerts) {
            if (!passesSeverity(alert.level) || store.wasAlertSeen(alert.id)) continue;
            NotificationHelper.show(this, alert);
            store.markAlertSeen(alert.id);
        }
    }

    private int filteredAlertCount() {
        if (dashboard == null) return 0;
        int count = 0;
        for (DashboardData.AlertInfo alert : dashboard.alerts) if (passesSeverity(alert.level)) count++;
        return count;
    }

    private boolean passesSeverity(String level) {
        return severityRank(level) >= severityRank(config.minimumSeverity);
    }

    private static int severityRank(String level) {
        if (level == null) return 0;
        String value = level.toUpperCase(Locale.US);
        if (value.contains("CRITICAL") || value.contains("ALERT") || value.contains("EMERGENCY")) return 2;
        if (value.contains("WARN") || value.contains("ERROR")) return 1;
        return 0;
    }

    private int severityColor(String level) {
        int rank = severityRank(level);
        return rank == 2 ? color("#DC2626") : rank == 1 ? color("#F59E0B") : accent;
    }

    private String localizedState(String state) {
        if (state == null) return getString(R.string.no_data);
        if (state.equalsIgnoreCase("RUNNING") || state.equalsIgnoreCase("ACTIVE")) return getString(R.string.running);
        if (state.contains("DEPLOY")) return getString(R.string.deploying);
        if (state.equalsIgnoreCase("STOPPED") || state.equalsIgnoreCase("INACTIVE")) return getString(R.string.stopped);
        return state;
    }

    private String localizedAction(String action) {
        if ("start".equals(action)) return getString(R.string.start);
        if ("stop".equals(action)) return getString(R.string.stop);
        if ("deploy".equals(action)) return getString(R.string.deploy);
        return getString(R.string.update);
    }

    private void addMetric(LinearLayout parent, String name, String value, int percent) {
        LinearLayout heading = row();
        heading.addView(label(name, 14, muted, false), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        heading.addView(label(value, 14, text, true));
        parent.addView(heading, sectionParams());
        if (percent < 0) return;
        ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgress(Math.max(0, Math.min(100, percent)));
        progress.setProgressTintList(ColorStateList.valueOf(percent >= 90 ? color("#DC2626") : accent));
        progress.setProgressBackgroundTintList(ColorStateList.valueOf(border));
        parent.addView(progress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(7)));
    }

    private void addKeyValue(LinearLayout parent, String key, String value) {
        LinearLayout line = row();
        line.addView(label(key, 14, muted, false), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        line.addView(label(value, 14, text, true));
        parent.addView(line, sectionParams());
    }

    private void addField(String title, View field) {
        TextView caption = label(title, 13, muted, true);
        caption.setPadding(dp(2), dp(8), 0, dp(5));
        content.addView(caption);
        content.addView(field, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
    }

    private void emptyState(String message) {
        LinearLayout card = card();
        TextView icon = label("◫", 36, accent, true);
        icon.setGravity(Gravity.CENTER);
        card.addView(icon);
        TextView textView = label(message, 15, muted, false);
        textView.setGravity(Gravity.CENTER);
        textView.setPadding(dp(8), dp(12), dp(8), dp(12));
        card.addView(textView);
        content.addView(card, cardParams());
    }

    private LinearLayout cardWithTitle(String title) {
        LinearLayout result = card();
        TextView heading = label(title, 19, text, true);
        heading.setPadding(0, 0, 0, dp(8));
        result.addView(heading);
        return result;
    }

    private LinearLayout card() {
        LinearLayout result = column();
        result.setPadding(dp(16), dp(16), dp(16), dp(16));
        GradientDrawable backgroundDrawable = new GradientDrawable();
        backgroundDrawable.setColor(surface);
        backgroundDrawable.setCornerRadius(dp(18));
        backgroundDrawable.setStroke(dp(1), border);
        result.setBackground(backgroundDrawable);
        result.setElevation(dp(2));
        return result;
    }

    private LinearLayout row() {
        LinearLayout result = new LinearLayout(this);
        result.setOrientation(LinearLayout.HORIZONTAL);
        result.setGravity(Gravity.CENTER_VERTICAL);
        return result;
    }

    private LinearLayout column() {
        LinearLayout result = new LinearLayout(this);
        result.setOrientation(LinearLayout.VERTICAL);
        return result;
    }

    private TextView label(String value, int sp, int color, boolean bold) {
        TextView result = new TextView(this);
        result.setText(value);
        result.setTextSize(sp);
        result.setTextColor(color);
        if (bold) result.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        result.setLineSpacing(0, 1.12f);
        return result;
    }

    private TextView badge(String value, int badgeColor) {
        TextView result = label(value, 11, badgeColor, true);
        result.setGravity(Gravity.CENTER);
        result.setPadding(dp(10), dp(5), dp(10), dp(5));
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(withAlpha(badgeColor, dark ? 48 : 28));
        shape.setCornerRadius(dp(20));
        result.setBackground(shape);
        return result;
    }

    private Button iconButton(String icon) {
        Button result = new Button(this);
        result.setText(icon);
        result.setTextSize(25);
        result.setTextColor(accent);
        result.setBackgroundColor(Color.TRANSPARENT);
        result.setPadding(0, 0, 0, 0);
        return result;
    }

    private Button actionButton(String caption, boolean primary) {
        Button result = new Button(this);
        result.setText(caption);
        result.setTextSize(13);
        result.setAllCaps(false);
        result.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        result.setTextColor(primary ? Color.WHITE : accent);
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(primary ? accent : Color.TRANSPARENT);
        drawable.setCornerRadius(dp(12));
        drawable.setStroke(dp(1), accent);
        result.setBackground(drawable);
        return result;
    }

    private EditText input(String hint, String value, boolean password) {
        EditText result = new EditText(this);
        result.setText(value);
        result.setHint(hint);
        result.setTextSize(15);
        result.setSingleLine(true);
        result.setTextColor(text);
        result.setHintTextColor(muted);
        result.setPadding(dp(14), 0, dp(14), 0);
        if (password) result.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(surface);
        drawable.setCornerRadius(dp(12));
        drawable.setStroke(dp(1), border);
        result.setBackground(drawable);
        return result;
    }

    private Spinner spinner(String[] values, int selected) {
        Spinner result = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, values) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView view = (TextView) super.getView(position, convertView, parent);
                view.setTextColor(text);
                view.setTextSize(15);
                return view;
            }
        };
        result.setAdapter(adapter);
        result.setSelection(selected);
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(surface);
        drawable.setCornerRadius(dp(12));
        drawable.setStroke(dp(1), border);
        result.setBackground(drawable);
        return result;
    }

    private CheckBox checkbox(String caption, boolean checked) {
        CheckBox result = new CheckBox(this);
        result.setText(caption);
        result.setTextColor(text);
        result.setTextSize(14);
        result.setChecked(checked);
        result.setButtonTintList(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{accent, muted}));
        result.setPadding(0, dp(2), 0, dp(2));
        return result;
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, dp(13));
        return params;
    }

    private LinearLayout.LayoutParams sectionParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(11), 0, 0);
        return params;
    }

    private LinearLayout.LayoutParams headingParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(18), 0, dp(8));
        return params;
    }

    private LinearLayout.LayoutParams weightedButtonParams(boolean first) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(48), 1);
        params.setMargins(first ? 0 : dp(5), dp(16), first ? dp(5) : 0, 0);
        return params;
    }

    private LinearLayout.LayoutParams compactButtonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(112), dp(44));
        params.setMargins(0, 0, dp(8), 0);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int color(String value) {
        return Color.parseColor(value);
    }

    private static int withAlpha(int value, int alpha) {
        return Color.argb(alpha, Color.red(value), Color.green(value), Color.blue(value));
    }

    private static int indexOf(String value, String[] choices) {
        for (int i = 0; i < choices.length; i++) if (choices[i].equals(value)) return i;
        return 0;
    }

    private static String formatBytes(long bytes) {
        if (bytes <= 0) return "0 B";
        String[] units = {"B", "KB", "MB", "GB", "TB", "PB"};
        double value = bytes;
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(Locale.getDefault(), value >= 10 ? "%.0f %s" : "%.1f %s", value, units[unit]);
    }

    private void showActionError(String operation, Exception error) {
        TextView details = label(getString(R.string.action_failed_detail,
                operation, DashboardUiFormatter.friendlyError(error)), 14, text, false);
        details.setTextIsSelectable(true);
        details.setPadding(dp(24), dp(8), dp(24), 0);
        new AlertDialog.Builder(this)
                .setTitle(R.string.action_failed_title)
                .setView(details)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void showConnectionError(Exception error) {
        TextView details = label(getString(R.string.connection_failed_detail,
                DashboardUiFormatter.friendlyError(error)), 14, text, false);
        details.setTextIsSelectable(true);
        details.setPadding(dp(24), dp(8), dp(24), 0);
        new AlertDialog.Builder(this)
                .setTitle(R.string.connection_failed_title)
                .setView(details)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void toast(int message) {
        toast(getString(message));
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
