package dev.yoru;

import dev.yoru.application.AnkiStreak;
import dev.yoru.domain.Model.*;
import dev.yoru.persistence.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Synthetic daily counts, independent of study-time sessions. */
public final class AnkiStreakTest {
    private static int checks;
    private static void check(boolean ok,String why) { checks++; if(!ok)throw new AssertionError(why); }
    private static final Instant NOW=Instant.parse("2026-09-23T12:00:00Z");
    private static final LocalDate TODAY=LocalDate.of(2026,9,23);
    private static AnkiSnapshot snapshot(Map<LocalDate,Long> days,Instant fetched) {
        return new AnkiSnapshot("Practice",1,days,fetched);
    }
    private static AnkiStreak stats(Map<LocalDate,Long> days) {
        return AnkiStreak.of(snapshot(days,NOW),NOW,ZoneOffset.UTC);
    }
    public static void main(String[] args)throws Exception {
        var days=new TreeMap<LocalDate,Long>();
        for(int i=0;i<400;i++) days.put(TODAY.minusDays(i),1L);
        var cached=snapshot(days,NOW);
        check(cached.days().size()==400,"Aggregate history is not trimmed to the chart's month");
        check(stats(days).current()==400&&stats(days).best()==400,"Long runs count any review");
        days.remove(TODAY);
        check(stats(days).current()==399,"An unfinished today keeps yesterday's run");
        days.put(TODAY,0L);
        check(stats(days).current()==399,"An explicit zero today has the same grace");
        days.remove(TODAY.minusDays(1));
        check(stats(days).current()==0&&stats(days).best()==398,"A missed yesterday breaks the current run");
        days.put(TODAY,1L);
        check(stats(days).current()==1,"One review starts a new run");
        days.put(TODAY.plusDays(1),900L);
        check(stats(days).current()==1&&stats(days).best()==398,"Future data cannot extend either run");
        check(stats(Map.of()).current()==0&&stats(Map.of()).best()==0,"Empty history has no streak");
        var offline=AnkiStreak.of(cached,NOW.plus(Duration.ofDays(5)),ZoneOffset.UTC);
        check(offline.current()==400&&offline.cached()&&offline.asOf().equals(TODAY),"Offline history is explicitly as of its last sync");
        var midnight=Instant.parse("2026-09-24T00:30:00Z");
        var west=AnkiStreak.of(snapshot(Map.of(TODAY,1L),midnight),midnight,ZoneId.of("America/Los_Angeles"));
        check(west.asOf().equals(TODAY)&&west.current()==1&&!west.cached(),"The chosen zone defines the current date");
        var gap=new TreeMap<LocalDate,Long>();
        gap.put(TODAY.minusDays(2),3L);gap.put(TODAY.minusDays(1),0L);gap.put(TODAY,2L);
        check(stats(gap).best()==1,"Explicit zero splits the best run");
        var state=State.empty().withAnki(new Anki(true,"",false,1,cached));
        check(state.sessions().isEmpty()&&AnkiStreak.of(state.anki().last(),NOW,ZoneOffset.UTC).current()==400,
            "Streaks need no study session or manual habit, even with time import off");
        check(PortableVault.parse(PortableVault.export(state,NOW)).equals(state),"All review days survive portable export");
        var dir=Files.createTempDirectory("yoru-anki-streak");
        try {
            var file=dir.resolve("synthetic.vault");
            try(var vault=new EncryptedVault(file,"fixture-password".toCharArray())) { vault.save(state); }
            try(var vault=new EncryptedVault(file,"fixture-password".toCharArray())) {
                check(vault.load().equals(state),"All review days survive encrypted reopening");
            }
            // The original 30-entry cache still loads without a new field or schema.
            var month=new TreeMap<LocalDate,Long>(new TreeMap<>(cached.days()).tailMap(TODAY.minusDays(29)));
            var old=state.withAnki(state.anki().withSnapshot(snapshot(month,NOW)));
            try(var vault=new EncryptedVault(file,"fixture-password".toCharArray())) { vault.save(old); }
            try(var vault=new EncryptedVault(file,"fixture-password".toCharArray())) {
                check(vault.load().equals(old),"Existing month-sized summaries remain valid");
            }
        } finally {
            try(var files=Files.walk(dir)) { for(var file:files.sorted(Comparator.reverseOrder()).toList())Files.delete(file); }
        }
        System.out.println("PASS: "+checks+" automatic Anki streak checks (long history, gaps, offline, zones, vaults)");
    }
}
