package io.github.nasmanager;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;

/**
 * Checks whether the configured TrueNAS TCP endpoint is reachable.
 *
 * <p>This probe deliberately performs no HTTP/WebSocket request and never reads or sends a
 * username, password or API key. It is safe to run independently from an authenticated refresh.
 * The call is blocking and must be executed away from the Android main thread.</p>
 */
final class ServerReachabilityProbe {
    static final int DEFAULT_TIMEOUT_MILLIS = 2_000;

    private final int timeoutMillis;

    ServerReachabilityProbe() {
        this(DEFAULT_TIMEOUT_MILLIS);
    }

    ServerReachabilityProbe(int timeoutMillis) {
        if (timeoutMillis <= 0) {
            throw new IllegalArgumentException("timeoutMillis must be positive");
        }
        this.timeoutMillis = timeoutMillis;
    }

    Result probe(AppConfig config) {
        long checkedAtEpochMillis = System.currentTimeMillis();
        long startedAtNanos = System.nanoTime();
        try {
            Endpoint endpoint = resolveEndpoint(config);
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(endpoint.host(), endpoint.port()), timeoutMillis);
            }
            return Result.reachable(checkedAtEpochMillis, elapsedMillis(startedAtNanos));
        } catch (IOException | RuntimeException error) {
            String message = error.getMessage();
            if (message == null || message.trim().isEmpty()) {
                message = error.getClass().getSimpleName();
            }
            return Result.unreachable(checkedAtEpochMillis, message);
        }
    }

    Endpoint resolveEndpoint(AppConfig config) throws IOException {
        if (config == null) {
            throw new IOException("TrueNAS configuration is required");
        }
        URI uri = config.serverOriginUri();
        int port = uri.getPort();
        if (port < 0) {
            port = "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
        }
        return new Endpoint(uri.getHost(), port);
    }

    private static long elapsedMillis(long startedAtNanos) {
        return Math.max(0L, (System.nanoTime() - startedAtNanos) / 1_000_000L);
    }

    static final class Endpoint {
        private final String host;
        private final int port;

        Endpoint(String host, int port) {
            this.host = host;
            this.port = port;
        }

        String host() {
            return host;
        }

        int port() {
            return port;
        }
    }

    static final class Result {
        private final boolean reachable;
        private final long checkedAtEpochMillis;
        private final long latencyMillis;
        private final String errorMessage;

        private Result(boolean reachable, long checkedAtEpochMillis, long latencyMillis, String errorMessage) {
            this.reachable = reachable;
            this.checkedAtEpochMillis = checkedAtEpochMillis;
            this.latencyMillis = latencyMillis;
            this.errorMessage = errorMessage;
        }

        static Result reachable(long checkedAtEpochMillis, long latencyMillis) {
            return new Result(true, checkedAtEpochMillis, latencyMillis, null);
        }

        static Result unreachable(long checkedAtEpochMillis, String errorMessage) {
            return new Result(false, checkedAtEpochMillis, -1L, errorMessage);
        }

        boolean isReachable() {
            return reachable;
        }

        long checkedAtEpochMillis() {
            return checkedAtEpochMillis;
        }

        long latencyMillis() {
            return latencyMillis;
        }

        String errorMessage() {
            return errorMessage;
        }
    }
}
