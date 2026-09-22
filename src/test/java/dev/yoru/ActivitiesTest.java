package dev.yoru;

import dev.yoru.application.*;
import dev.yoru.domain.Model;
import dev.yoru.domain.Model.*;
import dev.yoru.persistence.EncryptedVault;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/**
 * Managing study activities (#38): renaming a label, and removing an activity
 * either by keeping its recorded time under Uncategorized or by deleting it too.
 *
 * Invented data only. Everything here turns on one property: an activity is its
 * id, never its label. So the checks compare the records that survive by id and
 * compare the reward ledger by equality rather than reading totals back, and one
 * case gives two activities the same label to prove the label is never consulted.
 */
public final class ActivitiesTest {
    private static int checks;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    interface Action {void run()throws Exception;}
    private static void rejects(Action action,String why)throws Exception{
        try{action.run();}catch(IllegalArgumentException|IOException expected){checks++;return;}
        throw new AssertionError(why);
    }
    private static final class Memory implements Repository {
        State state=State.empty();boolean fail,backupFail;int backups;
        public State load(){return state;}
        public void save(State next)throws IOException{if(fail)throw new IOException("Disk full");state=next;}
        public void backup()throws IOException{if(backupFail)throw new IOException("Backup failed");backups++;}
        public void close(){}
    }

    private static final Clock CLOCK=Clock.fixed(Instant.parse("2026-09-09T12:00:00Z"),ZoneOffset.UTC);
    private static final Instant NOW=Instant.parse("2026-09-09T12:00:00Z");
    private static final UUID CODING=UUID.nameUUIDFromBytes(new byte[]{1,2,3});
    private static final UUID JAPANESE=UUID.nameUUIDFromBytes(new byte[]{4,5,6});
    private static final UUID STUDY=UUID.nameUUIDFromBytes(new byte[]{7,8,9});
    private static final UUID TAG=UUID.nameUUIDFromBytes(new byte[]{10});
    private static final UUID EARNED=UUID.nameUUIDFromBytes(new byte[]{11});
    private static final UUID DELIVERED=UUID.nameUUIDFromBytes(new byte[]{12});

    private static Activity activity(State state,UUID id) {
        return state.activities().stream().filter(a->a.id().equals(id)).findFirst().orElse(null);
    }
    private static Activity bucket(State state) {
        var found=state.activities().stream().filter(a->a.name().equalsIgnoreCase(Model.UNCATEGORIZED)).toList();
        check(found.size()==1,"exactly one "+Model.UNCATEGORIZED+" bucket");
        return found.getFirst();
    }
    private static long total(State state) {
        return Analytics.daily(state,null,ZoneOffset.UTC,NOW).values().stream().mapToLong(Long::longValue).sum();
    }
    /** The whole vault, so a change to any part of it is visible. */
    private static State rebuild(State state,List<Activity> activities,List<Session> sessions,
                                 List<ScheduleBlock> blocks,List<RecurringBlock> recurring,List<Task> tasks) {
        return new State(activities,sessions,blocks,recurring,tasks,state.habits(),state.tags(),
            state.settings(),state.notes());
    }

    /** Three activities and one of everything that can point at them. */
    private static State seed() {
        var activities=List.of(
            new Activity(CODING,"Coding",30),
            new Activity(JAPANESE,"Japanese",0),
            new Activity(STUDY,"Study",45));
        var sessions=List.of(
            new Session(UUID.nameUUIDFromBytes(new byte[]{21}),CODING,Instant.parse("2026-09-07T09:00:00Z"),Instant.parse("2026-09-07T10:00:00Z")),
            new Session(UUID.nameUUIDFromBytes(new byte[]{22}),CODING,Instant.parse("2026-09-08T09:00:00Z"),Instant.parse("2026-09-08T09:30:00Z")),
            new Session(UUID.nameUUIDFromBytes(new byte[]{23}),JAPANESE,Instant.parse("2026-09-07T11:00:00Z"),Instant.parse("2026-09-07T11:30:00Z")),
            new Session(UUID.nameUUIDFromBytes(new byte[]{24}),STUDY,Instant.parse("2026-09-08T14:00:00Z"),Instant.parse("2026-09-08T15:00:00Z")));
        var blocks=List.of(
            new ScheduleBlock(UUID.nameUUIDFromBytes(new byte[]{31}),CODING,Instant.parse("2026-09-10T09:00:00Z"),Instant.parse("2026-09-10T10:00:00Z")),
            new ScheduleBlock(UUID.nameUUIDFromBytes(new byte[]{32}),JAPANESE,Instant.parse("2026-09-11T11:00:00Z"),Instant.parse("2026-09-11T12:00:00Z")));
        var recurring=List.of(
            new RecurringBlock(UUID.nameUUIDFromBytes(new byte[]{41}),CODING,DayOfWeek.MONDAY,LocalTime.of(9,0),LocalTime.of(10,0)),
            new RecurringBlock(UUID.nameUUIDFromBytes(new byte[]{42}),JAPANESE,DayOfWeek.TUESDAY,LocalTime.of(11,0),LocalTime.of(12,0)));
        var tasks=List.of(
            new Task(UUID.nameUUIDFromBytes(new byte[]{51}),CODING,TAG,"Read chapter 2","Check the figures",LocalDate.of(2026,9,12),TaskStatus.TODO,"syllabus.pdf",NOW,0,LocalDate.of(2026,9,11)),
            new Task(UUID.nameUUIDFromBytes(new byte[]{52}),JAPANESE,null,"Vocabulary drill","",LocalDate.of(2026,9,13),TaskStatus.DOING,"manual",NOW,1,null),
            new Task(UUID.nameUUIDFromBytes(new byte[]{53}),null,null,"Order textbook","",null,TaskStatus.DONE,"manual",NOW,2,null));
        var habits=List.of(new Habit(UUID.nameUUIDFromBytes(new byte[]{61}),"Daily reading",HabitKind.DAILY,"UTC",Set.of(LocalDate.of(2026,9,8)),List.of()));
        var tags=List.of(new Tag(TAG,"Class",0x3366CC));
        return new State(activities,sessions,blocks,recurring,tasks,habits,tags,Settings.defaults(),Notes.empty());
    }

    private static Tracker tracker(Memory repo)throws IOException {
        repo.state=seed();
        return new Tracker(repo,CLOCK);
    }

    /** A rename changes the label and nothing else, because the id is the identity. */
    private static void renameKeepsEveryRecord()throws Exception {
        var repo=new Memory();var t=tracker(repo);
        var before=t.state();
        t.renameActivity(CODING,"Programming");
        var after=t.state();
        check(after.activities().size()==before.activities().size(),"a rename adds and removes no activity");
        check("Programming".equals(activity(after,CODING).name()),"the label changed");
        check(activity(after,CODING).targetMinutes()==30,"the target survives the rename");
        check(activity(after,JAPANESE).equals(activity(before,JAPANESE)),"another activity is untouched");
        check(after.sessions().equals(before.sessions()),"every session still points at the same activity");
        check(after.blocks().equals(before.blocks()),"planned blocks are untouched");
        check(after.recurring().equals(before.recurring()),"weekly repeats are untouched");
        check(after.tasks().equals(before.tasks()),"tasks are untouched");

        check(after.habits().equals(before.habits())&&after.tags().equals(before.tags())
            &&after.settings().equals(before.settings()),
            "nothing else in the vault moved");
        check(t.usage(CODING).equals(new Tracker.ActivityUsage(2,5400L,1,1,1)),
            "the reported usage is unchanged by a rename, got "+t.usage(CODING));
        check(total(after)==total(before),"the recorded totals are unchanged by a rename");
        check(repo.backups==1,"the vault was backed up before the rename");

        int backups=repo.backups;
        t.renameActivity(CODING,"Programming");
        check(t.state().equals(after)&&repo.backups==backups,"renaming to the label it already has writes nothing");
        t.renameActivity(CODING,"coding");
        check("coding".equals(activity(t.state(),CODING).name()),"a change of case is a real rename");
        t.renameActivity(CODING,"  Coding  ");
        check("Coding".equals(activity(t.state(),CODING).name()),"the label is stripped like every other name");
        var settled=t.state();
        rejects(()->t.renameActivity(CODING,"Japanese"),"another activity already has that name");
        rejects(()->t.renameActivity(CODING,"   "),"a blank label is refused");
        rejects(()->t.renameActivity(UUID.randomUUID(),"Anything"),"an unknown activity is refused");
        check(t.state().equals(settled),"a refused rename changes nothing");
    }

    /** Two activities can share a label in an existing vault; only the id decides. */
    private static void renameIsByIdentityNotLabel()throws Exception {
        var twin=UUID.nameUUIDFromBytes(new byte[]{20});
        var twinSession=UUID.nameUUIDFromBytes(new byte[]{25});
        var repo=new Memory();repo.state=seed();
        var state=repo.state;
        var activities=new ArrayList<>(state.activities());
        activities.add(new Activity(twin,"Coding",0));
        var sessions=new ArrayList<>(state.sessions());
        sessions.add(new Session(twinSession,twin,NOW.minusSeconds(7200),NOW.minusSeconds(3600)));
        repo.state=rebuild(state,activities,sessions,state.blocks(),state.recurring(),state.tasks());
        var t=new Tracker(repo,CLOCK);
        var before=t.state();
        t.renameActivity(twin,"Coding at night");
        var after=t.state();
        check("Coding".equals(activity(after,CODING).name()),"the activity that shares the old label keeps it");
        check("Coding at night".equals(activity(after,twin).name()),"the id asked for is the one renamed");
        check(after.sessions().equals(before.sessions()),"both activities keep their own sessions");
        check(after.sessions().stream().filter(s->s.activityId().equals(twin)).count()==1,
            "the twin's session is still the twin's");
    }

    /** Removing with keep-time moves exactly the removed activity's records. */
    private static void removeKeepingTimeMovesExactlyThoseSessions()throws Exception {
        var repo=new Memory();var t=tracker(repo);
        var before=t.state();
        var usage=t.usage(CODING);
        check(usage.sessions()==2&&usage.seconds()==5400,"the confirmation counts sessions and their time");
        check(usage.blocks()==1&&usage.repeats()==1&&usage.tasks()==1,"and the plans that will be kept");
        t.removeActivity(CODING,true);
        var after=t.state();
        check(after.activities().size()==before.activities().size(),"the activity became the bucket, it was not added");
        check(activity(after,CODING)==null,"the removed activity is gone");
        var keep=bucket(after).id();
        check(after.sessions().size()==before.sessions().size(),"remove-keep loses no session");
        check(after.sessions().equals(before.sessions().stream()
            .map(s->s.activityId().equals(CODING)?new Session(s.id(),keep,s.start(),s.end()):s).toList()),
            "exactly the removed activity's sessions moved to the bucket");
        check(after.sessions().stream().noneMatch(s->s.activityId().equals(CODING)),"nothing still points at the removed activity");
        check(after.blocks().equals(before.blocks().stream()
            .map(b->b.activityId().equals(CODING)?new ScheduleBlock(b.id(),keep,b.start(),b.end()):b).toList()),
            "every planned block survives under the bucket");
        check(after.recurring().equals(before.recurring().stream()
            .map(r->r.activityId().equals(CODING)?new RecurringBlock(r.id(),keep,r.dayOfWeek(),r.startTime(),r.endTime()):r).toList()),
            "every weekly repeat survives under the bucket");
        check(after.tasks().equals(before.tasks().stream()
            .map(x->CODING.equals(x.activityId())?new Task(x.id(),keep,x.tagId(),x.title(),x.notes(),x.due(),
                x.status(),x.source(),x.createdAt(),x.order(),x.plannedFor()):x).toList()),
            "every task survives under the bucket");
        check(after.habits().equals(before.habits())&&after.tags().equals(before.tags())
            &&after.settings().equals(before.settings()),
            "no unrelated record was touched");
        check(total(after)==total(before),"keeping the time keeps every total");

        check(repo.backups==1,"the vault was backed up before the removal");

        t.removeActivity(JAPANESE,true);
        check(t.state().activities().size()==2,"a second removal reuses the bucket rather than making another");
        check(bucket(t.state()).id().equals(keep),"the same bucket is used again");
        check(t.state().sessions().stream().filter(s->s.activityId().equals(keep)).count()==3,
            "both activities' sessions are under the one bucket");
        check(t.state().sessions().stream().filter(s->s.activityId().equals(STUDY)).count()==1,
            "the activity nobody removed keeps its session");
    }

    /** Removing with delete-time removes exactly the removed activity's sessions. */
    private static void removeDeletingTimeLandsExactlyTheSessions()throws Exception {
        var repo=new Memory();var t=tracker(repo);
        var before=t.state();
        long beforeTotal=total(before);
        t.removeActivity(CODING,false);
        var after=t.state();
        check(after.sessions().equals(before.sessions().stream().filter(s->!s.activityId().equals(CODING)).toList()),
            "exactly the removed activity's sessions are deleted");
        check(after.sessions().size()==before.sessions().size()-2,"two sessions went, no others");
        check(after.sessions().stream().anyMatch(s->s.id().equals(UUID.nameUUIDFromBytes(new byte[]{23}))),
            "another activity's session is still there, unchanged");
        check(after.sessions().stream().anyMatch(s->s.id().equals(UUID.nameUUIDFromBytes(new byte[]{24}))),
            "and so is the third activity's session");
        var keep=bucket(after).id();
        check(after.blocks().equals(before.blocks().stream()
            .map(b->b.activityId().equals(CODING)?new ScheduleBlock(b.id(),keep,b.start(),b.end()):b).toList()),
            "deleting the time keeps the planned block");
        check(after.recurring().size()==before.recurring().size()&&after.tasks().equals(before.tasks().stream()
            .map(x->CODING.equals(x.activityId())?new Task(x.id(),keep,x.tagId(),x.title(),x.notes(),x.due(),
                x.status(),x.source(),x.createdAt(),x.order(),x.plannedFor()):x).toList()),
            "and keeps the repeats and tasks");

        check(total(after)==beforeTotal-5400,"the deleted time is gone from the totals");
        check(after.habits().equals(before.habits())&&after.tags().equals(before.tags())
            &&after.settings().equals(before.settings()),
            "nothing unrelated was lost");
    }

    /** The activity being timed cannot be removed, and a refused removal leaves no trace. */
    private static void refusesWhileItsTimerRuns()throws Exception {
        var repo=new Memory();var t=tracker(repo);
        t.start(CODING);
        var running=t.state();
        rejects(()->t.removeActivity(CODING,true),"remove-keep is refused while the activity is timing");
        rejects(()->t.removeActivity(CODING,false),"remove-delete is refused while the activity is timing");
        check(t.state().equals(running),"a refused removal leaves the vault exactly as it was");
        check(repo.backups==0,"and takes no backup");
        check(t.active()!=null&&t.active().activityId().equals(CODING),"the timer keeps running");
        t.removeActivity(JAPANESE,false);
        check(t.active()!=null&&t.active().activityId().equals(CODING),"removing another activity leaves the timer alone");
        check(t.state().sessions().stream().anyMatch(s->s.end()==null&&s.activityId().equals(CODING)),
            "the running session survives");
    }

    /** A failure anywhere leaves the vault as it was, and the same edit lands once it can be written. */
    private static void rollbackLeavesTheVaultExactlyAsItWas()throws Exception {
        var repo=new Memory();var t=tracker(repo);
        var before=t.state();
        repo.fail=true;
        rejects(()->t.renameActivity(CODING,"Programming"),"a failed rename is surfaced");
        check(t.state().equals(before),"a failed rename changes nothing");
        rejects(()->t.removeActivity(CODING,true),"a failed removal is surfaced");
        check(t.state().equals(before),"a failed removal changes nothing");
        repo.fail=false;
        repo.backupFail=true;
        int backedUp=repo.backups;
        rejects(()->t.renameActivity(CODING,"Programming"),"a rename is blocked when the backup cannot be written");
        rejects(()->t.removeActivity(CODING,true),"a removal is blocked when the backup cannot be written");
        check(t.state().equals(before),"a blocked backup changes nothing");
        check(repo.backups==backedUp,"and nothing was counted as backed up");
        repo.backupFail=false;
        t.renameActivity(CODING,"Programming");
        check("Programming".equals(activity(t.state(),CODING).name()),"the rename lands once the vault is writable");
        t.removeActivity(CODING,false);
        check(activity(t.state(),CODING)==null&&t.state().sessions().size()==2,"and so does the removal");
    }

    /** The bucket is an ordinary activity, but records cannot be removed out from under it. */
    private static void theBucketItselfIsProtected()throws Exception {
        var named=UUID.nameUUIDFromBytes(new byte[]{30});
        var blockId=UUID.nameUUIDFromBytes(new byte[]{33});
        var repo=new Memory();repo.state=seed();
        var state=repo.state;
        var activities=new ArrayList<>(state.activities());
        activities.add(new Activity(named,Model.UNCATEGORIZED,0));
        var sessions=new ArrayList<>(state.sessions());
        sessions.add(new Session(UUID.nameUUIDFromBytes(new byte[]{26}),named,NOW.minusSeconds(1800),NOW.minusSeconds(600)));
        var blocks=new ArrayList<>(state.blocks());
        blocks.add(new ScheduleBlock(blockId,named,NOW.plusSeconds(86400),NOW.plusSeconds(90000)));
        repo.state=rebuild(state,activities,sessions,blocks,state.recurring(),state.tasks());
        var t=new Tracker(repo,CLOCK);
        rejects(()->t.removeActivity(named,true),"the bucket cannot keep its own time");
        rejects(()->t.removeActivity(named,false),"and cannot be removed while a planned block points at it");
        t.deleteBlock(blockId);
        rejects(()->t.removeActivity(named,true),"a session that needs keeping still has nowhere to go");
        t.removeActivity(named,false);
        check(activity(t.state(),named)==null,"with nothing left to move, the bucket can go");
        check(t.state().sessions().size()==4,"and its session went with it");
    }

    /** A rename survives an encrypted reopen with every session still attached. */
    private static void survivesAnEncryptedReopen()throws Exception {
        Path dir=Files.createTempDirectory("yoru-activities-"),file=dir.resolve("test.vault");
        String password="test-only-password-456";
        try {
            try(var vault=new EncryptedVault(file,password.toCharArray())) {
                var t=new Tracker(vault,CLOCK);
                t.restore(seed());
                t.renameActivity(CODING,"Programming");
                t.removeActivity(JAPANESE,true);
            }
            try(var vault=new EncryptedVault(file,password.toCharArray())) {
                var state=vault.load();
                check("Programming".equals(activity(state,CODING).name()),"the new label survives the reopen");
                check(state.sessions().size()==4&&state.sessions().stream().filter(s->s.activityId().equals(CODING)).count()==2,
                    "every session survived the reopen, still on the renamed activity");
                check(state.sessions().stream().noneMatch(s->s.activityId().equals(JAPANESE)),"the removed activity is gone");
            }
        } finally {
            try(var files=Files.walk(dir)){for(var p:files.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}
        }
    }

    public static void main(String[] args)throws Exception {
        renameKeepsEveryRecord();
        renameIsByIdentityNotLabel();
        removeKeepingTimeMovesExactlyThoseSessions();
        removeDeletingTimeLandsExactlyTheSessions();
        refusesWhileItsTimerRuns();
        rollbackLeavesTheVaultExactlyAsItWas();
        theBucketItselfIsProtected();
        survivesAnEncryptedReopen();
        System.out.println("PASS: "+checks+" activity checks (rename by identity, remove keeping or deleting time, "
            +"refusal while timing, rollback on failure)");
    }
}
