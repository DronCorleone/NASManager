package io.github.nasmanager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.EOFException;

/** One cancellable app.container_log_follow subscription. Call next() from a worker thread. */
final class AppLogsMonitor implements AutoCloseable {
    static final int DEFAULT_TAIL_LINES = 200;

    private static final String COLLECTION = "app.container_log_follow";
    private final TrueNasWebSocketClient client;
    private volatile boolean closed;

    AppLogsMonitor(AppConfig config, String appName, String containerId) throws Exception {
        this(config, appName, containerId, DEFAULT_TAIL_LINES);
    }

    AppLogsMonitor(AppConfig config, String appName, String containerId, int tailLines) throws Exception {
        String validAppName = requireValue(appName, "App name");
        String validContainerId = requireValue(containerId, "Container ID");
        if (tailLines < 1) throw new IllegalArgumentException("Tail line count must be at least 1");

        client = new TrueNasWebSocketClient(config);
        try {
            JSONObject sourceArgs = new JSONObject()
                    .put("tail_lines", tailLines)
                    .put("app_name", validAppName)
                    .put("container_id", validContainerId);
            client.call("core.subscribe", new JSONArray().put(COLLECTION + ":" + sourceArgs));
        } catch (Exception error) {
            client.close();
            throw error;
        }
    }

    /** Blocks until a log entry arrives; closing the monitor immediately unblocks this call. */
    DashboardData.AppLogEntry next() throws Exception {
        if (closed) throw new EOFException("App logs monitor is closed");
        while (true) {
            Object fields = client.nextEventFields(COLLECTION);
            DashboardData.AppLogEntry entry = AppLogsParser.parseLogEntry(TrueNasClient.toJava(fields));
            if (entry != null) return entry;
            if (closed) throw new EOFException("App logs monitor is closed");
        }
    }

    @Override
    public void close() {
        closed = true;
        // This connection owns exactly one dynamic source. Closing it both cancels a blocking read
        // locally and releases the server-side subscription, without racing core.unsubscribe.
        client.close();
    }

    private static String requireValue(String value, String label) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.trim();
    }
}
