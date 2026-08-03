package io.github.nasmanager;

import java.io.IOException;

public final class AppConfigSecurityTest {
    public static void main(String[] args) throws Exception {
        acceptsHttpsOrigin();
        rejectsInsecureAndAmbiguousUrls();
        System.out.println("AppConfigSecurityTest: all checks passed");
    }

    private static void acceptsHttpsOrigin() throws Exception {
        AppConfig config = configured(" https://nas.example.com:8443/ ");
        require("https".equals(config.requireSecureServerUri().getScheme()), "HTTPS expected");
        require("nas.example.com".equals(config.requireSecureServerUri().getHost()), "host expected");
        require(config.requireSecureServerUri().getPort() == 8443, "port expected");
    }

    private static void rejectsInsecureAndAmbiguousUrls() {
        rejects("http://192.168.1.10", "HTTP must never carry an API key");
        rejects("ws://192.168.1.10", "ws must never carry an API key");
        rejects("192.168.1.10", "scheme is required");
        rejects("https://user@nas.example.com", "userinfo is forbidden");
        rejects("https://nas.example.com/ui", "paths are forbidden");
        rejects("https://nas.example.com?key=value", "queries are forbidden");
    }

    private static void rejects(String url, String message) {
        try {
            configured(url).requireSecureServerUri();
            throw new AssertionError(message);
        } catch (IOException expected) { }
    }

    private static AppConfig configured(String url) {
        AppConfig config = new AppConfig();
        config.serverUrl = url;
        config.apiKey = "test-key";
        return config;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
