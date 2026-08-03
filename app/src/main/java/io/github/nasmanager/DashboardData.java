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
    long memoryTotal;
    long memoryUsed;
    long uptimeSeconds;
    final List<PoolInfo> pools = new ArrayList<>();
    final List<AppInfo> apps = new ArrayList<>();
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

        boolean isRunning() {
            return "RUNNING".equalsIgnoreCase(state) || "ACTIVE".equalsIgnoreCase(state);
        }
    }

    static final class AlertInfo {
        String id;
        String level;
        String title;
        String message;
        String date;
    }
}
