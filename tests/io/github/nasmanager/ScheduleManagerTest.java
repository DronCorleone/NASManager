package io.github.nasmanager;

import java.time.Instant;
import java.time.ZoneId;

public final class ScheduleManagerTest {
    public static void main(String[] args) {
        ZoneId utc = ZoneId.of("UTC");
        long before = Instant.parse("2026-08-04T07:59:30Z").toEpochMilli();
        assert DailyScheduleCalculator.nextTriggerMillis(before, utc, 8, 0)
                == Instant.parse("2026-08-04T08:00:00Z").toEpochMilli();

        long after = Instant.parse("2026-08-04T08:00:01Z").toEpochMilli();
        assert DailyScheduleCalculator.nextTriggerMillis(after, utc, 8, 0)
                == Instant.parse("2026-08-05T08:00:00Z").toEpochMilli();

        // DST spring-forward: 02:30 does not exist, so Java resolves to the next valid local time.
        ZoneId berlin = ZoneId.of("Europe/Berlin");
        long dstEve = Instant.parse("2026-03-28T22:00:00Z").toEpochMilli();
        assert DailyScheduleCalculator.nextTriggerMillis(dstEve, berlin, 2, 30)
                == Instant.parse("2026-03-29T01:30:00Z").toEpochMilli();

        boolean rejected = false;
        try { DailyScheduleCalculator.nextTriggerMillis(before, utc, 24, 0); }
        catch (IllegalArgumentException expected) { rejected = true; }
        assert rejected;
        System.out.println("ScheduleManagerTest: all checks passed");
    }
}
