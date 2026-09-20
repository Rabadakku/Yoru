package dev.yoru.persistence;

import dev.yoru.domain.Model.State;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Vault backups are pruned as they are taken (#7): the ten newest, the first of
 * each of the last thirty days, anything dated in the future, and never a file
 * that is not one of this vault's reset backups.
 *
 * The expected counts below are worked out by hand from the schedules, not
 * computed with the rule under test. Invented vaults in a temporary folder only.
 */
public final class BackupRetentionTest {
    private static int checks;
    private static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }

    private static final Instant NOW = Instant.parse("2026-09-14T15:00:00Z");

    public static void main(String[] args) throws Exception {
        aBurstKeepsTheNewestAndTheFirstOfTheDay();
        aLongHistoryKeepsOnePerDay();
        aClockThatMovedBackLosesNothing();
        onDiskOnlyThisVaultsResetBackupsArePruned();
        System.out.println("PASS: " + checks + " backup retention checks (newest, daily, future, on disk)");
    }

    private static Map<Path, Instant> taken(List<Instant> when) {
        var map = new LinkedHashMap<Path, Instant>();
        for (int i = 0; i < when.size(); i++)
            map.put(Path.of("Invented.yoru.reset-" + when.get(i).toEpochMilli() + "-" + i + ".bak"), when.get(i));
        return map;
    }

    /** Twenty-five deletions in a row, a minute apart, on one morning. */
    private static void aBurstKeepsTheNewestAndTheFirstOfTheDay() {
        var times = new java.util.ArrayList<Instant>();
        Instant first = Instant.parse("2026-09-14T09:00:00Z");
        for (int i = 0; i < 25; i++) times.add(first.plus(Duration.ofMinutes(i)));
        var backups = taken(times);
        var expired = new HashSet<>(EncryptedVault.expired(backups, NOW, ZoneOffset.UTC));
        check(expired.size() == 14, "25 backups keep the newest 10 and the day's first, so 14 go, got " + expired.size());
        Path at0900 = pathAt(backups, first), at0901 = pathAt(backups, first.plus(Duration.ofMinutes(1)));
        check(!expired.contains(at0900), "the first backup of the day survives the burst");
        check(expired.contains(at0901), "the one after it does not");
        for (int i = 15; i < 25; i++)
            check(!expired.contains(pathAt(backups, first.plus(Duration.ofMinutes(i)))), "the ten newest survive, minute " + i);
        check(EncryptedVault.expired(taken(times.subList(0, 10)), NOW, ZoneOffset.UTC).isEmpty(), "ten backups are all kept");
    }

    /** One backup every six hours for sixty days. */
    private static void aLongHistoryKeepsOnePerDay() {
        var times = new java.util.ArrayList<Instant>();
        for (int k = 0; k < 240; k++) times.add(NOW.minus(Duration.ofHours(6L * k)));
        var backups = taken(times);
        var expired = new HashSet<>(EncryptedVault.expired(backups, NOW, ZoneOffset.UTC));
        // The newest ten reach back to 09:00 on the 12th and hold the 03:00
        // backups of the 14th and 13th; the other 28 days each keep 03:00.
        check(expired.size() == 240 - 38, "10 newest + 30 days - 2 shared kept, so 202 go, got " + expired.size());
        var kept = backups.entrySet().stream().filter(e -> !expired.contains(e.getKey())).map(Map.Entry::getValue).toList();
        LocalDate oldest = kept.stream().min(Comparator.naturalOrder()).orElseThrow().atZone(ZoneOffset.UTC).toLocalDate();
        check(oldest.equals(LocalDate.parse("2026-08-16")), "nothing older than thirty days is kept, oldest " + oldest);
        check(kept.stream().filter(t -> t.isBefore(Instant.parse("2026-09-12T09:00:00Z")))
            .allMatch(t -> t.atZone(ZoneOffset.UTC).getHour() == 3), "outside the newest ten, only each day's first is kept");
    }

    /** Fifteen backups dated tomorrow, as after the clock was set back a day, and three from today. */
    private static void aClockThatMovedBackLosesNothing() {
        var times = new java.util.ArrayList<Instant>();
        for (int i = 0; i < 15; i++) times.add(NOW.plus(Duration.ofDays(1)).plus(Duration.ofMinutes(i)));
        Instant morning = Instant.parse("2026-09-14T08:00:00Z");
        for (int i = 0; i < 3; i++) times.add(morning.plus(Duration.ofMinutes(i)));
        var backups = taken(times);
        var expired = EncryptedVault.expired(backups, NOW, ZoneOffset.UTC);
        check(expired.size() == 2, "all fifteen future backups and today's first are kept, got " + expired.size() + " expired");
        check(!expired.contains(pathAt(backups, morning)), "today's first backup is one of them");
    }

    private static void onDiskOnlyThisVaultsResetBackupsArePruned() throws IOException {
        Path dir = Files.createTempDirectory("yoru-backups");
        try {
            var store = new VaultStore(dir);
            Path file = store.path("Invented");
            String base = file.getFileName().toString();
            long old = Instant.now().minus(Duration.ofDays(90)).toEpochMilli();
            for (int i = 0; i < 20; i++)
                Files.write(dir.resolve(base + ".reset-" + (old + i * 1000L) + "-" + UUID.randomUUID() + ".bak"), new byte[] {1});
            Path oldest = dir.resolve(base + ".reset-" + old + "-" + UUID.randomUUID() + ".bak");
            Files.write(oldest, new byte[] {1});
            Path migration = dir.resolve(base + ".v11.bak");
            Path stale = dir.resolve(base + ".v11.stale-" + old + "-" + UUID.randomUUID() + ".bak");
            Path unreadable = dir.resolve(base + ".reset-unknown.bak");
            Path neighbour = dir.resolve(store.path("Invented 2").getFileName() + ".reset-" + old + "-" + UUID.randomUUID() + ".bak");
            for (Path p : List.of(migration, stale, unreadable, neighbour)) Files.write(p, new byte[] {1});

            try (var vault = store.createPasswordless("Invented")) { vault.save(State.empty()); }
            State before;
            try (var vault = store.open("Invented")) {
                before = vault.load();
                vault.backup();
            }
            var left = EncryptedVault.resetBackups(file);
            check(left.size() == EncryptedVault.RECENT_BACKUPS,
                "22 reset backups from one old day and today keep the newest ten, got " + left.size());
            check(left.values().stream().anyMatch(t -> t.isAfter(Instant.ofEpochMilli(old + Duration.ofDays(1).toMillis()))),
                "the backup just taken is one of them");
            check(!Files.exists(oldest), "the oldest reset backup is removed");
            // It is made through a flushed temporary copy, since it may be what
            // replaces the older ones: the copy is the vault, only its owner can
            // read it, and no half-made copy is left beside it.
            Path taken = left.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
            check(Files.mismatch(taken, file) == -1, "the backup just taken is the vault, byte for byte");
            if (Files.getFileStore(taken).supportsFileAttributeView("posix"))
                check(Files.getPosixFilePermissions(taken).equals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")),
                    "and only its owner can read it");
            try (var files = Files.list(dir)) {
                check(files.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")), "and no temporary copy is left");
            }
            for (Path p : List.of(migration, stale, unreadable, neighbour))
                check(Files.exists(p), "a file that is not this vault's reset backup is never pruned: " + p.getFileName());
            try (var reopened = store.open("Invented")) {
                check(reopened.load().equals(before), "the vault itself is untouched");
            }
        } finally {
            try (var files = Files.walk(dir)) {
                for (Path p : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
            }
        }
    }

    private static Path pathAt(Map<Path, Instant> backups, Instant when) {
        return backups.entrySet().stream().filter(e -> e.getValue().equals(when)).findFirst().orElseThrow().getKey();
    }
}
