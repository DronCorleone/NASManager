package io.github.nasmanager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class AppLogsParserTest {
    public static void main(String[] args) {
        parsesContainerMapIncludingStoppedStates();
        parsesLegacyContainerArrayAndFallbackKeys();
        parsesLogEntryAndNullableTimestamp();
        ignoresMalformedEventsAndBoundsHistory();
        System.out.println("AppLogsParser tests passed");
    }

    private static void parsesContainerMapIncludingStoppedStates() {
        Map<String, Object> result = map(
                "abc", map("id", "abc", "service_name", "web", "image", "nginx:latest", "state", "running"),
                "def", map("id", "def", "service_name", "permissions", "image", "busybox", "state", "exited"));
        List<DashboardData.ContainerInfo> containers = AppLogsParser.parseContainers(result);
        assert containers.size() == 2;
        assert "web".equals(containers.get(0).serviceName);
        assert "RUNNING".equals(containers.get(0).state);
        assert "permissions".equals(containers.get(1).serviceName);
        assert "EXITED".equals(containers.get(1).state);
    }

    private static void parsesLegacyContainerArrayAndFallbackKeys() {
        List<DashboardData.ContainerInfo> containers = AppLogsParser.parseContainers(map("containers", list(
                map("container_id", "legacy", "name", "worker", "image_name", "worker:v1", "status", "crashed"))));
        assert containers.size() == 1;
        assert "legacy".equals(containers.get(0).id);
        assert "worker".equals(containers.get(0).serviceName);
        assert "CRASHED".equals(containers.get(0).state);
    }

    private static void parsesLogEntryAndNullableTimestamp() {
        DashboardData.AppLogEntry entry = AppLogsParser.parseLogEntry(map("fields",
                map("timestamp", "2026-08-04T10:00:00Z", "data", "server ready")));
        assert entry != null;
        assert "2026-08-04T10:00:00Z".equals(entry.timestamp);
        assert "server ready".equals(entry.data);

        DashboardData.AppLogEntry noTimestamp = AppLogsParser.parseLogEntry(map("timestamp", null, "data", "raw line"));
        assert noTimestamp != null;
        assert "".equals(noTimestamp.timestamp);
    }

    private static void ignoresMalformedEventsAndBoundsHistory() {
        assert AppLogsParser.parseLogEntry(map("timestamp", "now")) == null;
        List<DashboardData.AppLogEntry> history = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            DashboardData.AppLogEntry entry = new DashboardData.AppLogEntry();
            entry.data = String.valueOf(i);
            AppLogsParser.appendBounded(history, entry, 3);
        }
        assert history.size() == 3;
        assert "1".equals(history.get(0).data);
        assert "3".equals(history.get(2).data);
    }

    private static List<Object> list(Object... values) {
        return Arrays.asList(values);
    }

    private static Map<String, Object> map(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) result.put(String.valueOf(values[i]), values[i + 1]);
        return result;
    }
}
