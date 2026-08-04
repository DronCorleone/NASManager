package io.github.nasmanager;

import java.net.ServerSocket;

public final class ServerReachabilityProbeTest {
    public static void main(String[] args) throws Exception {
        resolvesBareAddressAsHttp();
        resolvesDefaultPorts();
        resolvesExplicitPorts();
        rejectsInvalidConfigurationWithoutCredentials();
        reportsReachableLocalEndpoint();
        reportsClosedLocalEndpointAsUnreachable();
        validatesTimeout();
        System.out.println("ServerReachabilityProbeTest: all checks passed");
    }

    private static void resolvesBareAddressAsHttp() throws Exception {
        ServerReachabilityProbe.Endpoint endpoint = endpoint("truenas.local");
        require("truenas.local".equals(endpoint.host()), "bare host expected");
        require(endpoint.port() == 80, "bare address must use HTTP port 80");
    }

    private static void resolvesDefaultPorts() throws Exception {
        require(endpoint("http://192.168.31.10").port() == 80, "HTTP default port expected");
        require(endpoint("https://nas.example.com/").port() == 443, "HTTPS default port expected");
    }

    private static void resolvesExplicitPorts() throws Exception {
        ServerReachabilityProbe.Endpoint http = endpoint("http://192.168.31.10:8080");
        require("192.168.31.10".equals(http.host()), "IPv4 host expected");
        require(http.port() == 8080, "explicit HTTP port expected");

        ServerReachabilityProbe.Endpoint https = endpoint("https://nas.example.com:8443");
        require(https.port() == 8443, "explicit HTTPS port expected");
    }

    private static void rejectsInvalidConfigurationWithoutCredentials() {
        AppConfig empty = new AppConfig();
        ServerReachabilityProbe.Result result = new ServerReachabilityProbe().probe(empty);
        require(!result.isReachable(), "empty URL must be unreachable");
        require(result.latencyMillis() == -1L, "failed probe must not report latency");
        require(result.errorMessage() != null && !result.errorMessage().isEmpty(), "failure reason expected");

        AppConfig noCredentials = new AppConfig();
        noCredentials.serverUrl = "http://127.0.0.1:9";
        result = new ServerReachabilityProbe(100).probe(noCredentials);
        require(!result.isReachable(), "closed endpoint must be unreachable");
        require(result.errorMessage() != null, "network error expected");
    }

    private static void reportsReachableLocalEndpoint() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            AppConfig config = new AppConfig();
            config.serverUrl = "http://127.0.0.1:" + server.getLocalPort();
            ServerReachabilityProbe.Result result = new ServerReachabilityProbe().probe(config);
            require(result.isReachable(), "listening local endpoint must be reachable");
            require(result.latencyMillis() >= 0L, "successful probe latency expected");
            require(result.checkedAtEpochMillis() > 0L, "check timestamp expected");
            require(result.errorMessage() == null, "successful probe must not contain an error");
        }
    }

    private static void reportsClosedLocalEndpointAsUnreachable() throws Exception {
        int closedPort;
        try (ServerSocket server = new ServerSocket(0)) {
            closedPort = server.getLocalPort();
        }
        AppConfig config = new AppConfig();
        config.serverUrl = "https://127.0.0.1:" + closedPort;
        ServerReachabilityProbe.Result result = new ServerReachabilityProbe(500).probe(config);
        require(!result.isReachable(), "closed local endpoint must be unreachable");
        require(result.latencyMillis() == -1L, "failed probe latency sentinel expected");
        require(result.checkedAtEpochMillis() > 0L, "failure timestamp expected");
    }

    private static void validatesTimeout() {
        try {
            new ServerReachabilityProbe(0);
            throw new AssertionError("zero timeout must be rejected");
        } catch (IllegalArgumentException expected) { }
    }

    private static ServerReachabilityProbe.Endpoint endpoint(String url) throws Exception {
        AppConfig config = new AppConfig();
        config.serverUrl = url;
        return new ServerReachabilityProbe().resolveEndpoint(config);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
