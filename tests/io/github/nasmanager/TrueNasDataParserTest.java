package io.github.nasmanager;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TrueNasDataParserTest {
    public static void main(String[] args) {
        parsesAppQueryDetailsAcrossSupportedKeys();
        parsesIconFallbacksSafely();
        parsesAndSumsAppStats();
        parsesInterfacesAndRealtimeResources();
        toleratesMissingOptionalFields();
        System.out.println("TrueNasDataParser tests passed");
    }

    private static void parsesAppQueryDetailsAcrossSupportedKeys() {
        DashboardData data = new DashboardData();
        Map<String, Object> host = map("host_port", 2283, "host_ip", "0.0.0.0");
        Map<String, Object> port = map("container_port", 3001, "protocol", "tcp",
                "host_ports", list(host));
        Map<String, Object> container = map("id", "abc123", "service_name", "server",
                "image", "ghcr.io/immich/server:v2", "state", "running", "port_config", list(port));
        Map<String, Object> workloads = map("containers", 2, "used_ports", list(port),
                "container_details", list(container));
        Map<String, Object> app = map(
                "id", "immich", "name", "Immich", "state", "RUNNING",
                "upgrade_available", true, "human_version", "v2.5.0_1.4.2",
                "version", "1.4.2", "metadata", map("icon", "https://icons/immich.png", "app_version", "2.5.0"),
                "portals", map("Web UI", "http://192.168.1.20:2283"), "active_workloads", workloads);

        Map<String, Object> legacyKeys = map("id", "custom", "name", "Custom", "state", "STOPPED",
                "human_version", "3.1.0_1.2.0", "version", "1.2.0",
                "metadata", map("icon_url", "https://icons/custom.png"));
        TrueNasDataParser.parseApps(data, list(app, legacyKeys));
        assert data.apps.size() == 2;
        DashboardData.AppInfo parsed = data.apps.get(0);
        assert "immich".equals(parsed.name);
        assert "Immich".equals(parsed.displayName);
        assert "RUNNING".equals(parsed.state);
        assert parsed.updateAvailable;
        assert "https://icons/immich.png".equals(parsed.iconUrl);
        assert "2.5.0".equals(parsed.appVersion);
        assert "1.4.2".equals(parsed.catalogRevision);
        assert "http://192.168.1.20:2283".equals(parsed.webUiUrl);
        assert parsed.containerCount == 2;
        assert parsed.ports.size() == 1;
        assert parsed.ports.get(0).containerPort == 3001;
        assert parsed.ports.get(0).hostPorts.get(0).hostPort == 2283;
        assert parsed.containers.size() == 1;
        assert "server".equals(parsed.containers.get(0).serviceName);
        assert "RUNNING".equals(parsed.containers.get(0).state);
        assert "3.1.0_1.2.0".equals(data.apps.get(1).appVersion);
        assert "https://icons/custom.png".equals(data.apps.get(1).iconUrl);
    }

    private static void parsesAndSumsAppStats() {
        Map<String, Object> app = map("app_name", "immich", "cpu_usage", 12.5,
                "memory", 1_073_741_824L,
                "networks", list(
                        map("interface_name", "eth0", "rx_bytes", 1000, "tx_bytes", 2500),
                        map("interface_name", "eth1", "rx_bytes", 25, "tx_bytes", 75)),
                "blkio", map("read", 2_147_483_648L, "write", 4096));
        List<DashboardData.AppStats> result = TrueNasDataParser.parseAppStats(list(app));
        assert result.size() == 1;
        DashboardData.AppStats stats = result.get(0);
        assert stats.cpuPercent == 12.5;
        assert stats.memoryBytes == 1_073_741_824L;
        assert stats.networkRxBytesPerSecond == 1025;
        assert stats.networkTxBytesPerSecond == 2575;
        assert stats.blockReadBytes == 2_147_483_648L;
        assert stats.blockWriteBytes == 4096;
    }

    private static void parsesIconFallbacksSafely() {
        DashboardData data = new DashboardData();
        TrueNasDataParser.parseApps(data, list(
                map("id", "jellyfin", "metadata", map("name", "jellyfin")),
                map("id", "immich", "metadata", map("name", "immich",
                        "source", map("logo", map("url", "//cdn.example/immich.png")))),
                map("id", "filebrowser", "name", "File Browser"),
                map("id", "custom", "custom_app", true, "metadata", map("name", "private-app")),
                map("id", "unsafe", "metadata", map("icon_url", "file:///data/secret.png"))));

        Map<String, Object> catalog = map("community", map(
                "jellyfin", map("name", "jellyfin", "title", "Jellyfin", "icon_url", "https://icons/jellyfin.svg"),
                "immich", map("name", "immich", "title", "Immich", "icon_url", "https://icons/immich.svg"),
                "filebrowser", map("name", "filebrowser", "title", "File Browser", "icon_url", "https://icons/filebrowser.png")));
        TrueNasDataParser.mergeCatalogIcons(data, catalog);

        assert "https://icons/jellyfin.svg".equals(data.apps.get(0).iconUrl);
        assert "https://cdn.example/immich.png".equals(data.apps.get(1).iconUrl);
        assert "https://icons/filebrowser.png".equals(data.apps.get(2).iconUrl);
        assert "".equals(data.apps.get(3).iconUrl);
        assert "".equals(data.apps.get(4).iconUrl);
        assert "https://icons/jellyfin.png?size=144#app".equals(
                TrueNasDataParser.rasterIconUrl("https://icons/jellyfin.SVG?size=144#app"));
        assert "http://icons/filebrowser.webp?x=1".equals(
                TrueNasDataParser.rasterIconUrl("http://icons/filebrowser.webp?x=1"));
        assert "".equals(TrueNasDataParser.rasterIconUrl("file:///private/icon.svg"));
    }

    private static void parsesInterfacesAndRealtimeResources() {
        DashboardData data = new DashboardData();
        TrueNasDataParser.parseInterfaces(data, list(map("id", "eno1",
                "state", map("link_state", "LINK_STATE_UP",
                        "aliases", list(map("address", "192.168.31.20"))),
                "aliases", list(map("address", "192.168.31.20")))));
        Map<String, Object> cpu = map("cpu", map("usage", 31.6, "temp", 52.25),
                "cpu0", map("usage", 20, "temp", 51));
        Map<String, Object> memory = map("physical_memory_total", 16_000L,
                "physical_memory_available", 6_000L);
        Map<String, Object> interfaces = map("eno1", map("link_state", "LINK_STATE_UP",
                "received_bytes_rate", 1234.5, "sent_bytes_rate", 4567.5));
        TrueNasDataParser.parseRealtime(data, map("cpu", cpu, "memory", memory, "interfaces", interfaces));

        assert data.cpuPercent == 32;
        assert data.cpuTemperatureC == 52.25;
        assert data.memoryTotal == 16_000L;
        assert data.memoryUsed == 10_000L;
        assert data.interfaces.size() == 1;
        DashboardData.InterfaceInfo info = data.interfaces.get(0);
        assert "eno1".equals(info.name);
        assert info.addresses.size() == 1;
        assert "192.168.31.20".equals(info.addresses.get(0));
        assert info.rxBytesPerSecond == 1234.5;
        assert info.txBytesPerSecond == 4567.5;
    }

    private static void toleratesMissingOptionalFields() {
        DashboardData data = new DashboardData();
        TrueNasDataParser.parseApps(data, list(map("name", "custom", "state", "stopped")));
        DashboardData.AppInfo app = data.apps.get(0);
        assert "custom".equals(app.name);
        assert "".equals(app.iconUrl);
        assert "".equals(app.webUiUrl);
        assert "STOPPED".equals(app.state);
        assert TrueNasDataParser.parseAppStats(map("fields", list())).isEmpty();
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
