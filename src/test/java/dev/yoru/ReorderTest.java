package dev.yoru;

import dev.yoru.application.*;
import dev.yoru.domain.Model.*;
import dev.yoru.persistence.EncryptedVault;
import dev.yoru.persistence.PortableVault;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/**
 * Activities and tags keep the order the owner gives them (#59).
 *
 * Only the order changes: sessions, blocks and tasks point at an activity or
 * tag by id, never by its place, so moving one touches no other record.
 * Invented data only.
 */
public final class ReorderTest {
    private static int checks;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    private interface Action{void run()throws Exception;}
    private static void refuses(Action action)throws Exception{try{action.run();throw new AssertionError("Expected refusal");}catch(IllegalArgumentException|IOException expected){checks++;}}
    private static class Memory implements Repository{
        State state=State.empty();boolean failSave,failBackup;int saves,backups;
        public State load(){return state;}
        public void save(State next)throws IOException{if(failSave)throw new IOException("Synthetic save failure");state=next;saves++;}
        public void backup()throws IOException{if(failBackup)throw new IOException("Synthetic backup failure");backups++;}
        public void close(){}
    }
    private static List<UUID> activities(Tracker t){return t.state().activities().stream().map(Activity::id).toList();}
    private static List<UUID> tags(Tracker t){return t.state().tags().stream().map(Tag::id).toList();}

    public static void main(String[] args)throws Exception{
        var now=Instant.parse("2026-09-24T12:00:00Z");
        var repo=new Memory();
        var t=new Tracker(repo,Clock.fixed(now,ZoneOffset.UTC));
        t.addActivity("Reading",30);t.addActivity("Drawing",0);t.addActivity("Running",20);
        var a=activities(t);
        t.log(a.get(1),now.minusSeconds(7200),now.minusSeconds(3600));
        var first=t.addTag("Morning",0x90D8DA);var second=t.addTag("Errand",0xE8B24C);var third=t.addTag("Quiet",0xD9736A);
        var g=tags(t);

        // Activities.
        int saves=repo.saves,backups=repo.backups;
        t.moveActivity(a.getFirst(),-1);t.moveActivity(a.getLast(),1);
        check(repo.saves==saves&&repo.backups==backups,"Moving the first up or the last down writes nothing");
        var drawing=t.state().activities().get(1);var session=t.state().sessions().getFirst();
        t.moveActivity(a.get(1),-1);
        check(activities(t).equals(List.of(a.get(1),a.get(0),a.get(2))),"An activity moves up past its neighbour");
        check(repo.backups==backups+1,"A reorder is backed up first, like the habit order");
        check(t.state().activities().getFirst().equals(drawing),"Moving keeps the name and target");
        check(t.state().sessions().getFirst().equals(session),"Sessions still point at the same activity");
        t.moveActivity(a.get(1),1);t.moveActivity(a.get(1),1);
        check(activities(t).equals(List.of(a.get(0),a.get(2),a.get(1))),"And down past each neighbour to the end");

        // Tags.
        t.moveTag(first.id(),-1);t.moveTag(third.id(),1);
        check(tags(t).equals(g),"Moving the first tag up or the last down changes nothing");
        t.moveTag(third.id(),-1);
        check(tags(t).equals(List.of(first.id(),third.id(),second.id())),"A tag moves up past its neighbour");
        check(t.state().tags().get(1).equals(third),"Moving keeps the tag's name and colour");

        // Refusals leave everything as it was.
        var before=t.state();
        refuses(()->t.moveActivity(UUID.randomUUID(),1));refuses(()->t.moveActivity(a.getFirst(),0));refuses(()->t.moveActivity(a.getFirst(),2));
        refuses(()->t.moveTag(UUID.randomUUID(),-1));refuses(()->t.moveTag(first.id(),0));
        check(t.state().equals(before),"Stale ids and anything but one step up or down are refused");
        repo.failSave=true;refuses(()->t.moveActivity(a.get(2),-1));refuses(()->t.moveTag(first.id(),1));
        check(t.state().equals(before),"A failed save keeps the old order");repo.failSave=false;
        repo.failBackup=true;refuses(()->t.moveActivity(a.get(2),-1));refuses(()->t.moveTag(first.id(),1));
        check(t.state().equals(before)&&repo.state.equals(before),"A failed backup writes nothing");repo.failBackup=false;

        // The order is the vault's, in both formats.
        var saved=t.state();
        check(PortableVault.parse(PortableVault.export(saved,now)).equals(saved),"Order survives the portable export");
        var dir=Files.createTempDirectory("yoru-reorder");
        try{
            var file=dir.resolve("synthetic.vault");
            try(var vault=new EncryptedVault(file,"fixture-password".toCharArray())){vault.save(saved);}
            try(var vault=new EncryptedVault(file,"fixture-password".toCharArray())){check(vault.load().equals(saved),"Order survives reopening the encrypted vault");}
        }finally{try(var files=Files.walk(dir)){for(var file:files.sorted(Comparator.reverseOrder()).toList())Files.delete(file);}}
        System.out.println("PASS: "+checks+" reorder checks (activities and tags, ends, refusals, failures, both vault formats)");
    }
}
