package io.github.nasmanager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Pure parser for app.container_ids and app.container_log_follow JSON-RPC payloads. */
final class AppLogsParser {
    private AppLogsParser() { }

    static List<DashboardData.ContainerInfo> parseContainers(Object result) {
        Object value = unwrap(result, "result", "containers");
        List<DashboardData.ContainerInfo> containers = new ArrayList<>();
        Map<?, ?> byId = asMap(value);
        if (byId != null) {
            for (Map.Entry<?, ?> row : byId.entrySet()) {
                DashboardData.ContainerInfo container = parseContainer(row.getValue(), string(row.getKey(), ""));
                if (container != null) containers.add(container);
            }
            return containers;
        }
        for (Object row : safeList(asList(value))) {
            DashboardData.ContainerInfo container = parseContainer(row, "");
            if (container != null) containers.add(container);
        }
        return containers;
    }

    static DashboardData.AppLogEntry parseLogEntry(Object eventFields) {
        Object value = unwrap(eventFields, "fields", null);
        Map<?, ?> fields = asMap(value);
        if (fields == null || !fields.containsKey("data")) return null;
        DashboardData.AppLogEntry entry = new DashboardData.AppLogEntry();
        entry.timestamp = string(fields.get("timestamp"), "");
        entry.data = string(fields.get("data"), "");
        return entry;
    }

    /** Adds an entry while bounding retained UI history on memory-constrained devices. */
    static void appendBounded(List<DashboardData.AppLogEntry> target,
                              DashboardData.AppLogEntry entry, int maximumEntries) {
        if (target == null || entry == null || maximumEntries < 1) return;
        while (target.size() >= maximumEntries) target.remove(0);
        target.add(entry);
    }

    private static DashboardData.ContainerInfo parseContainer(Object row, String fallbackId) {
        Map<?, ?> source = asMap(row);
        if (source == null) return null;
        DashboardData.ContainerInfo container = new DashboardData.ContainerInfo();
        container.id = firstString(source, "id", "container_id", fallbackId);
        if (container.id.isEmpty()) return null;
        container.serviceName = firstString(source, "service_name", "name", container.id);
        container.image = firstString(source, "image", "image_name", "");
        container.state = firstString(source, "state", "status", "UNKNOWN").toUpperCase(Locale.US);
        return container;
    }

    private static Object unwrap(Object value, String first, String second) {
        Map<?, ?> envelope = asMap(value);
        if (envelope == null) return value;
        if (first != null && envelope.containsKey(first)) return envelope.get(first);
        if (second != null && envelope.containsKey(second)) return envelope.get(second);
        return value;
    }

    private static String firstString(Map<?, ?> source, String first, String second, String fallback) {
        String value = string(source.get(first), "");
        return value.isEmpty() ? string(source.get(second), fallback) : value;
    }

    private static String string(Object value, String fallback) {
        if (value == null) return fallback;
        String result = String.valueOf(value);
        return result.isEmpty() || "null".equalsIgnoreCase(result) ? fallback : result;
    }

    private static Map<?, ?> asMap(Object value) {
        return value instanceof Map ? (Map<?, ?>) value : null;
    }

    private static List<?> asList(Object value) {
        return value instanceof List ? (List<?>) value : null;
    }

    private static List<?> safeList(List<?> value) {
        return value == null ? Collections.emptyList() : value;
    }
}
