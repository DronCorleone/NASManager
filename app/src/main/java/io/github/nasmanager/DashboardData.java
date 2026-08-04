package io.github.nasmanager;

import java.util.ArrayList;
import java.util.List;

final class DashboardData {
    boolean online;
    String hostName = "TrueNAS";
    String version = "";
    double[] loadAverage = new double[]{0, 0, 0};
    int cpuCores = 1;
    int cpuPercent = -1;
    double cpuTemperatureC = Double.NaN;
    long memoryTotal;
    long memoryUsed = -1;
    long uptimeSeconds;
    final List<PoolInfo> pools = new ArrayList<>();
    final List<AppInfo> apps = new ArrayList<>();
    final List<InterfaceInfo> interfaces = new ArrayList<>();
    final List<AlertInfo> alerts = new ArrayList<>();

    static final class PoolInfo {
        String name;
        String status;
        long size;
        long used;
    }

    static final class AppInfo {
        String name;
        String displayName;
        String state;
        boolean updateAvailable;
        String iconUrl = "";
        String appVersion = "";
        String catalogRevision = "";
        String webUiUrl = "";
        int containerCount;
        final List<PortInfo> ports = new ArrayList<>();
        final List<ContainerInfo> containers = new ArrayList<>();
        AppStats stats;

        boolean isRunning() {
            return "RUNNING".equalsIgnoreCase(state) || "ACTIVE".equalsIgnoreCase(state);
        }
    }

    static final class PortInfo {
        int containerPort;
        String protocol = "";
        final List<HostPortInfo> hostPorts = new ArrayList<>();
    }

    static final class HostPortInfo {
        int hostPort;
        String hostIp = "";
    }

    static final class ContainerInfo {
        String id = "";
        String serviceName = "";
        String image = "";
        String state = "UNKNOWN";
        final List<PortInfo> ports = new ArrayList<>();
    }

    static final class AppLogEntry {
        String timestamp = "";
        String data = "";
    }

    static final class AppStats {
        String appName = "";
        double cpuPercent = -1;
        long memoryBytes = -1;
        double networkRxBytesPerSecond;
        double networkTxBytesPerSecond;
        long blockReadBytes;
        long blockWriteBytes;
    }

    static final class InterfaceInfo {
        String name = "";
        final List<String> addresses = new ArrayList<>();
        String linkState = "UNKNOWN";
        double rxBytesPerSecond;
        double txBytesPerSecond;
    }

    static final class AlertInfo {
        String id;
        String level;
        String title;
        String message;
        String date;
    }
}
