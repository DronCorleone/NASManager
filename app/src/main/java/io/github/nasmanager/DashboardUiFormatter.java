package io.github.nasmanager;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure presentation helpers kept independent from Android so they can be unit-tested. */
final class DashboardUiFormatter {
    private static final Pattern JSON_ERROR = Pattern.compile(
            "\\\"(?:message|reason|error)\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\"",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(x?[0-9a-fA-F]+);");

    private DashboardUiFormatter() { }

    static int cpuPercent(int value) {
        return value < 0 ? -1 : Math.min(100, value);
    }

    static int percentage(long used, long total) {
        if (used < 0 || total <= 0) return -1;
        long bounded = Math.min(used, total);
        return (int) Math.round(bounded * 100.0 / total);
    }

    static String formatLoadAverage(double value, Locale locale) {
        if (Double.isNaN(value) || Double.isInfinite(value) || value < 0) return "";
        return String.format(locale, "%.2f", value);
    }

    static String cleanAlertText(String value) {
        if (value == null || value.trim().isEmpty()) return "";
        String clean = value
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</(?:p|div|li|h[1-6])\\s*>", "\n")
                .replaceAll("(?i)<li(?:\\s[^>]*)?>", "• ")
                .replaceAll("<[^>]+>", "")
                .replace("&nbsp;", " ")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&amp;", "&");
        clean = decodeNumericEntities(clean).replace("\r", "");
        clean = clean.replaceAll("[ \\t]+", " ")
                .replaceAll(" *\\n *", "\n")
                .replaceAll("\\n{3,}", "\n\n");
        return clean.trim();
    }

    static String formatAlertDate(String raw, Locale locale, ZoneId zoneId) {
        if (raw == null || raw.trim().isEmpty()) return "";
        String value = raw.trim();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        Instant instant = parseInstant(value, zoneId);
        if (instant == null) return value.length() > 16 ? value.substring(0, 16).replace('T', ' ') : value;
        String pattern = "ru".equals(locale.getLanguage()) ? "dd.MM.yyyy HH:mm" : "MMM d, yyyy, HH:mm";
        return DateTimeFormatter.ofPattern(pattern, locale).withZone(zoneId).format(instant);
    }

    static String friendlyError(Throwable error) {
        if (error == null) return "Unknown error";
        Throwable current = error;
        String message = "";
        while (current != null) {
            if (current.getMessage() != null && !current.getMessage().trim().isEmpty()) {
                message = current.getMessage().trim();
                break;
            }
            current = current.getCause();
        }
        if (message.isEmpty()) return error.getClass().getSimpleName();

        Matcher match = JSON_ERROR.matcher(message);
        if (match.find()) {
            String detail = decodeJsonString(match.group(1));
            Matcher status = Pattern.compile("^(HTTP\\s+\\d+)", Pattern.CASE_INSENSITIVE).matcher(message);
            return status.find() ? status.group(1) + " — " + detail : detail;
        }
        return cleanAlertText(message);
    }

    private static Instant parseInstant(String value, ZoneId zoneId) {
        try {
            long timestamp = Long.parseLong(value);
            if (Math.abs(timestamp) < 100_000_000_000L) timestamp *= 1000;
            return Instant.ofEpochMilli(timestamp);
        } catch (NumberFormatException ignored) { }
        try { return Instant.parse(value); } catch (DateTimeParseException ignored) { }
        try { return OffsetDateTime.parse(value).toInstant(); } catch (DateTimeParseException ignored) { }
        try { return LocalDateTime.parse(value).atZone(zoneId).toInstant(); }
        catch (DateTimeParseException ignored) { return null; }
    }

    private static String decodeNumericEntities(String value) {
        Matcher matcher = NUMERIC_ENTITY.matcher(value);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String number = matcher.group(1);
            int radix = number.startsWith("x") || number.startsWith("X") ? 16 : 10;
            if (radix == 16) number = number.substring(1);
            try {
                matcher.appendReplacement(result, Matcher.quoteReplacement(
                        new String(Character.toChars(Integer.parseInt(number, radix)))));
            } catch (IllegalArgumentException invalidEntity) {
                matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group()));
            }
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static String decodeJsonString(String value) {
        return value.replace("\\n", "\n")
                .replace("\\r", "")
                .replace("\\t", "\t")
                .replace("\\\"", "\"")
                .replace("\\/", "/")
                .replace("\\\\", "\\");
    }
}
