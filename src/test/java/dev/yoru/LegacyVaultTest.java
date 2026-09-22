package dev.yoru;

import dev.yoru.domain.Model.*;
import dev.yoru.persistence.EncryptedVault;
import java.nio.file.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * A real vault written by an older build, kept as bytes so every future schema
 * bump has to keep opening it.
 *
 * This fixture was produced by the schema-5 jar that was sitting on the Desktop
 * on 2026-09-09 — not by this codebase pretending to be old. That distinction is
 * the point: a migration test that writes its own input with today's writer only
 * proves the writer and reader agree with each other, which they always will.
 *
 * If you bump SCHEMA, do not regenerate this file. Make it load.
 */
public final class LegacyVaultTest {
    private static int checks;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}

    private static final String PASSWORD="legacy-vault-password";
    private static final String VAULT=
        "WU9SVQAAAAHTEl09owtAU3BqEHvG65UFfXc2YuNGYvOo/622vhsVkeatw1ScVF8NpCkzDngs" +
        "y/OGHp0aj6qZl9G5SyjZR7iasHKfkm/b3GQYGrfM8vNwf+uxVu83F0anOwDBSC4xkYy10otm" +
        "kdb4iiLoMfDgie5zIIcyXFYQQuT5ivD42NV/paYcLYjYtC3NDPRqMCFkgTAQjQgCBlgg74Ef" +
        "/JfEB8wepHQaaoqeg2ccETAogrzLwZ3d+rrXxa4o7ri36O48vOdfQ3dZTQHPpFM2iaupRqps" +
        "LiJT357sCQzE35gwW1kU10jyQuFZBGo/0e6AQHTUV6c2NDhTYWcIt2eD0CTiTBzUUz0sB31p" +
        "jxjZN+fAlsMs68T6gcdU4x0vNsShmu8NpzsVjLRyOT9NoVYLq8Noe3+WNq2/It0RjlgpMb1h" +
        "1um8UHUOeBVGkLnyJ9tZlAxrUDEP3Eyz7oVx2ovY6MW+q0aMcbUAz1L9HIGsqWNWxM0Hxbgc" +
        "tw==";

    /**
     * A vault written by 1.0.10 (schema 12), the one release with the Waifu
     * theme: its settings name that theme and carry a portrait choice. Both are
     * gone, so this is the file that proves the removal did not strand anyone —
     * the theme has to read as Moonlight, and the portrait bytes have to be
     * stepped over, or every record after them is read at the wrong offset.
     */
    private static final String ILLUSTRATED_VAULT=
        "WU9SVQAAAAGXSRSYpzp3tMi7xJYt9/MTO78Gc1uzCbXwrQiDdiyx3l2+nNsmB+uUs4KjSdBL" +
        "H4X4vCCNMaLZieZ9z6oHUC4yhXUkoVGQhhhHqcK+EkjJQffrlA8OGky5uxCx76MqTOpOJ5sI" +
        "LceYnw9/SlqfmaKZS64/1thavXOheGmk0yZ5kLmARrf/4dky2DrB3pk9kCUACQeel7XWf5wh" +
        "KS+w4yWHQr9VfJQaFIQFGzTf";

    /**
     * A vault written by 1.0.15 (schema 13), the last schema before Pages:
     * an activity, a tag, a session, a task with a plan and a habit, all
     * invented. Opened now, it must arrive with no pages and no page links.
     */
    private static final String PAGES_BEFORE_VAULT=
        "WU9SVQAAAAH8t6c/Z11zPnrJZf0fTt+62QQilpB/fmEAfGN3QEDDvxfC9Mcr3+JqYtRJq5Di" +
        "joNwqmyC4cIWSW6BLHbq80JnlK6ut4NHCPyYB1d0A4jBmhQRv3JzLIGtMO2yGZXqhJp+U9uh" +
        "+SZWQMtgO9qZUFkRsuTmR/H4y/kjP7S8NUrnTEdIuBN9rEUJ3rSpRd2CNJ94d8v/+k+FtLss" +
        "I5R4lMo2SqVflAsd4uLg0mcM/Y4HfQ/hPu90YRSXsstL6fAigtmRQ14Alebd32pYj3Btu/Gm" +
        "Bqk89TlojSB6m7P6oBeRdMejGryQnav/lrsFd51PWucL7YimGc+LYdwZV5aFujMxMjFs8RK4" +
        "wptyV+70AuVM3N5maFKgzFR7raCt7qCBWeWKk8kJPt8VHc0Waf/Heah46wp8nAOOgWtYi2mM" +
        "UnwodzO4h6q4ZZC5dc2YLbAolrPH7R08+0DCmUDr85zc29qGowaAcSFDu3php+b68uVhc6QD" +
        "nNKKr9wByJlMumfBaZmzNJJ3U2laf/qnpGl4gOBtKBTCXV3jxctTpn2zNOOizGETxSXaPlL+" +
        "kBPI1OWi";

    public static void main(String[] args)throws Exception{
        Path dir=Files.createTempDirectory("yoru-legacy-");
        try {
            Path file=dir.resolve("legacy.vault");
            Files.write(file,Base64.getDecoder().decode(VAULT));

            State loaded;
            try(var vault=new EncryptedVault(file,PASSWORD.toCharArray())){loaded=vault.load();}

            check(loaded.activities().size()==1,"An older vault still opens");
            check(loaded.activities().getFirst().name().equals("Study"),"Its activity survives");
            check(loaded.activities().getFirst().targetMinutes()==30,"Its daily target survives");
            check(loaded.sessions().size()==1,"Its recorded session survives");
            check(loaded.sessions().getFirst().seconds(null)==5400,"The session keeps its length");
            check(loaded.blocks().size()==1,"Its one-off schedule block survives");
            check(loaded.tasks().size()==1,"Its task survives");
            var task=loaded.tasks().getFirst();
            check(task.title().equals("Read chapter 4"),"The task keeps its title");
            check(task.due().equals(LocalDate.parse("2026-09-11")),"The task keeps its due date");
            check(task.notes().equals("notes"),"The task keeps its notes");
            check(task.source().equals("syllabus.pdf"),"The task keeps where it came from");
            check(task.status()==TaskStatus.TODO,"An unfinished task reads as TODO, not DONE");

            // Fields that did not exist when this vault was written must arrive as
            // sensible empties rather than as a failure to load.
            check(loaded.recurring().isEmpty(),"The weekly template arrives empty");
            check(loaded.tags().isEmpty(),"Tags arrive empty");
            check(task.tagId()==null,"The task is untagged");
            check(loaded.settings().equals(Settings.defaults()),"Settings fall back to the defaults");
            // A theme that no longer exists reads as the nearest one that does,
            // rather than failing the whole vault on a name.
            check(ThemeId.known("WAIFU")==ThemeId.MOONLIGHT,"The withdrawn Waifu theme opens as Moonlight");
            check(ThemeId.known("NO_SUCH_THEME")==ThemeId.MIDNIGHT,"An unknown theme falls back to the default");

            // And it must survive being written back out at the current schema.
            Path again=dir.resolve("resaved.vault");
            try(var vault=new EncryptedVault(again,PASSWORD.toCharArray())){vault.save(loaded);}
            try(var vault=new EncryptedVault(again,PASSWORD.toCharArray())){
                check(vault.load().equals(loaded),"Re-saving at the current schema loses nothing");
            }
        } finally {
            try(var files=Files.walk(dir)){
                for(var path:files.sorted(Comparator.reverseOrder()).toList())Files.delete(path);
            }
        }
        illustrated();
        beforePages();
        System.out.println("PASS: "+checks+" legacy vault checks (an older build's file still opens)");
    }

    /** The 1.0.10 vault above, opened by a build that no longer has the theme it names. */
    private static void illustrated() throws Exception {
        Path dir=Files.createTempDirectory("yoru-illustrated-");
        try {
            Path file=dir.resolve("illustrated.vault");
            Files.write(file,Base64.getDecoder().decode(ILLUSTRATED_VAULT));

            State loaded;
            try(var vault=new EncryptedVault(file,PASSWORD.toCharArray())){loaded=vault.load();}

            check(loaded.settings().theme()==ThemeId.MOONLIGHT,
                "A vault saved with the withdrawn Waifu theme opens as Moonlight");
            // Everything written after the portrait choice: proof the reader
            // stepped over exactly those bytes and no others.
            check(loaded.settings().dailyGoalHours()==6,"Its daily goal survives");
            check(loaded.settings().minSessionSeconds()==120,"Its session floor survives");
            check(loaded.settings().weekStartsOn()==java.time.DayOfWeek.SUNDAY,"Its week start survives");
            check(loaded.activities().size()==1&&loaded.activities().getFirst().name().equals("Study"),
                "Its activity survives");
            check(loaded.activities().getFirst().targetMinutes()==30,"Its daily target survives");

            // And it upgrades: saved again at the current schema, it reads back the same.
            Path again=dir.resolve("resaved.vault");
            try(var vault=new EncryptedVault(again,PASSWORD.toCharArray())){vault.save(loaded);}
            try(var vault=new EncryptedVault(again,PASSWORD.toCharArray())){
                check(vault.load().equals(loaded),"Re-saving it at the current schema loses nothing");
            }
        } finally {
            try(var files=Files.walk(dir)){
                for(var path:files.sorted(Comparator.reverseOrder()).toList())Files.delete(path);
            }
        }
    }

    /** The 1.0.15 vault above: opened by a build with Pages, then carrying them. */
    private static void beforePages() throws Exception {
        Path dir=Files.createTempDirectory("yoru-before-pages-");
        try {
            Path file=dir.resolve("before-pages.vault");
            Files.write(file,Base64.getDecoder().decode(PAGES_BEFORE_VAULT));
            // A fresh array each time: the vault wipes the password it is given.
            java.util.function.Supplier<char[]> password=()->"fixture-password-13".toCharArray();
            State loaded;
            try(var vault=new EncryptedVault(file,password.get())){loaded=vault.load();}

            check(loaded.activities().size()==1&&loaded.activities().getFirst().targetMinutes()==45,
                "A schema 13 vault keeps its activity");
            check(loaded.sessions().size()==1&&loaded.sessions().getFirst().seconds(null)==3600,"and its session");
            check(loaded.habits().size()==1,"and its habit");
            var task=loaded.tasks().getFirst();
            check(task.title().equals("Read chapter 4")&&task.status()==TaskStatus.DOING&&task.order()==2,
                "and its task, status and order");
            check(task.plannedFor().equals(LocalDate.parse("2026-09-24"))&&task.due().equals(LocalDate.parse("2026-09-25")),
                "and the task's plan and deadline");
            check(task.tagId()!=null&&loaded.tags().getFirst().id().equals(task.tagId()),"and its tag");
            check(loaded.notes().folders().isEmpty()&&loaded.notes().pages().isEmpty(),"It arrives with no pages");
            check(task.pageIds().isEmpty(),"and its task links to none");

            // Upgraded in place, it reads back the same.
            try(var vault=new EncryptedVault(file,password.get())){vault.save(loaded);}
            try(var vault=new EncryptedVault(file,password.get())){
                check(vault.load().equals(loaded),"Re-saving it at schema 14 loses nothing");
            }
            // The first upgraded save keeps the schema 13 bytes beside it.
            try(var files=Files.list(dir)){
                check(files.anyMatch(f->f.getFileName().toString().startsWith("before-pages.vault.v13")),
                    "The schema 13 file is kept as a backup before the upgrade");
            }

            // Then it holds pages: a folder, a page longer than 64 KB, one in the
            // trash, and the task linked to both.
            var now=Instant.parse("2026-09-22T09:00:00Z");
            var folder=new Folder(UUID.randomUUID(),null,"Classes",now,null);
            var longPage=new Page(UUID.randomUUID(),folder.id(),"Lecture 1",
                "# Cells\n[[Lecture 2]] 日本語\n"+"x".repeat(80_000),now,now,null);
            var trashed=new Page(UUID.randomUUID(),null,"Scrap","gone soon",now,now,now);
            var withPages=loaded.withNotes(new Notes(List.of(folder),List.of(longPage,trashed)))
                .withTasks(List.of(task.withPages(List.of(longPage.id(),trashed.id()))));
            try(var vault=new EncryptedVault(file,password.get())){vault.save(withPages);}
            try(var vault=new EncryptedVault(file,password.get())){
                var back=vault.load();
                check(back.equals(withPages),"Pages, folders and task links survive a save and reopen");
                check(back.notes().page(longPage.id()).orElseThrow().body().length()==longPage.body().length(),
                    "A page longer than 64 KB comes back whole");
            }
        } finally {
            try(var files=Files.walk(dir)){
                for(var path:files.sorted(Comparator.reverseOrder()).toList())Files.delete(path);
            }
        }
    }
}
