package io.github.nasmanager;

import java.io.IOException;

public final class AppConfigSecurityTest {
    public static void main(String[] args) throws Exception {
        acceptsHttpAsPrimaryLanMode();
        acceptsHttpsAsOptionalApiKeyMode();
        acceptsHttpsPasswordFallback();
        requiresCredentialForSelectedTransport();
        rejectsAmbiguousUrls();
        System.out.println("AppConfigSecurityTest: all checks passed");
    }

    private static void acceptsHttpAsPrimaryLanMode() throws Exception {
        AppConfig config = configured(" 192.168.1.10:8080 ");
        config.password = "password";
        require("http".equals(config.requireServerUri().getScheme()), "bare LAN address must default to HTTP");
        require("192.168.1.10".equals(config.requireServerUri().getHost()), "LAN host expected");
        require(config.requireServerUri().getPort() == 8080, "LAN port expected");
        require(config.usesPasswordAuthentication(), "HTTP must select password authentication");
        require(!config.usesApiKeyAuthentication(), "HTTP must never select API-key authentication");
        require("PASSWORD_PLAIN".equals(config.authenticationMechanism()), "HTTP login mechanism expected");
        require("password".equals(config.authenticationSecretField()), "HTTP must send the password field");
        require(config.isApiConfigured(), "HTTP configuration should be complete");
    }

    private static void acceptsHttpsAsOptionalApiKeyMode() throws Exception {
        AppConfig config = configured(" https://nas.example.com:8443/ ");
        require("https".equals(config.requireServerUri().getScheme()), "HTTPS expected");
        require("nas.example.com".equals(config.requireServerUri().getHost()), "host expected");
        require(config.requireServerUri().getPort() == 8443, "HTTPS port expected");
        require(config.usesApiKeyAuthentication(), "HTTPS must select API-key authentication");
        require(!config.usesPasswordAuthentication(), "HTTPS must not select password authentication");
        require("API_KEY_PLAIN".equals(config.authenticationMechanism()), "HTTPS API-key mechanism expected");
        require("api_key".equals(config.authenticationSecretField()), "HTTPS must send the API-key field");
    }

    private static void acceptsHttpsPasswordFallback() throws Exception {
        AppConfig config = configured("https://nas.example.com");
        config.apiKey = "";
        config.password = "password";
        require(config.isApiConfigured(), "HTTPS should accept password when no API key is set");
        require(config.usesPasswordAuthentication(), "HTTPS must fall back to password authentication");
        require(!config.usesApiKeyAuthentication(), "empty API key must not select API-key authentication");
        require("PASSWORD_PLAIN".equals(config.authenticationMechanism()), "HTTPS password fallback expected");
        config.requireServerUri();
    }

    private static void requiresCredentialForSelectedTransport() {
        AppConfig http = configured("http://192.168.1.10");
        require(!http.isApiConfigured(), "HTTP must require a password, not an API key");
        rejects(http, "HTTP without a password must be rejected");

        AppConfig https = configured("https://nas.example.com");
        https.apiKey = "";
        https.password = "";
        require(!https.isApiConfigured(), "HTTPS must require an API key or password");
        rejects(https, "HTTPS without a credential must be rejected");
    }

    private static void rejectsAmbiguousUrls() {
        rejects("ws://192.168.1.10", "ws must never carry an API key");
        rejects("wss://nas.example.com", "WebSocket schemes are derived internally");
        rejects("https://user@nas.example.com", "userinfo is forbidden");
        rejects("https://nas.example.com/ui", "paths are forbidden");
        rejects("https://nas.example.com?key=value", "queries are forbidden");
    }

    private static void rejects(String url, String message) {
        AppConfig config = configured(url);
        config.password = "password";
        rejects(config, message);
    }

    private static void rejects(AppConfig config, String message) {
        try {
            config.requireServerUri();
            throw new AssertionError(message);
        } catch (IOException expected) { }
    }

    private static AppConfig configured(String url) {
        AppConfig config = new AppConfig();
        config.serverUrl = url;
        config.username = "test-user";
        config.apiKey = "test-key";
        return config;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
