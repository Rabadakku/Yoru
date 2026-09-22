package dev.yoru;
import dev.yoru.application.*;
import dev.yoru.domain.Model.*;
import dev.yoru.persistence.EncryptedVault;
import java.time.*;
import java.nio.file.*;
import java.util.*;
import java.io.*;
public final class CoreTest {
    static int checks;
    static void check(boolean ok,String why) {
        checks++;
        if(!ok)throw new AssertionError(why);
    }
    interface Action {
        void run()throws Exception;
    }
    static void rejects(Action a,String why)throws Exception {
        boolean failed=false;
        try {
            a.run();
        }
        catch(IOException|IllegalArgumentException e) {
            failed=true;
        }
        check(failed,why);
    }
    static class Memory implements Repository {
        State s=State.empty();
        boolean fail;
        public State load() {
            return s;
        }
        public void save(State next)throws IOException {
            if(fail)throw new IOException("disk failure");
            s=next;
        }
        public void close() {
        }
    }
    public static void main(String[] args)throws Exception {
        var mem=new Memory();
        Instant now=Instant.parse("2026-09-08T15:00:00Z");
        var t=new Tracker(mem,Clock.fixed(now,ZoneOffset.UTC));
        t.addActivity("Study",30);
        UUID id=t.state().activities().getFirst().id();
        rejects(()->t.addActivity("study",0),"duplicate name");
        rejects(()->t.addActivity(" ",0),"blank");
        rejects(()->t.addActivity("Code",-1),"negative target");
        t.start(id);
        check(t.active()!=null,"clock in");
        rejects(()->t.start(id),"single session");
        check(new Tracker(mem,Clock.fixed(now,ZoneOffset.UTC)).active()!=null,"recovery");
        rejects(()->t.stop(now.minusSeconds(1)),"negative elapsed");
        rejects(()->t.stop(now.plusSeconds(1)),"future clock out");
        // Ten minutes, not one: since the minimum applies at clock-out, a
        // sixty-second session would be discarded and these assertions would be
        // testing the floor instead of the correction they are named for.
        var later=new Tracker(mem,Clock.fixed(now.plusSeconds(1200),ZoneOffset.UTC));
        check(later.stop(now.plusSeconds(600)),"clock out records a session over the minimum");
        check(later.active()==null,"clock out");
        check(Duration.between(later.state().sessions().getFirst().start(),later.state().sessions().getFirst().end()).getSeconds()==600,"corrected stop");
        rejects(()->later.log(id,now.plusSeconds(300),now.plusSeconds(900)),"overlap rejected");
        later.log(id,now.minusSeconds(600),now);
        check(later.state().sessions().size()==2,"adjacent allowed");
        mem.fail=true;
        rejects(()->later.addActivity("Code",0),"disk failure surfaced");
        check(later.state().activities().size()==1,"persist before publish");
        mem.fail=false;
        later.plan(id,now,now.plusSeconds(1200));
        rejects(()->later.plan(id,now.plusSeconds(30),now.plusSeconds(90)),"plan overlap");
        check(Analytics.adherence(later.state(),later.state().blocks().getFirst(),now.plusSeconds(1200))==0.5,"schedule overlap ratio");
        // The same sessions the totals and the grid count: a stored session
        // under the floor must not show as time matched against a block.
        var floored=new Tracker(mem,Clock.fixed(now.plusSeconds(1200),ZoneOffset.UTC));
        floored.settings(new Settings(ThemeId.MIDNIGHT,4,3600));
        check(Analytics.adherence(floored.state(),floored.state().blocks().getFirst(),now.plusSeconds(1200))==0,
            "a session under the minimum matches no block, as it counts toward no total");
        floored.settings(new Settings(ThemeId.MIDNIGHT,4,300));
        ZoneId ny=ZoneId.of("America/New_York");
        LocalDate spring=LocalDate.of(2026,3,8);
        Instant a=spring.atStartOfDay(ny).toInstant(),b=spring.plusDays(1).atStartOfDay(ny).toInstant();
        var state=new State(t.state().activities(),List.of(new Session(UUID.randomUUID(),id,a,b)),List.of());
        check(Analytics.daily(state,null,ny,now).get(spring)==23*3600L,"DST 23 hour day");
        // Zero floor so the midnight-splitting logic is tested on its own; the
        // floor itself is exercised separately below.
        var noFloor=new Settings(ThemeId.MIDNIGHT,4,0);
        var cross=new State(t.state().activities(),List.of(new Session(UUID.randomUUID(),id,a.minusSeconds(60),a.plusSeconds(60))),List.of())
            .withSettings(noFloor);
        var totals=Analytics.daily(cross,null,ny,now);
        check(totals.get(spring)==60&&totals.get(spring.minusDays(1))==60,"midnight split");
        check(Analytics.streak(totals,spring.plusDays(1))==2,"yesterday grace");
        check(Analytics.streak(totals,spring.plusDays(2))==0,"streak reset");
        check(Analytics.daily(cross,UUID.randomUUID(),ny,now).isEmpty(),"per activity isolation");

        // The floor is still a read-time rule over whatever a State holds — an
        // older vault, or a JSON import, can carry sessions under it. What
        // changed is that Tracker no longer creates them; that is asserted
        // against the tracker further down.
        var brief=new Session(UUID.randomUUID(),id,a,a.plusSeconds(120));
        var longer=new Session(UUID.randomUUID(),id,a.plusSeconds(600),a.plusSeconds(1200));
        var mixed=new State(t.state().activities(),List.of(brief,longer),List.of());
        check(mixed.sessions().size()==2,"a State can still hold a short session");
        check(Analytics.daily(mixed,null,ny,now).getOrDefault(spring,0L)==600L,"sessions under the floor do not count");
        check(Analytics.daily(mixed.withSettings(noFloor),null,ny,now).get(spring)==720L,"a zero floor counts everything");
        check(!Analytics.counts(brief,mixed,now)&&Analytics.counts(longer,mixed,now),"counts() matches the floor");
        var runningNow=new Session(UUID.randomUUID(),id,now.minusSeconds(30),null);
        check(Analytics.counts(runningNow,mixed,now),"a running session always counts");

        // Clocking out under the minimum records nothing at all.
        var floorRepo=new Memory();
        // An hour on, so the manual entries below are in the past.
        var clock=Clock.fixed(now.plusSeconds(3600),ZoneOffset.UTC);
        var floorTracker=new Tracker(floorRepo,Clock.fixed(now,ZoneOffset.UTC));
        floorTracker.addActivity("Study",0);
        UUID floorActivity=floorTracker.state().activities().getFirst().id();
        floorTracker.start(floorActivity);
        var stopping=new Tracker(floorRepo,clock);
        check(!stopping.stop(now.plusSeconds(120)),"a two-minute clock-out reports that it recorded nothing");
        check(stopping.state().sessions().isEmpty(),"and stores nothing at all");
        check(stopping.active()==null,"and leaves no timer running");

        // Deliberate entry is refused rather than silently dropped: nobody should
        // type a session and watch it disappear.
        rejects(()->stopping.log(floorActivity,now,now.plusSeconds(120)),"a short manual session is refused");
        stopping.log(floorActivity,now,now.plusSeconds(600));
        check(stopping.state().sessions().size()==1,"a long enough manual session is kept");
        UUID kept=stopping.state().sessions().getFirst().id();
        rejects(()->stopping.editSession(kept,floorActivity,now,now.plusSeconds(60)),
            "editing a session below the minimum is refused");
        check(Duration.between(stopping.state().sessions().getFirst().start(),
            stopping.state().sessions().getFirst().end()).getSeconds()==600,"the refused edit changed nothing");

        // Sessions already stored below the floor are removed only when asked.
        var legacy=stopping.state().withCore(stopping.state().activities(),
            List.of(stopping.state().sessions().getFirst(),
                new Session(UUID.randomUUID(),floorActivity,now.plusSeconds(1800),now.plusSeconds(1860))),
            stopping.state().blocks());
        floorRepo.s=legacy;
        var withLegacy=new Tracker(floorRepo,clock);
        check(withLegacy.state().sessions().size()==2,"an older vault's short session still loads");
        check(withLegacy.purgeShortSessions()==1,"purging removes exactly the short ones");
        check(withLegacy.state().sessions().size()==1,"and leaves the rest");
        check(withLegacy.purgeShortSessions()==0,"purging again removes nothing");

        // Heat map tiers scale to the user's own daily goal.
        check(Analytics.heat(0,4)==0,"no time is the empty tier");
        check(Analytics.heat(4*3600,4)==Analytics.HEAT_OVER_GOAL,"meeting the goal reaches the top tier");
        check(Analytics.heat(9*3600,4)==Analytics.HEAT_OVER_GOAL,"beating the goal stays at the top tier");
        check(Analytics.heat(2*3600,4)==3&&Analytics.heat(2*3600,8)==2,"the same time reads lower against a bigger goal");
        check(Analytics.heat(1,4)==1,"any recorded time clears the empty tier");
        Path dir=Files.createTempDirectory("yoru-test-"),file=dir.resolve("test.vault");
        String password="test-only-password-123";
        byte[] first;
        try(var v=new EncryptedVault(file,password.toCharArray())) {
            v.save(later.state());
            first=Files.readAllBytes(file);
            v.save(later.state());
            check(!Arrays.equals(first,Files.readAllBytes(file)),"fresh nonce");
            rejects(()-> {
                try(var duplicate=new EncryptedVault(file,password.toCharArray())) {
                }
            }
            ,"exclusive lock");
        }
        try(var v=new EncryptedVault(file,password.toCharArray())) {
            check(v.load().equals(later.state()),"encrypted round trip");
        }
        byte[] before=Files.readAllBytes(file);
        rejects(()-> {
            try(var v=new EncryptedVault(file,"wrong-password-long".toCharArray())) {
            }
        }
        ,"wrong password");
        check(Arrays.equals(before,Files.readAllBytes(file)),"wrong password preserves file");
        before[before.length-1]^=1;
        Files.write(file,before);
        rejects(()-> {
            try(var v=new EncryptedVault(file,password.toCharArray())) {
            }
        }
        ,"tampering rejected");
        check(dev.yoru.ui.YoruApp.csv("=1+1").equals("\"'=1+1\""),"CSV formula neutralized");
        check(dev.yoru.ui.YoruApp.csv("a\"b").equals("\"a\"\"b\""),"CSV quote escaped");
        Files.delete(file);
        Files.delete(Path.of(file+".lock"));
        Files.delete(dir);
        System.out.println("PASS: "+checks+" checks (timers, recovery, validation, DST, analytics, atomic failure, vault authentication, exports)");
    }
}
