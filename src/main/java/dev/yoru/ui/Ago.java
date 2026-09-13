package dev.yoru.ui;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** When something happened, as a person says it: "just now", "5 minutes ago", "yesterday", "Tue 8 Sep". */
final class Ago {
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);

    private Ago() { }

    static String describe(Instant then, Instant now, ZoneId zone) {
        long seconds = Duration.between(then, now).getSeconds();
        // Under a minute, or a clock a little ahead of this one.
        if (seconds < 60) return "just now";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes == 1 ? "1 minute ago" : minutes + " minutes ago";
        long hours = minutes / 60;
        // Hours before dates, so an hour across midnight is not "yesterday".
        if (hours < 24) return hours == 1 ? "1 hour ago" : hours + " hours ago";
        var day = then.atZone(zone).toLocalDate();
        if (day.equals(now.atZone(zone).toLocalDate().minusDays(1))) return "yesterday";
        return then.atZone(zone).format(DAY);
    }
}
