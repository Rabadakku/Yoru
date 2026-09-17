package dev.yoru;

import dev.yoru.application.*;
import dev.yoru.domain.Model.*;
import dev.yoru.persistence.EncryptedVault;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/**
 * Schema 12 round trip: tags, task status/order, the campaign counters, the
 * reward ledger, the game save and settings must survive a save and reopen
 * exactly.
 *
 * Written because every one of those fields was appended to a positional binary
 * format, where a single misplaced read shifts everything after it and produces
 * plausible-looking garbage rather than an error.
 */
public final class SchemaTest {
    private static int checks;
    private static void check(boolean ok,String why) { checks++; if(!ok) throw new AssertionError(why); }

    private static final class Memory implements Repository {
        State state=State.empty();
        public State load() { return state; }
        public void save(State next) { state=next; }
        public void close() { }
    }

    public static void main(String[] args) throws Exception {
        var now=Instant.parse("2026-09-09T14:00:00Z");
        var repo=new Memory();
        var t=new Tracker(repo,Clock.fixed(now,ZoneOffset.UTC));

        t.addActivity("Class",0);
        var activity=t.state().activities().getFirst().id();
        var tag=t.addTag("Reading",0x90D8DA);
        check(t.state().tags().size()==1&&t.state().tags().getFirst().colour()==0x90D8DA,"tag stored with colour");

        t.addTasks(List.of(
            new Task(UUID.randomUUID(),activity,tag.id(),"Read chapter 4","notes",LocalDate.of(2026,9,12),
                TaskStatus.DOING,"reading-list.txt",now.minusSeconds(3600),2),
            new Task(UUID.randomUUID(),activity,null,"Lab report","",null,TaskStatus.TODO,"",now,0)));
        check(t.state().tasks().size()==2,"tasks added");

        // The game side: study-earned rewards and the game's own save.
        t.log(activity,now.minusSeconds(20000),now.minusSeconds(14600));
        var pending=t.bankReward(UUID.randomUUID(),252,5);
        var delivered=t.bankReward(UUID.randomUUID(),255,6);
        t.rewardDelivered(delivered.id(),now.minusSeconds(1000));
        t.gameSaved(dev.yoru.game.Gen3Fixture.save(2,4));
        check(t.state().pendingRewards().equals(List.of(pending)),"one of the two is still pending");
        check(t.state().campaign().encountersUsed()==0,"no encounter opened yet");
        check(t.state().game()!=null,"the game save is kept in the vault");

        t.settings(new Settings(ThemeId.SAKURA,TrainerId.MAY,6,120,DayOfWeek.MONDAY));

        var saved=t.state();
        check(saved.settings().theme()==ThemeId.SAKURA,"settings applied");

        Path dir=Files.createTempDirectory("yoru-schema-");
        Path vault=dir.resolve("v5.vault");
        String password="schema-test-password";
        try(var v=new EncryptedVault(vault,password.toCharArray())) { v.save(saved); }
        try(var v=new EncryptedVault(vault,password.toCharArray())) {
            var loaded=v.load();
            check(loaded.equals(saved),"full schema 13 state round trips");
            check(loaded.settings().trainer()==TrainerId.MAY,"trainer choice persists");
            check(loaded.settings().dailyGoalHours()==6,"daily goal persists");
            check(loaded.settings().minSessionSeconds()==120,"minimum session persists");
            check(loaded.tags().getFirst().name().equals("Reading"),"tag persists");
            check(loaded.tasks().stream().anyMatch(x->x.status()==TaskStatus.DOING),"task status persists");
            check(loaded.tasks().stream().anyMatch(x->tag.id().equals(x.tagId())),"task tag persists");
            check(loaded.tasks().stream().anyMatch(x->x.order()==2),"task order persists");
            check(loaded.campaign().equals(saved.campaign()),"the campaign counters persist");
            check(loaded.rewards().equals(saved.rewards()),"both rewards persist");
            check(loaded.pendingRewards().size()==1,"with the same one still pending");
            check(loaded.game().equals(saved.game()),"the game save persists");
        }

        // Deleting a tag must not delete the work that carried it.
        var reopened=new Tracker(repo,Clock.fixed(now,ZoneOffset.UTC));
        int before=reopened.state().tasks().size();
        reopened.deleteTag(tag.id());
        check(reopened.state().tasks().size()==before,"deleting a tag keeps its tasks");
        check(reopened.state().tasks().stream().allMatch(x->x.tagId()==null),"deleting a tag untags its tasks");
        check(reopened.state().tags().isEmpty(),"tag removed");

        // A reset of one section must not quietly clear the others.
        reopened.settings(new Settings(ThemeId.LINEN,TrainerId.MAY,5,60,DayOfWeek.MONDAY));
        reopened.addTag("Japanese",0xD8B074);
        reopened.reset(EnumSet.of(Tracker.ResetPart.TASKS));
        check(reopened.state().tasks().isEmpty(),"tasks reset");
        check(reopened.state().settings().theme()==ThemeId.LINEN,"reset keeps settings it was not asked to clear");
        check(reopened.state().rewards().size()==2,"reset keeps the rewards");
        check(reopened.state().game()!=null,"reset keeps the game save");

        reopened.reset(EnumSet.of(Tracker.ResetPart.SETTINGS));
        check(reopened.state().settings().theme()==ThemeId.MIDNIGHT,"settings reset returns defaults");

        try(var walk=Files.walk(dir)) {
            for(var p:walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
        System.out.println("PASS: "+checks+" schema 12 checks (tags, status, campaign, rewards, game save, settings, reset scoping)");
    }
}
