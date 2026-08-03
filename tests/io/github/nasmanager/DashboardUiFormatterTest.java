package io.github.nasmanager;

import java.time.ZoneId;
import java.util.Locale;

public final class DashboardUiFormatterTest {
    public static void main(String[] args) {
        assert DashboardUiFormatter.cpuPercent(-1) == -1;
        assert DashboardUiFormatter.cpuPercent(42) == 42;
        assert DashboardUiFormatter.cpuPercent(140) == 100;
        assert DashboardUiFormatter.percentage(3, 12) == 25;
        assert DashboardUiFormatter.percentage(0, 12) == 0;
        assert DashboardUiFormatter.percentage(-1, 12) == -1;
        assert DashboardUiFormatter.percentage(10, 0) == -1;
        assert "1.25".equals(DashboardUiFormatter.formatLoadAverage(1.25, Locale.ENGLISH));

        String html = "Line one<br>192.168.31.52.<br><a href=\"https://example.test\">documentation</a> &amp; help";
        assert "Line one\n192.168.31.52.\ndocumentation & help".equals(
                DashboardUiFormatter.cleanAlertText(html));
        assert "Disk • failed".equals(DashboardUiFormatter.cleanAlertText("Disk &#8226; failed"));

        ZoneId utc = ZoneId.of("UTC");
        assert "03.08.2026 21:15".equals(DashboardUiFormatter.formatAlertDate(
                "1785791700000", new Locale("ru"), utc));
        assert "Aug 3, 2026, 21:15".equals(DashboardUiFormatter.formatAlertDate(
                "2026-08-03T21:15:00Z", Locale.ENGLISH, utc));

        Exception httpError = new Exception("HTTP 400: {\"message\":\"API key has been revoked\\nand must be renewed\"}");
        assert "HTTP 400 — API key has been revoked\nand must be renewed".equals(
                DashboardUiFormatter.friendlyError(httpError));
        String longMessage = "This detail must remain visible because the error dialog is scrollable and selectable. ".repeat(4);
        assert longMessage.trim().equals(DashboardUiFormatter.friendlyError(new Exception(longMessage)));

        System.out.println("DashboardUiFormatterTest: all checks passed");
    }
}
