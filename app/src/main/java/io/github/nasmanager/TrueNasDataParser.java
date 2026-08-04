package io.github.nasmanager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Pure data parser kept independent from Android and org.json so it can be unit-tested on the JVM. */
final class TrueNasDataParser {
    private TrueNasDataParser() { }

    static void parseApps(DashboardData target, List<?> rows) {
        int index = 0;
        for (Object row : safeList(rows)) {
            Map<?, ?> source = map(row);
            if (source == null) continue;
            DashboardData.AppInfo app = new DashboardData.AppInfo();
            app.name = firstString(source, "id", "name", "app-" + index++);
            app.displayName = firstString(source, "name", "human_name", app.name);
            app.state = firstString(source, "state", "status", "UNKNOWN").toUpperCase(Locale.US);
            app.updateAvailable = bool(source, "upgrade_available",
                    bool(source, "image_updates_available", bool(source, "update_available", false)));

            Map<?, ?> metadata = map(source.get("metadata"));
            app.iconUrl = firstString(metadata, "icon", "icon_url", "");
            app.appVersion = metadata == null ? "" : string(metadata.get("app_version"), "");
            if (app.appVersion.isEmpty()) {
                app.appVersion = firstString(source, "human_version", "app_version", "");
            }
            app.catalogRevision = string(source.get("version"), "");
            app.webUiUrl = firstPortal(source.get("portals"));

            Map<?, ?> workloads = map(source.get("active_workloads"));
            app.containerCount = integer(workloads, "containers", "container_count", 0);
            Object usedPorts = workloads == null ? null : workloads.get("used_ports");
            Object containerDetails = workloads == null ? null : workloads.get("container_details");
            parsePorts(app.ports, list(usedPorts == null ? source.get("used_ports") : usedPorts));
            parseContainers(app.containers, list(containerDetails == null ? source.get("container_details") : containerDetails));
            if (app.containerCount == 0 && !app.containers.isEmpty()) app.containerCount = app.containers.size();
            target.apps.add(app);
        }
    }

    static List<DashboardData.AppStats> parseAppStats(Object eventFields) {
        Object value = eventFields;
        Map<?, ?> envelope = map(value);
        if (envelope != null) {
            if (envelope.containsKey("fields")) value = envelope.get("fields");
            else if (envelope.containsKey("stats")) value = envelope.get("stats");
            else if (envelope.containsKey("apps")) value = envelope.get("apps");
        }
        List<DashboardData.AppStats> result = new ArrayList<>();
        for (Object row : safeList(list(value))) {
            Map<?, ?> source = map(row);
            if (source == null) continue;
            DashboardData.AppStats stats = new DashboardData.AppStats();
            stats.appName = firstString(source, "app_name", "name", "");
            if (stats.appName.isEmpty()) continue;
            stats.cpuPercent = decimal(source, "cpu_usage", "cpu_percent", -1);
            stats.memoryBytes = longNumber(source, "memory", "memory_bytes", -1);
            for (Object networkRow : safeList(list(source.get("networks")))) {
                Map<?, ?> network = map(networkRow);
                if (network == null) continue;
                stats.networkRxBytesPerSecond += decimal(network, "rx_bytes", "received_bytes_rate", 0);
                stats.networkTxBytesPerSecond += decimal(network, "tx_bytes", "sent_bytes_rate", 0);
            }
            Map<?, ?> block = map(source.get("blkio"));
            stats.blockReadBytes = longNumber(block, "read", "read_bytes", 0);
            stats.blockWriteBytes = longNumber(block, "write", "write_bytes", 0);
            result.add(stats);
        }
        return result;
    }

    static void mergeAppStats(DashboardData target, List<DashboardData.AppStats> samples) {
        for (DashboardData.AppStats sample : samples) {
            for (DashboardData.AppInfo app : target.apps) {
                if (sample.appName.equals(app.name)) {
                    app.stats = sample;
                    break;
                }
            }
        }
    }

    static void parseInterfaces(DashboardData target, List<?> rows) {
        for (Object row : safeList(rows)) {
            Map<?, ?> source = map(row);
            if (source == null) continue;
            DashboardData.InterfaceInfo info = new DashboardData.InterfaceInfo();
            info.name = firstString(source, "name", "id", "");
            if (info.name.isEmpty()) continue;
            Map<?, ?> state = map(source.get("state"));
            info.linkState = firstString(state, "link_state", "linkstate",
                    firstString(source, "link_state", "linkstate", "UNKNOWN"));
            addAddresses(info.addresses, state == null ? null : state.get("aliases"));
            addAddresses(info.addresses, source.get("aliases"));
            target.interfaces.add(info);
        }
    }

    static void parseRealtime(DashboardData target, Map<?, ?> realtime) {
        if (realtime == null) return;
        Map<?, ?> memory = map(realtime.get("memory"));
        if (memory != null) {
            long total = longNumber(memory, "physical_memory_total", "total", 0);
            long available = longNumber(memory, "physical_memory_available", "available", 0);
            long used = longNumber(memory, "physical_memory_used", "used", -1);
            if (total > 0) target.memoryTotal = total;
            target.memoryUsed = used >= 0 ? used : Math.max(0, target.memoryTotal - available);
        }

        Map<?, ?> cpu = map(realtime.get("cpu"));
        if (cpu != null) {
            Map<?, ?> aggregate = map(cpu.get("cpu"));
            double usage = aggregate == null
                    ? decimal(cpu, "usage", "average", -1)
                    : decimal(aggregate, "usage", "average", -1);
            if (usage >= 0) target.cpuPercent = (int) Math.round(usage);
            double temperature = aggregate == null ? number(cpu.get("temp"), Double.NaN)
                    : number(aggregate.get("temp"), Double.NaN);
            if (Double.isNaN(temperature)) {
                double sum = 0;
                int count = 0;
                for (Object coreValue : cpu.values()) {
                    Map<?, ?> core = map(coreValue);
                    double coreTemp = core == null ? Double.NaN : number(core.get("temp"), Double.NaN);
                    if (!Double.isNaN(coreTemp)) { sum += coreTemp; count++; }
                }
                if (count > 0) temperature = sum / count;
            }
            target.cpuTemperatureC = temperature;
        }

        Map<?, ?> network = map(realtime.get("interfaces"));
        if (network != null) {
            for (Map.Entry<?, ?> entry : network.entrySet()) {
                String name = string(entry.getKey(), "");
                Map<?, ?> metrics = map(entry.getValue());
                if (name.isEmpty() || metrics == null) continue;
                DashboardData.InterfaceInfo info = findOrCreateInterface(target, name);
                info.linkState = firstString(metrics, "link_state", "state", info.linkState);
                info.rxBytesPerSecond = decimal(metrics, "received_bytes_rate", "rx_bytes", 0);
                info.txBytesPerSecond = decimal(metrics, "sent_bytes_rate", "tx_bytes", 0);
                // 25.04 reports interval byte counts when a link is down.
                if (info.rxBytesPerSecond == 0) info.rxBytesPerSecond = number(metrics.get("received_bytes"), 0);
                if (info.txBytesPerSecond == 0) info.txBytesPerSecond = number(metrics.get("sent_bytes"), 0);
            }
        }
    }

    private static DashboardData.InterfaceInfo findOrCreateInterface(DashboardData target, String name) {
        for (DashboardData.InterfaceInfo info : target.interfaces) if (name.equals(info.name)) return info;
        DashboardData.InterfaceInfo info = new DashboardData.InterfaceInfo();
        info.name = name;
        target.interfaces.add(info);
        return info;
    }

    private static void parsePorts(List<DashboardData.PortInfo> target, List<?> rows) {
        for (Object row : safeList(rows)) {
            Map<?, ?> source = map(row);
            if (source == null) continue;
            DashboardData.PortInfo port = new DashboardData.PortInfo();
            port.containerPort = integer(source, "container_port", "port", 0);
            port.protocol = firstString(source, "protocol", "type", "");
            for (Object hostRow : safeList(list(source.get("host_ports")))) {
                Map<?, ?> host = map(hostRow);
                if (host == null) continue;
                DashboardData.HostPortInfo mapping = new DashboardData.HostPortInfo();
                mapping.hostPort = integer(host, "host_port", "port", 0);
                mapping.hostIp = firstString(host, "host_ip", "ip", "");
                port.hostPorts.add(mapping);
            }
            target.add(port);
        }
    }

    private static void parseContainers(List<DashboardData.ContainerInfo> target, List<?> rows) {
        for (Object row : safeList(rows)) {
            Map<?, ?> source = map(row);
            if (source == null) continue;
            DashboardData.ContainerInfo container = new DashboardData.ContainerInfo();
            container.id = firstString(source, "id", "container_id", "");
            container.serviceName = firstString(source, "service_name", "name", "");
            container.image = firstString(source, "image", "image_name", "");
            parsePorts(container.ports, list(source.get("port_config")));
            target.add(container);
        }
    }

    private static void addAddresses(List<String> target, Object value) {
        for (Object row : safeList(list(value))) {
            Map<?, ?> alias = map(row);
            String address;
            if (alias == null) address = string(row, "");
            else address = firstString(alias, "address", "addr", "");
            if (!address.isEmpty() && !target.contains(address)) target.add(address);
        }
    }

    private static String firstPortal(Object value) {
        if (value instanceof String) return string(value, "");
        Map<?, ?> portals = map(value);
        if (portals != null) {
            for (Object portal : portals.values()) {
                String found = firstPortal(portal);
                if (!found.isEmpty()) return found;
            }
        }
        List<?> portalsList = list(value);
        if (portalsList != null) {
            for (Object portal : portalsList) {
                String found = firstPortal(portal);
                if (!found.isEmpty()) return found;
            }
        }
        return "";
    }

    private static Map<?, ?> map(Object value) {
        return value instanceof Map ? (Map<?, ?>) value : null;
    }

    private static List<?> list(Object value) {
        return value instanceof List ? (List<?>) value : null;
    }

    private static List<?> safeList(List<?> value) {
        return value == null ? Collections.emptyList() : value;
    }

    private static String firstString(Map<?, ?> source, String first, String second, String fallback) {
        if (source == null) return fallback;
        String value = string(source.get(first), "");
        return value.isEmpty() ? string(source.get(second), fallback) : value;
    }

    private static String string(Object value, String fallback) {
        if (value == null) return fallback;
        String result = String.valueOf(value);
        return result.isEmpty() || "null".equalsIgnoreCase(result) ? fallback : result;
    }

    private static boolean bool(Map<?, ?> source, String key, boolean fallback) {
        if (source == null || !source.containsKey(key)) return fallback;
        Object value = source.get(key);
        return value instanceof Boolean ? (Boolean) value : Boolean.parseBoolean(String.valueOf(value));
    }

    private static int integer(Map<?, ?> source, String first, String second, int fallback) {
        return (int) longNumber(source, first, second, fallback);
    }

    private static long longNumber(Map<?, ?> source, String first, String second, long fallback) {
        if (source == null) return fallback;
        Long value = longNumber(source.get(first));
        if (value != null) return value;
        value = longNumber(source.get(second));
        return value == null ? fallback : value;
    }

    private static Long longNumber(Object value) {
        if (value instanceof Number) return ((Number) value).longValue();
        try { return value == null ? null : Long.parseLong(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return null; }
    }

    private static double decimal(Map<?, ?> source, String first, String second, double fallback) {
        if (source == null) return fallback;
        double value = number(source.get(first), Double.NaN);
        return Double.isNaN(value) ? number(source.get(second), fallback) : value;
    }

    private static double number(Object value, double fallback) {
        if (value instanceof Number) return ((Number) value).doubleValue();
        try { return value == null ? fallback : Double.parseDouble(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return fallback; }
    }
}
