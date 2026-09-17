package dev.yoru;

import dev.yoru.application.*;
import dev.yoru.domain.Model.*;
import dev.yoru.persistence.PortableVault;
import java.io.IOException;
import java.time.*;
import java.util.*;

/**
 * The portable vault export and import (#1).
 *
 * The point of this format is that it can be read and trusted when a vault
 * already looks wrong, so the assertions are about exactness — every field
 * survives the round trip, and a file that is wrong anywhere is refused whole
 * rather than applied in part.
 */
public final class PortableVaultTest {
    private static int checks;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}

    private interface Action {void run()throws Exception;}
    private static void rejects(Action action,String why)throws Exception{
        try{action.run();}catch(IllegalArgumentException|IOException expected){checks++;return;}
        throw new AssertionError(why);
    }
    /** Asserts the refusal names the field, not just that it refused. */
    private static void refuses(String json,String fragment,String why){
        checks++;
        try{PortableVault.parse(json);}
        catch(RuntimeException e){
            if(e.getMessage()!=null&&e.getMessage().contains(fragment))return;
            throw new AssertionError(why+" — refused, but said: "+e.getMessage());
        }
        throw new AssertionError(why);
    }

    private static final class Memory implements Repository {
        State state=State.empty();boolean fail;
        public State load(){return state;}
        public void save(State next)throws IOException{if(fail)throw new IOException("Disk full");state=next;}
        public void close(){}
    }

    /** Every field populated and none of them left at its default. */
    private static State populated() {
        var study=new Activity(UUID.randomUUID(),"Study",30);
        var japanese=new Activity(UUID.randomUUID(),"Japanese",0);
        var pending=new Reward(UUID.randomUUID(),252,5,Instant.parse("2026-09-01T08:00:00Z"),null);
        var delivered=new Reward(UUID.randomUUID(),4,7,Instant.parse("2026-09-02T08:00:00Z"),
            Instant.parse("2026-09-02T09:00:00Z"));
        var tag=new Tag(UUID.randomUUID(),"Reading",0x90D8DA);
        return new State(
            List.of(study,japanese),
            List.of(new Session(UUID.randomUUID(),study.id(),Instant.parse("2026-09-03T09:00:00Z"),
                        Instant.parse("2026-09-03T10:30:00Z")),
                    new Session(UUID.randomUUID(),japanese.id(),Instant.parse("2026-09-03T11:00:00Z"),
                        Instant.parse("2026-09-03T11:45:00Z")),
                    // A running session.
                    new Session(UUID.randomUUID(),study.id(),Instant.parse("2026-09-04T09:00:00Z"),null)),
            List.of(new ScheduleBlock(UUID.randomUUID(),study.id(),
                Instant.parse("2026-09-05T13:00:00Z"),Instant.parse("2026-09-05T14:30:00Z"))),
            List.of(new RecurringBlock(UUID.randomUUID(),study.id(),DayOfWeek.MONDAY,
                        LocalTime.of(9,0),LocalTime.of(10,30)),
                    new RecurringBlock(UUID.randomUUID(),japanese.id(),DayOfWeek.WEDNESDAY,
                        LocalTime.of(14,15),LocalTime.of(15,45))),
            List.of(new Task(UUID.randomUUID(),study.id(),tag.id(),"Read chapter 4",
                        "Worked examples\nsecond line, with \"quotes\" and 日本語",
                        LocalDate.parse("2026-09-10"),TaskStatus.DOING,"reading-list.txt",
                        Instant.parse("2026-09-01T12:00:00Z"),3),
                    new Task(UUID.randomUUID(),null,null,"Order textbook","",null,
                        TaskStatus.DONE,"",Instant.parse("2026-09-01T12:00:01Z"),7),
                    // A task planned for a different day than it is due (#25).
                    new Task(UUID.randomUUID(),study.id(),tag.id(),"MLA citation quiz","",
                        LocalDate.parse("2026-09-11"),TaskStatus.TODO,"reading-list.txt",
                        Instant.parse("2026-09-01T12:00:02Z"),9,LocalDate.parse("2026-09-10"))),
            List.of(new Habit(UUID.randomUUID(),"Evening reset",HabitKind.DAILY,"America/New_York",
                        Set.of(LocalDate.parse("2026-09-01"),LocalDate.parse("2026-09-03")),List.of()),
                    new Habit(UUID.randomUUID(),"Time since last soda",HabitKind.TIME_SINCE,"Asia/Tokyo",
                        Set.of(),List.of(Instant.parse("2026-08-01T00:00:00Z"),Instant.parse("2026-08-20T06:30:00Z")))),
            List.of(tag),
            new Settings(ThemeId.SAKURA,TrainerId.MAY,7,120,DayOfWeek.MONDAY),
            new Campaign(0x9E3779B97F4A7C15L,12,21600),
            List.of(pending,delivered),
            new GameSave(dev.yoru.game.Gen3Fixture.save(2,4),Instant.parse("2026-09-07T08:00:00Z")));
    }

    public static void main(String[] args)throws Exception{
        var original=populated();
        var when=Instant.parse("2026-09-09T19:30:00Z");
        var json=PortableVault.export(original,when);

        // Readable, and honest about what it is.
        check(json.contains("\n  \"activities\""),"The export is indented, not one line");
        check(json.contains("not encrypted"),"The export says in the file that it is not encrypted");
        check(json.contains("\"exported\": \"2026-09-09T19:30:00Z\""),"The export records when it was taken");
        check(json.contains("\"colour\": \"#90D8DA\""),"Tag colours are written as readable hex");
        check(json.contains("日本語"),"Non-ASCII text survives writing");

        // The whole vault, exactly.
        var restored=PortableVault.parse(json);
        check(restored.equals(original),"Every field survives the round trip");
        check(PortableVault.export(restored,when).equals(json),"Exporting twice produces the same file");

        // Spot checks on the fields most likely to be quietly dropped.
        check(restored.sessions().get(2).end()==null,"A running session stays running");
        check(restored.rewards().getFirst().deliveredAt()==null,"An undelivered reward stays undelivered");
        check(restored.tasks().get(1).due()==null,"A task with no due date keeps none");
        check(restored.tasks().getFirst().order()==3,"Manual order survives");
        var split=restored.tasks().stream().filter(t->t.title().equals("MLA citation quiz")).findFirst().orElseThrow();
        check(split.plannedFor().equals(LocalDate.parse("2026-09-10")),"A planned day survives the round trip");
        check(split.due().equals(LocalDate.parse("2026-09-11")),"and so does the deadline it differs from");
        check(split.workOn().equals(split.plannedFor()),"The task wants attention on the planned day");
        check(restored.tasks().getFirst().plannedFor()==null,"A task with no plan keeps none");
        check(json.contains("\"plannedFor\": \"2026-09-10\""),"The export writes the planned day readably");
        check(restored.tasks().getFirst().createdAt().equals(Instant.parse("2026-09-01T12:00:00Z")),"createdAt survives");
        check(restored.tasks().getFirst().notes().equals(original.tasks().getFirst().notes()),"Newlines and quotes in notes survive");
        check(restored.rewards().get(1).deliveredAt().equals(Instant.parse("2026-09-02T09:00:00Z")),"The delivery time survives");
        check(restored.rewards().get(1).level()==7,"The delivered level survives");
        check(restored.campaign().rewardedSeconds()==21600,"The reward ledger survives");
        check(restored.campaign().encountersUsed()==12,"Opened encounters survive");
        check(restored.campaign().seed()==original.campaign().seed(),"The campaign seed survives");
        check(restored.game().equals(original.game()),"The game save survives the round trip");
        check(restored.habits().get(1).zone().equals("Asia/Tokyo"),"A habit keeps its own timezone");
        check(restored.habits().getFirst().checkIns().size()==2,"Check-in history survives");
        check(restored.habits().get(1).starts().size()==2,"Time-since restarts survive");
        check(restored.recurring().size()==2,"The weekly template survives");
        check(restored.recurring().getFirst().dayOfWeek()==DayOfWeek.MONDAY,"A repeating block keeps its weekday");
        check(restored.recurring().getFirst().startTime().equals(LocalTime.of(9,0)),"A repeating block keeps its clock time");
        check(restored.recurring().get(1).endTime().equals(LocalTime.of(15,45)),"Minutes are not rounded away");
        check(json.contains("\"startTime\": \"09:00\""),"Clock times are written as readable HH:MM");
        check(restored.settings().theme()==ThemeId.SAKURA,"Settings survive");
        check(restored.settings().minSessionSeconds()==120,"A non-default session floor survives");
        check(!json.contains("waifu"),"The withdrawn companion choice is no longer written");
        check(restored.tasks().getFirst().tagId().equals(original.tags().getFirst().id()),"Task tagging survives");

        // An empty vault is a valid vault.
        var empty=State.empty();
        check(PortableVault.parse(PortableVault.export(empty,when)).equals(empty),"An empty vault round-trips");

        // An export written by 1.0.10 still imports: its companion choice is a
        // field this version does not know, and its theme no longer exists.
        var illustrated=json.replace("\"theme\": \"SAKURA\"","\"theme\": \"WAIFU\"")
            .replace("\"trainer\":","\"waifu\": \"nightfall\",\n    \"trainer\":");
        check(PortableVault.parse(illustrated).settings().theme()==ThemeId.MOONLIGHT,
            "An export naming the withdrawn Waifu theme imports as Moonlight");

        // Refusals name the field. This is the tool people reach for when a vault
        // already looks wrong; "Invalid JSON" would not help anyone.
        refuses(json.replace("\"yoru\": 2","\"yoru\": 99"),"format","A future format version is refused by name");
        refuses(json.replace("\"activities\"","\"activitys\""),"activities","A missing section is named");
        refuses(json.replace("\"targetMinutes\": 30","\"targetMinutes\": \"thirty\""),"targetMinutes","A wrong type is named");
        refuses(json.replace("\"name\": \"Study\"","\"name\": 5"),"name","A wrong type in a record is named");
        refuses(json.replace("\"trainer\": \"MAY\"","\"trainer\": \"NEON\""),"NEON","An unknown enum value is named");
        // Except the theme: a palette is a preference, not data, so an unknown
        // one falls back instead of refusing the whole workspace.
        check(PortableVault.parse(json.replace("\"theme\": \"SAKURA\"","\"theme\": \"NEON\"")).settings().theme()==ThemeId.MIDNIGHT,
            "An unknown theme imports as the default rather than refusing the file");
        refuses(json.replace("\"status\": \"DOING\"","\"status\": \"BLOCKED\""),"BLOCKED","An unknown task status is named");
        refuses(json.replace("\"colour\": \"#90D8DA\"","\"colour\": \"periwinkle\""),"colour","A malformed colour is named");
        refuses(json.replace("\"earnedAt\": \"2026-09-01T08:00:00Z\"","\"earnedAt\": \"the first\""),"earnedAt","A malformed instant is named");
        refuses(json.replace("\"due\": \"2026-09-10\"","\"due\": \"2026-99-99\""),"due","An impossible date is named");
        refuses(json.replace("\"level\": 7","\"level\": \"high\""),"level","A wrong number type is named");
        refuses(json.replace("\"dayOfWeek\": \"MONDAY\"","\"dayOfWeek\": \"MONDIAL\""),"MONDIAL","An unknown weekday is named");
        refuses(json.replace("\"startTime\": \"09:00\"","\"startTime\": \"nine\""),"startTime","A malformed clock time is named");
        rejects(()->PortableVault.parse("not json at all"),"Text that is not JSON is refused");
        rejects(()->PortableVault.parse("[]"),"A JSON array is not a vault");

        // A record index is reported, so a bad row in a long file can be found.
        refuses(json.replace("\"title\": \"Order textbook\"","\"title\": 5"),"tasks[1]","The failing record is identified by index");

        // Domain rules still apply through import: this is not a back door.
        rejects(()->PortableVault.parse(json.replace("\"dailyGoalHours\": 7","\"dailyGoalHours\": 99")),
            "Settings validation applies to an imported vault");
        rejects(()->PortableVault.parse(json.replace("\"nationalDex\": 4","\"nationalDex\": 9999")),
            "An unknown species is refused on import");
        rejects(()->PortableVault.parse(json.replace("\"title\": \"Read chapter 4\"","\"title\": \"\"")),
            "An empty task title is refused on import");

        // Applying is all or nothing.
        var repo=new Memory();
        var tracker=new Tracker(repo,Clock.systemUTC());
        tracker.addActivity("Existing",0);
        var before=tracker.state();
        rejects(()->PortableVault.parse(json.replace("\"nationalDex\": 4","\"nationalDex\": 9999")),"Parse fails before any write");
        check(tracker.state().equals(before),"A refused file leaves the open vault untouched");
        check(repo.state.equals(before),"A refused file writes nothing to storage");

        tracker.restore(PortableVault.parse(json));
        check(tracker.state().equals(original),"Importing replaces the whole vault");
        check(repo.state.equals(original),"The import reached storage");

        // A failed write leaves the running app on what it had, per Tracker.commit.
        var second=new Memory();
        var failing=new Tracker(second,Clock.systemUTC());
        failing.addActivity("Kept",0);
        var kept=failing.state();
        second.fail=true;
        rejects(()->failing.restore(PortableVault.parse(json)),"A failed write is reported");
        check(failing.state().equals(kept),"A failed import leaves the running vault as it was");

        System.out.println("PASS: "+checks+" portable vault checks (round trip, named refusals, all-or-nothing import)");
    }
}
