package dev.yoru;

import dev.yoru.domain.Model.*;
import dev.yoru.persistence.EncryptedVault;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.Base64;
import java.util.Comparator;

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
            check(loaded.game()==null,"An old vault arrives with no game save");
            check(task.tagId()==null,"The task is untagged");
            check(loaded.rewards().isEmpty(),"An empty collection brings no rewards");
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
            check(loaded.settings().trainer()==TrainerId.MAY,"Its trainer survives the withdrawn setting");
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
}
