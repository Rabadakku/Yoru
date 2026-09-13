package dev.yoru.ui;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** "Saved in your vault · 5 minutes ago": when, the way a person says it. */
public final class AgoTest {
    private static int checks;
    private static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }

    private static void says(String expected, String then, String now, ZoneId zone) {
        String got = Ago.describe(Instant.parse(then), Instant.parse(now), zone);
        check(expected.equals(got), then + " seen at " + now + " reads \"" + expected + "\", got \"" + got + "\"");
    }

    public static void main(String[] args) {
        var utc = ZoneOffset.UTC;
        String now = "2026-09-13T12:00:00Z";
        says("just now", "2026-09-13T12:00:00Z", now, utc);
        says("just now", "2026-09-13T11:59:01Z", now, utc);
        says("just now", "2026-09-13T12:00:30Z", now, utc);          // a clock slightly ahead
        says("1 minute ago", "2026-09-13T11:59:00Z", now, utc);
        says("5 minutes ago", "2026-09-13T11:55:00Z", now, utc);
        says("59 minutes ago", "2026-09-13T11:01:00Z", now, utc);
        says("1 hour ago", "2026-09-13T11:00:00Z", now, utc);
        says("23 hours ago", "2026-09-12T13:00:00Z", now, utc);
        says("yesterday", "2026-09-12T08:00:00Z", now, utc);
        says("Tue 8 Sep", "2026-09-08T09:00:00Z", now, utc);

        // Across midnight an hour is still an hour, not "yesterday".
        says("1 hour ago", "2026-09-12T23:30:00Z", "2026-09-13T00:30:00Z", utc);

        // Which day it was depends on where the reader is.
        says("Fri 11 Sep", "2026-09-11T23:00:00Z", "2026-09-13T03:00:00Z", utc);
        says("yesterday", "2026-09-11T23:00:00Z", "2026-09-13T03:00:00Z", ZoneId.of("America/New_York"));

        System.out.println("PASS: " + checks + " relative time checks (minutes, hours, yesterday, date, zones)");
    }
}
