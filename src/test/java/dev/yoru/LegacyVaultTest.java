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
            // Schema 12's waifu choice did not exist when this vault was
            // written, so it arrives as "no panel", never as a reset or a failure.
            check(loaded.settings().waifu()==null,"An older vault arrives with no waifu choice");

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
        System.out.println("PASS: "+checks+" legacy vault checks (an older build's file still opens)");
    }
}
