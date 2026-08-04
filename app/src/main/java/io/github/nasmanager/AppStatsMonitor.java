package io.github.nasmanager;

import org.json.JSONArray;

import java.io.EOFException;
import java.util.List;

/** One authenticated, long-lived app.stats subscription. Call nextFor from a worker thread. */
final class AppStatsMonitor implements AutoCloseable {
    static final int SOURCE_INTERVAL_SECONDS = 2;

    private final TrueNasWebSocketClient client;
    private volatile boolean closed;

    AppStatsMonitor(AppConfig config) throws Exception {
        client = new TrueNasWebSocketClient(config);
        try {
            client.call("core.subscribe", new JSONArray().put(
                    "app.stats:{\"interval\":" + SOURCE_INTERVAL_SECONDS + "}"));
        } catch (Exception error) {
            client.close();
            throw error;
        }
    }

    /**
     * Blocks until the next two-second source update and returns that app's aggregate sample.
     * A stopped app can be absent from the event; in that case a no-load sample is returned.
     */
    DashboardData.AppStats nextFor(String appName) throws Exception {
        if (closed) throw new EOFException("App stats monitor is closed");
        Object event = client.nextEventFields("app.stats");
        List<DashboardData.AppStats> samples = TrueNasDataParser.parseAppStats(TrueNasClient.toJava(event));
        for (DashboardData.AppStats sample : samples) {
            if (sample.appName.equals(appName)) return sample;
        }
        DashboardData.AppStats empty = new DashboardData.AppStats();
        empty.appName = appName == null ? "" : appName;
        empty.cpuPercent = 0;
        empty.memoryBytes = 0;
        return empty;
    }

    @Override
    public void close() {
        closed = true;
        client.close();
    }
}
