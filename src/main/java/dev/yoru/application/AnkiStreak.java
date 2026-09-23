package dev.yoru.application;

import dev.yoru.domain.Model.AnkiSnapshot;
import java.time.*;

/** Review-day streaks, independent of timed sittings or the study-session floor. */
public record AnkiStreak(int current, int best, LocalDate asOf, boolean cached) {
    public static AnkiStreak of(AnkiSnapshot snapshot, Instant now, ZoneId zone) {
        var today=now.atZone(zone).toLocalDate();
        var fetched=snapshot.fetchedAt().atZone(zone).toLocalDate();
        // Missing days after an offline refresh are unknown, not missed reviews.
        var asOf=fetched.isBefore(today)?fetched:today;
        int best=0, run=0;
        LocalDate previous=null;
        for(var day:snapshot.days().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey()).toList()) {
            if(day.getKey().isAfter(asOf)) break;
            if(day.getValue()==0) { run=0; previous=null; continue; }
            run=previous!=null&&previous.plusDays(1).equals(day.getKey())?run+1:1;
            best=Math.max(best,run);
            previous=day.getKey();
        }
        return new AnkiStreak(Analytics.streak(snapshot.days(),asOf),best,asOf,fetched.isBefore(today));
    }
}
