package io.github.nasmanager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

final class TrueNasClient {
    private final AppConfig config;

    TrueNasClient(AppConfig config) {
        this.config = config;
    }

    DashboardData loadDashboard() throws Exception {
        try {
            return loadDashboardWebSocket();
        } catch (Exception websocketFailure) {
            return loadDashboardRest();
        }
    }

    private DashboardData loadDashboardWebSocket() throws Exception {
        DashboardData result = new DashboardData();
        try (TrueNasWebSocketClient client = new TrueNasWebSocketClient(config)) {
            JSONObject system = asObject(client.call("system.info", new JSONArray()));
            parseSystem(result, system);
            boolean realtimeSubscribed = false;
            try {
                client.call("core.subscribe", new JSONArray().put("reporting.realtime:{\"interval\":2}"));
                realtimeSubscribed = true;
            } catch (Exception ignored) { }
            JSONArray query = new JSONArray().put(new JSONArray()).put(new JSONObject());
            try { parsePools(result, asArray(client.call("pool.query", query))); } catch (Exception ignored) { }
            try { parseApps(result, asArray(client.call("app.query", query))); } catch (Exception ignored) { }
            try { parseAlerts(result, asArray(client.call("alert.list", new JSONArray()))); } catch (Exception ignored) { }
            if (realtimeSubscribed) {
                try { parseRealtime(result, client.nextEvent("reporting.realtime")); } catch (Exception ignored) { }
            }
        }
        return result;
    }

    private DashboardData loadDashboardRest() throws Exception {
        DashboardData result = new DashboardData();
        JSONObject system = asObject(request("GET", "/api/v2.0/system/info", null));
        parseSystem(result, system);
        try { loadRealtime(result); } catch (Exception ignored) { }
        try { loadPools(result); } catch (Exception ignored) { }
        try { loadApps(result); } catch (Exception ignored) { }
        try { loadAlerts(result); } catch (Exception ignored) { }
        return result;
    }

    private void parseSystem(DashboardData result, JSONObject system) {
        result.online = true;
        result.hostName = firstString(system, "hostname", "system_product", "TrueNAS");
        result.version = firstString(system, "version", "system_version", "");
        result.uptimeSeconds = firstLong(system, "uptime_seconds", "uptime", 0);
        result.memoryTotal = firstLong(system, "physmem", "physical_memory", 0);
        result.cpuCores = Math.max(1, (int) firstLong(system, "cores", "physical_cores", 1));
        JSONArray load = system.optJSONArray("loadavg");
        if (load != null) {
            for (int i = 0; i < Math.min(3, load.length()); i++) result.loadAverage[i] = load.optDouble(i, 0);
        }
    }

    boolean testConnection() {
        try {
            try (TrueNasWebSocketClient ignored = new TrueNasWebSocketClient(config)) { return true; }
        } catch (Exception ignored) {
        }
        try {
            request("GET", "/api/v2.0/system/info", null);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    void shutdown() throws Exception {
        try (TrueNasWebSocketClient client = new TrueNasWebSocketClient(config)) {
            client.call("system.shutdown", new JSONArray().put("NAS Manager mobile request").put(new JSONObject()));
            return;
        } catch (Exception websocketFailure) {
            request("POST", "/api/v2.0/system/shutdown", new JSONObject());
        }
    }

    void startApp(String name) throws Exception {
        if (callWebSocketApp("app.start", name, null)) return;
        tryAppAction("/api/v2.0/app/start", name, null,
                "/api/v2.0/chart/release/scale", "replica_count", 1);
    }

    void stopApp(String name) throws Exception {
        if (callWebSocketApp("app.stop", name, null)) return;
        tryAppAction("/api/v2.0/app/stop", name, null,
                "/api/v2.0/chart/release/scale", "replica_count", 0);
    }

    void deployApp(String name) throws Exception {
        if (callWebSocketApp("app.redeploy", name, null)) return;
        tryAppAction("/api/v2.0/app/redeploy", name, null,
                "/api/v2.0/chart/release/redeploy", null, 0);
    }

    void updateApp(String name) throws Exception {
        if (callWebSocketApp("app.upgrade", name, new JSONObject())) return;
        JSONObject modern = new JSONObject().put("app_name", name).put("options", new JSONObject());
        try {
            request("POST", "/api/v2.0/app/upgrade", modern);
        } catch (Exception modernFailure) {
            JSONObject legacy = new JSONObject().put("release_name", name).put("options", new JSONObject());
            request("POST", "/api/v2.0/chart/release/upgrade", legacy);
        }
    }

    private boolean callWebSocketApp(String method, String name, JSONObject options) {
        try (TrueNasWebSocketClient client = new TrueNasWebSocketClient(config)) {
            JSONArray params = new JSONArray().put(name);
            if (options != null) params.put(options);
            client.call(method, params);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void tryAppAction(String modernPath, String name, String extraKey,
                              String legacyPath, String legacyKey, int legacyValue) throws Exception {
        JSONObject modern = new JSONObject().put("app_name", name);
        if (extraKey != null) modern.put(extraKey, true);
        try {
            request("POST", modernPath, modern);
        } catch (Exception modernFailure) {
            JSONObject legacy = new JSONObject().put("id", name);
            if (legacyKey != null) legacy.put(legacyKey, legacyValue);
            request("POST", legacyPath, legacy);
        }
    }

    private void loadPools(DashboardData target) throws Exception {
        parsePools(target, asArray(request("GET", "/api/v2.0/pool", null)));
    }

    private void parsePools(DashboardData target, JSONArray array) {
        for (int i = 0; i < array.length(); i++) {
            JSONObject source = array.optJSONObject(i);
            if (source == null) continue;
            DashboardData.PoolInfo pool = new DashboardData.PoolInfo();
            pool.name = firstString(source, "name", "id", "Pool");
            pool.status = firstString(source, "status", "healthy", "UNKNOWN");
            if (source.has("healthy") && source.optBoolean("healthy")) pool.status = "ONLINE";
            pool.size = firstLong(source, "size", "total", 0);
            pool.used = firstLong(source, "allocated", "used", 0);
            if (pool.size == 0) {
                JSONObject scan = source.optJSONObject("scan");
                if (scan != null) pool.size = firstLong(scan, "total", "size", 0);
            }
            target.pools.add(pool);
        }
    }

    private void loadRealtime(DashboardData target) throws Exception {
        parseRealtime(target, asObject(request("GET", "/api/v2.0/reporting/realtime", null)));
    }

    private void parseRealtime(DashboardData target, JSONObject realtime) {
        JSONObject memory = realtime.optJSONObject("memory");
        if (memory != null) {
            long total = firstLong(memory, "total", "physical_memory_total", 0);
            long available = firstLong(memory, "available", "physical_memory_available", 0);
            long used = firstLong(memory, "used", "physical_memory_used", 0);
            if (total > 0) target.memoryTotal = total;
            target.memoryUsed = used > 0 ? used : Math.max(0, target.memoryTotal - available);
        }
        JSONObject cpu = realtime.optJSONObject("cpu");
        if (cpu != null) {
            JSONObject aggregate = cpu.optJSONObject("cpu");
            double value = aggregate == null ? firstDouble(cpu, "usage", "average", -1)
                    : firstDouble(aggregate, "usage", "average", -1);
            if (value >= 0) target.cpuPercent = (int) Math.round(value);
        }
    }

    private void loadApps(DashboardData target) throws Exception {
        JSONArray array;
        try {
            array = asArray(request("GET", "/api/v2.0/app", null));
        } catch (Exception modernFailure) {
            array = asArray(request("GET", "/api/v2.0/chart/release", null));
        }
        parseApps(target, array);
    }

    private void parseApps(DashboardData target, JSONArray array) {
        for (int i = 0; i < array.length(); i++) {
            JSONObject source = array.optJSONObject(i);
            if (source == null) continue;
            DashboardData.AppInfo app = new DashboardData.AppInfo();
            app.name = firstString(source, "id", "name", "app-" + i);
            app.displayName = firstString(source, "name", "human_name", app.name);
            app.state = firstString(source, "state", "status", "UNKNOWN").toUpperCase(Locale.US);
            app.updateAvailable = source.optBoolean("upgrade_available",
                    source.optBoolean("image_updates_available", source.optBoolean("update_available", false)));
            target.apps.add(app);
        }
    }

    private void loadAlerts(DashboardData target) throws Exception {
        parseAlerts(target, asArray(request("GET", "/api/v2.0/alert/list", null)));
    }

    private void parseAlerts(DashboardData target, JSONArray array) {
        for (int i = 0; i < array.length(); i++) {
            JSONObject source = array.optJSONObject(i);
            if (source == null || source.optBoolean("dismissed", false)) continue;
            DashboardData.AlertInfo alert = new DashboardData.AlertInfo();
            alert.id = firstString(source, "uuid", "id", String.valueOf(i));
            alert.level = firstString(source, "level", "severity", "INFO").toUpperCase(Locale.US);
            alert.title = firstString(source, "klass", "title", alert.level);
            alert.message = firstString(source, "formatted", "text", "");
            JSONObject datetime = source.optJSONObject("datetime");
            alert.date = datetime == null ? source.optString("datetime", "") : datetime.optString("$date", "");
            target.alerts.add(alert);
        }
    }

    private String request(String method, String path, JSONObject body) throws Exception {
        if (!config.isApiConfigured()) throw new IOException("TrueNAS is not configured");
        HttpURLConnection connection = (HttpURLConnection) new URL(config.normalizedUrl() + path).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(7000);
        connection.setReadTimeout(15000);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Authorization", "Bearer " + config.apiKey);
        connection.setRequestProperty("X-API-Key", config.apiKey);
        if (body != null) {
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
        }
        int code = connection.getResponseCode();
        InputStream stream = code >= 200 && code < 300
                ? connection.getInputStream() : connection.getErrorStream();
        String response = read(stream);
        connection.disconnect();
        if (code < 200 || code >= 300) {
            throw new IOException("HTTP " + code + (response.isEmpty() ? "" : ": " + compactError(response)));
        }
        return response;
    }

    private static String read(InputStream stream) throws IOException {
        if (stream == null) return "";
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) result.append(line);
        }
        return result.toString();
    }

    private static String compactError(String raw) {
        String result = raw.replace('\n', ' ').replace('\r', ' ').trim();
        return result.length() > 180 ? result.substring(0, 180) + "…" : result;
    }

    private static JSONObject asObject(String raw) throws JSONException {
        return raw == null || raw.trim().isEmpty() ? new JSONObject() : new JSONObject(raw);
    }

    private static JSONObject asObject(Object value) throws JSONException {
        if (value instanceof JSONObject) return (JSONObject) value;
        return value == null || value == JSONObject.NULL ? new JSONObject() : new JSONObject(String.valueOf(value));
    }

    private static JSONArray asArray(String raw) throws JSONException {
        if (raw == null || raw.trim().isEmpty()) return new JSONArray();
        String value = raw.trim();
        if (value.startsWith("[")) return new JSONArray(value);
        JSONObject object = new JSONObject(value);
        JSONArray result = object.optJSONArray("result");
        if (result != null) return result;
        result = object.optJSONArray("data");
        return result == null ? new JSONArray() : result;
    }

    private static JSONArray asArray(Object value) throws JSONException {
        if (value instanceof JSONArray) return (JSONArray) value;
        return value == null || value == JSONObject.NULL ? new JSONArray() : new JSONArray(String.valueOf(value));
    }

    private static String firstString(JSONObject object, String first, String second, String fallback) {
        String value = object.optString(first, "");
        if (!value.isEmpty() && !"null".equals(value)) return value;
        value = object.optString(second, "");
        return value.isEmpty() || "null".equals(value) ? fallback : value;
    }

    private static long firstLong(JSONObject object, String first, String second, long fallback) {
        long value = object.optLong(first, Long.MIN_VALUE);
        if (value != Long.MIN_VALUE) return value;
        return object.optLong(second, fallback);
    }

    private static double firstDouble(JSONObject object, String first, String second, double fallback) {
        double value = object.optDouble(first, Double.NaN);
        return Double.isNaN(value) ? object.optDouble(second, fallback) : value;
    }
}
