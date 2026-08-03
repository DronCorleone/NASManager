package io.github.nasmanager;

final class AppConfig {
    String serverUrl = "";
    String username = "";
    String apiKey = "";
    String macAddress = "";
    String broadcastAddress = "255.255.255.255";
    String theme = "system";
    String language = "system";
    String minimumSeverity = "WARNING";
    boolean showPools = true;
    boolean showResources = true;
    boolean showApps = true;
    boolean showAlerts = true;
    boolean notifyAlerts = true;

    boolean isApiConfigured() {
        return !serverUrl.trim().isEmpty() && !apiKey.trim().isEmpty();
    }

    String normalizedUrl() {
        String value = serverUrl.trim();
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }
}
