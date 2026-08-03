package io.github.nasmanager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

final class TrueNasClient {
    private final AppConfig config;

    TrueNasClient(AppConfig config) {
        this.config = config;
    }

    DashboardData loadDashboard() throws Exception {
        return loadDashboardWebSocket();
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
            return false;
        }
    }

    void shutdown() throws Exception {
        try (TrueNasWebSocketClient client = new TrueNasWebSocketClient(config)) {
            client.callJob("system.shutdown", new JSONArray()
                    .put("NAS Manager mobile request")
                    .put(new JSONObject().put("delay", JSONObject.NULL)));
        }
    }

    void startApp(String name) throws Exception {
        callWebSocketApp("app.start", name, null);
    }

    void stopApp(String name) throws Exception {
        callWebSocketApp("app.stop", name, null);
    }

    void deployApp(String name) throws Exception {
        callWebSocketApp("app.redeploy", name, null);
    }

    void updateApp(String name) throws Exception {
        callWebSocketApp("app.upgrade", name, new JSONObject()
                .put("app_version", "latest")
                .put("values", new JSONObject())
                .put("snapshot_hostpaths", false));
    }

    private void callWebSocketApp(String method, String name, JSONObject options) throws Exception {
        try (TrueNasWebSocketClient client = new TrueNasWebSocketClient(config)) {
            JSONArray params = new JSONArray().put(name);
            if (options != null) params.put(options);
            client.callJob(method, params);
        }
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

    private static JSONObject asObject(Object value) throws JSONException {
        if (value instanceof JSONObject) return (JSONObject) value;
        return value == null || value == JSONObject.NULL ? new JSONObject() : new JSONObject(String.valueOf(value));
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
