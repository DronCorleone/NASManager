package io.github.nasmanager;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Locale;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class SecureConfigStore {
    private static final String PREFS = "nas_manager";
    private static final String KEY_ALIAS = "nas_manager_api_key";
    private final SharedPreferences preferences;
    private final Context context;

    SecureConfigStore(Context context) {
        this.context = context.getApplicationContext();
        preferences = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    AppConfig load() {
        AppConfig config = new AppConfig();
        config.serverUrl = preferences.getString("server_url", "");
        config.username = preferences.getString("username", "");
        config.password = decrypt(preferences.getString("password", ""));
        config.apiKey = decrypt(preferences.getString("api_key", ""));
        config.macAddress = preferences.getString("mac", "");
        config.broadcastAddress = preferences.getString("broadcast", "255.255.255.255");
        config.theme = preferences.getString("theme", "system");
        config.language = preferences.getString("language", "system");
        config.minimumSeverity = preferences.getString("minimum_severity", "WARNING");
        config.showPools = preferences.getBoolean("show_pools", true);
        config.showResources = preferences.getBoolean("show_resources", true);
        config.showApps = preferences.getBoolean("show_apps", true);
        config.showAlerts = preferences.getBoolean("show_alerts", true);
        config.notifyAlerts = preferences.getBoolean("notify_alerts", true);
        config.wakeScheduleEnabled = preferences.getBoolean("wake_schedule_enabled", false);
        config.wakeHour = preferences.getInt("wake_hour", 8);
        config.wakeMinute = preferences.getInt("wake_minute", 0);
        config.shutdownScheduleEnabled = preferences.getBoolean("shutdown_schedule_enabled", false);
        config.shutdownHour = preferences.getInt("shutdown_hour", 23);
        config.shutdownMinute = preferences.getInt("shutdown_minute", 0);
        return config;
    }

    void save(AppConfig config) {
        preferences.edit()
                .putString("server_url", config.normalizedUrl())
                .putString("username", config.username.trim())
                .putString("password", encrypt(config.password))
                .putString("api_key", encrypt(config.apiKey))
                .putString("mac", config.macAddress.trim().toUpperCase(Locale.US))
                .putString("broadcast", config.broadcastAddress.trim())
                .putString("theme", config.theme)
                .putString("language", config.language)
                .putString("minimum_severity", config.minimumSeverity)
                .putBoolean("show_pools", config.showPools)
                .putBoolean("show_resources", config.showResources)
                .putBoolean("show_apps", config.showApps)
                .putBoolean("show_alerts", config.showAlerts)
                .putBoolean("notify_alerts", config.notifyAlerts)
                .putBoolean("wake_schedule_enabled", config.wakeScheduleEnabled)
                .putInt("wake_hour", config.wakeHour)
                .putInt("wake_minute", config.wakeMinute)
                .putBoolean("shutdown_schedule_enabled", config.shutdownScheduleEnabled)
                .putInt("shutdown_hour", config.shutdownHour)
                .putInt("shutdown_minute", config.shutdownMinute)
                .apply();
        ScheduleManager.sync(context, config);
    }

    String getTheme() {
        return preferences.getString("theme", "system");
    }

    String getLanguage() {
        return preferences.getString("language", "system");
    }

    boolean wasAlertSeen(String id) {
        return preferences.getBoolean("alert_seen_" + id, false);
    }

    void markAlertSeen(String id) {
        preferences.edit().putBoolean("alert_seen_" + id, true).apply();
    }

    private String encrypt(String value) {
        if (value == null || value.isEmpty()) return "";
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + "."
                    + Base64.encodeToString(encrypted, Base64.NO_WRAP);
        } catch (Exception ignored) {
            return "";
        }
    }

    private String decrypt(String stored) {
        if (stored == null || stored.isEmpty()) return "";
        try {
            String[] parts = stored.split("\\.", 2);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(),
                    new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
            return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)),
                    StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return "";
        }
    }

    private SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (keyStore.containsAlias(KEY_ALIAS)) {
            return (SecretKey) keyStore.getKey(KEY_ALIAS, null);
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return generator.generateKey();
    }
}
