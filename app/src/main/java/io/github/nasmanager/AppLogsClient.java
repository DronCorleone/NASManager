package io.github.nasmanager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Collections;
import java.util.List;

/** Short-lived JSON-RPC client for discovering all containers belonging to an app. */
final class AppLogsClient {
    private final AppConfig config;

    AppLogsClient(AppConfig config) {
        this.config = config;
    }

    /** Includes stopped, exited, crashed and not-yet-running containers. Requires APPS_READ. */
    List<DashboardData.ContainerInfo> listContainers(String appName) throws Exception {
        if (appName == null || appName.trim().isEmpty()) return Collections.emptyList();
        try (TrueNasWebSocketClient client = new TrueNasWebSocketClient(config)) {
            Object result = client.call("app.container_ids", new JSONArray()
                    .put(appName.trim())
                    .put(new JSONObject().put("alive_only", false)));
            return AppLogsParser.parseContainers(TrueNasClient.toJava(result));
        }
    }
}
