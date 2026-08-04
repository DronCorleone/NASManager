package io.github.nasmanager;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

final class DailyScheduleCalculator {
    private DailyScheduleCalculator() { }

    static long nextTriggerMillis(long nowMillis, ZoneId zone, int hour, int minute) {
        validateTime(hour, minute);
        ZonedDateTime now = Instant.ofEpochMilli(nowMillis).atZone(zone);
        LocalDateTime localTarget = now.toLocalDate().atTime(hour, minute);
        ZonedDateTime target = localTarget.atZone(zone);
        if (!target.toInstant().isAfter(now.toInstant())) {
            target = localTarget.plusDays(1).atZone(zone);
        }
        return target.toInstant().toEpochMilli();
    }

    static void validateTime(int hour, int minute) {
        if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
            throw new IllegalArgumentException("Invalid daily schedule time");
        }
    }
}
