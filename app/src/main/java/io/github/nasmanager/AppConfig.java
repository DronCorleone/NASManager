package io.github.nasmanager;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

final class AppConfig {
    String serverUrl = "";
    String username = "";
    String password = "";
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
    boolean wakeScheduleEnabled = false;
    int wakeHour = 8;
    int wakeMinute = 0;
    boolean shutdownScheduleEnabled = false;
    int shutdownHour = 23;
    int shutdownMinute = 0;

    boolean isApiConfigured() {
        if (username == null || username.trim().isEmpty()) return false;
        try {
            parseServerUri();
            return usesApiKeyAuthentication()
                    ? apiKey != null && !apiKey.trim().isEmpty()
                    : password != null && !password.isEmpty();
        } catch (IOException ignored) {
            return false;
        }
    }

    String normalizedUrl() {
        String value = serverUrl.trim();
        if (!value.isEmpty() && !value.contains("://")) value = "http://" + value;
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }

    /**
     * Returns the configured API origin. HTTP is intentionally supported for trusted LANs, but it
     * must use username/password authentication: TrueNAS revokes API keys presented over HTTP.
     * HTTPS keeps API-key authentication and full Android certificate/hostname verification.
     */
    URI requireServerUri() throws IOException {
        URI uri = parseServerUri();
        if (username == null || username.trim().isEmpty()) {
            throw new IOException("TrueNAS username is required");
        }
        if ("http".equalsIgnoreCase(uri.getScheme())) {
            if (password == null || password.isEmpty()) {
                throw new IOException("TrueNAS password is required for an HTTP connection. API keys are never sent over HTTP because TrueNAS revokes them.");
            }
        } else if ((apiKey == null || apiKey.trim().isEmpty())
                && (password == null || password.isEmpty())) {
            throw new IOException("TrueNAS API key or password is required for an HTTPS connection");
        }
        return uri;
    }

    boolean usesPasswordAuthentication() {
        try {
            URI uri = parseServerUri();
            return "http".equalsIgnoreCase(uri.getScheme())
                    || ("https".equalsIgnoreCase(uri.getScheme())
                    && (apiKey == null || apiKey.trim().isEmpty()));
        } catch (IOException ignored) {
            return false;
        }
    }

    boolean usesApiKeyAuthentication() {
        try {
            return "https".equalsIgnoreCase(parseServerUri().getScheme())
                    && apiKey != null && !apiKey.trim().isEmpty();
        } catch (IOException ignored) {
            return false;
        }
    }

    String authenticationMechanism() {
        return usesPasswordAuthentication() ? "PASSWORD_PLAIN" : "API_KEY_PLAIN";
    }

    String authenticationSecretField() {
        return usesPasswordAuthentication() ? "password" : "api_key";
    }

    /** Returns the validated server origin without requiring or inspecting credentials. */
    URI serverOriginUri() throws IOException {
        if (serverUrl == null || serverUrl.trim().isEmpty()) {
            throw new IOException("TrueNAS server URL is required");
        }
        final URI uri;
        try {
            uri = new URI(normalizedUrl());
        } catch (URISyntaxException error) {
            throw new IOException("Invalid TrueNAS server URL", error);
        }
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IOException("TrueNAS server URL must use http:// or https://");
        }
        if (uri.getHost() == null || uri.getHost().trim().isEmpty()) {
            throw new IOException("Invalid TrueNAS server URL: host is missing");
        }
        if (uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IOException("TrueNAS server URL must contain only the origin (for example http://192.168.1.10)");
        }
        String path = uri.getRawPath();
        if (path != null && !path.isEmpty() && !"/".equals(path)) {
            throw new IOException("TrueNAS server URL must not contain a path");
        }
        return uri;
    }

    private URI parseServerUri() throws IOException {
        return serverOriginUri();
    }
}
