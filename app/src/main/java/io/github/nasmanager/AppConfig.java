package io.github.nasmanager;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

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

    /**
     * Returns the configured API origin after enforcing the transport guarantees required for
     * password-equivalent API keys. TrueNAS revokes keys presented over HTTP, so this check must
     * happen before either the WebSocket or legacy REST transport opens a connection.
     */
    URI requireSecureServerUri() throws IOException {
        if (!isApiConfigured()) throw new IOException("TrueNAS is not configured");
        final URI uri;
        try {
            uri = new URI(normalizedUrl());
        } catch (URISyntaxException error) {
            throw new IOException("Invalid TrueNAS server URL", error);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IOException("TrueNAS API keys require HTTPS. Configure an https:// URL before connecting; HTTP would revoke the key.");
        }
        if (uri.getHost() == null || uri.getHost().trim().isEmpty()) {
            throw new IOException("Invalid TrueNAS server URL: host is missing");
        }
        if (uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IOException("TrueNAS server URL must contain only the HTTPS origin (for example https://nas.example.com)");
        }
        String path = uri.getRawPath();
        if (path != null && !path.isEmpty() && !"/".equals(path)) {
            throw new IOException("TrueNAS server URL must not contain a path");
        }
        return uri;
    }
}
