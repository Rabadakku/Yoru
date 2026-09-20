package dev.yoru.importer;

import dev.yoru.application.Repository;
import dev.yoru.application.Tracker;
import dev.yoru.domain.Model.*;
import java.io.IOException;
import java.time.*;
import java.util.*;
import java.util.zip.*;

/**
 * The Notion importer against an invented export of the same shape.
 *
 * Everything below is built from {@link NotionFixture}: no real export, class
 * name or task title is ever involved. The checks cover the four promises the
 * feature makes — the usual columns map themselves, the mapping can be corrected
 * before anything is written, page text and classes arrive as notes and tags,
 * and the whole import is one write that cannot half-happen.
 */
public final class NotionTest {
    private static int checks;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}

    private interface Action { void run() throws Exception; }
    private static void refuses(Action action,String expected)throws Exception {
        try { action.run(); throw new AssertionError("Accepted what should be refused: "+expected); }
        catch(IOException e) { check(e.getMessage().contains(expected),"Helpful refusal: "+e.getMessage()); }
    }

    private static final class Memory implements Repository {
        State state=State.empty();boolean fail;int writes;
        public State load(){return state;}
        public void save(State state)throws IOException {if(fail)throw new IOException("disk full");this.state=state;writes++;}
        public void close(){}
    }

    public static void main(String[] args)throws Exception {
        var sheet=NotionImport.read(NotionFixture.zip(),"Study Tasks.zip");
        check(sheet.headers().equals(List.of("Name","Status","Class","Due")),"The CSV's own headers are read from inside the zip");
        check(sheet.rows().size()==5,"Five rows, with the trailing blank line ignored");
        check(sheet.source().equals("Study Tasks.csv"),"The sheet is named for the CSV it came from");
        check(sheet.rows().getFirst().cell("Name").equals("Read the first chapter"),"A cell reads back by header");
        check(sheet.rows().get(3).cell("Class").equals("Biology, Mathematics"),"A quoted cell keeps its comma");
        check(sheet.rows().get(4).cell("Due").isEmpty(),"An empty cell reads as empty");

        var direct=NotionImport.read(NotionFixture.csv(),"Study Tasks.csv");
        check(direct.headers().equals(sheet.headers())&&direct.rows().size()==5,"The CSV on its own reads the same as the zip");

        var mapping=NotionImport.autoMap(sheet.headers());
        check(mapping.title().equals("Name")&&mapping.status().equals("Status")
            &&mapping.due().equals("Due")&&mapping.tags().equals("Class"),"The usual columns map themselves");
        var boxes=NotionImport.autoMap(List.of("Name","Done","Class","Due"));
        check(boxes.status().equals("Done"),"A checkbox property is recognised as the status");
        var sparse=NotionImport.autoMap(List.of("Task","Completed","Subject","When"));
        check(sparse.title().equals("Task")&&sparse.status().equals("Completed")
            &&sparse.due().equals("When")&&sparse.tags().equals("Subject"),"Unusual names still map by their words");
        var nothing=NotionImport.autoMap(List.of("Alpha","Beta"));
        check(nothing.equals(new NotionImport.Mapping(null,null,null,null)),"Unknown columns map to nothing rather than guessing");

        var candidates=NotionImport.preview(sheet,mapping);
        check(candidates.size()==5,"Every titled row becomes a candidate");
        check(candidates.getFirst().title().equals("Read the first chapter"),"The title column supplies the title");
        check(candidates.getFirst().due().equals(LocalDate.of(2026,9,12)),"A written-out date is read");
        check(candidates.get(2).due().equals(LocalDate.of(2026,9,20)),"An ISO date is read");
        check(candidates.get(3).due().equals(LocalDate.of(2026,9,22)),"A slash date is read");
        check(candidates.get(4).due()==null,"A blank deadline stays unknown");
        check(candidates.get(0).status()==TaskStatus.TODO&&candidates.get(1).status()==TaskStatus.DOING
            &&candidates.get(2).status()==TaskStatus.DONE,"Status words become Yoru statuses");
        check(candidates.getFirst().tags().equals(List.of("Biology")),"A class becomes a tag");
        check(candidates.get(3).tags().equals(List.of("Biology","Mathematics")),"A multi-value class becomes several tags");
        check(candidates.getFirst().notes().contains("Read pages 1-30"),"Page text becomes notes");
        check(!candidates.getFirst().notes().contains("# Read the first chapter"),"The page heading that repeats the title is dropped");
        check(candidates.get(1).notes().isEmpty()&&candidates.get(4).notes().isEmpty(),"Rows with no page have no notes");
        check(candidates.getFirst().notes().contains("- [ ] Answer the review questions"),"The rest of the page is kept as written");

        // Notion writes each page's properties under its heading. They are the
        // row's own columns, already imported as fields, so they are not notes.
        check(!candidates.getFirst().notes().contains("Status: Not started")&&!candidates.getFirst().notes().contains("Class: Biology"),
            "The page's property lines are not repeated in the notes");
        check(candidates.getFirst().notes().startsWith("Read pages 1-30"),"The notes start where the page's own text does");

        // A date property with a time still has a deadline: its day.
        var timed=NotionImport.read(("Name,Due\nMeet the study group,\"September 25, 2026 3:00 PM (EDT)\"\n"
            +"Hand in the essay,2026-09-26 23:59\n").getBytes(),"Study Tasks.csv");
        var timedRows=NotionImport.preview(timed,NotionImport.autoMap(timed.headers()));
        check(timedRows.get(0).due().equals(LocalDate.of(2026,9,25))&&timedRows.get(1).due().equals(LocalDate.of(2026,9,26)),
            "A date with a time imports as that day");

        var boxSheet=NotionImport.read(NotionFixture.checkboxZip(),"Study Tasks.zip");
        var boxCandidates=NotionImport.preview(boxSheet,NotionImport.autoMap(boxSheet.headers()));
        check(boxCandidates.getFirst().status()==TaskStatus.DONE&&boxCandidates.get(1).status()==TaskStatus.TODO,"A checked box is done, an empty one is open");
        check(boxCandidates.getFirst().tags().equals(List.of("Biology")),"Checkbox exports import the same way");

        // A remap takes effect immediately, and is what the preview exists for.
        var remapped=NotionImport.preview(sheet,new NotionImport.Mapping("Name","Class","Due","Status"));
        check(remapped.get(1).status()==TaskStatus.TODO&&remapped.get(1).tags().equals(List.of("In progress")),"A manual remap feeds the roles it names");
        var undated=NotionImport.preview(sheet,new NotionImport.Mapping("Name","Status",null,"Class"));
        check(undated.getFirst().due()==null,"Leaving a role unmapped leaves its field empty");

        refuses(()->NotionImport.preview(sheet,new NotionImport.Mapping(null,"Status","Due","Class")),"task title");
        refuses(()->NotionImport.preview(sheet,new NotionImport.Mapping("Subject","Status","Due","Class")),"Subject");
        refuses(()->NotionImport.preview(sheet,new NotionImport.Mapping("Name","Status","Name","Class")),"Row 1");
        refuses(()->NotionImport.read(new byte[0],"Study Tasks.csv"),"empty");
        refuses(()->NotionImport.read("not an export".getBytes(),"notes.txt"),"CSV");
        refuses(()->NotionImport.read("a,b\n\"unclosed,1\n".getBytes(),"Study Tasks.csv"),"quote");
        refuses(()->NotionImport.read(zipOf("Read me.md","# Nothing to import"),"ExportBlock.zip"),"no CSV");
        refuses(()->NotionImport.read(tooManyRows(),"Study Tasks.csv"),"1000");

        // Two rows with the same title and no title at all: nothing to file.
        var awkward=NotionImport.read("Name,Status,Due\n,,2026-09-12\nRead chapter 2,Not started,2026-09-13\n".getBytes(),"Study Tasks.csv");
        check(NotionImport.preview(awkward,NotionImport.autoMap(awkward.headers())).size()==1,"A row with no title is not a task");

        // ---- one transaction ------------------------------------------------
        var repo=new Memory();
        var tracker=new Tracker(repo,Clock.systemUTC());
        var order=NotionImport.prepare(candidates,tracker.state(),"Study Tasks");
        check(order.newTags().size()==4&&order.tasks().size()==5,"Every class becomes a tag: "+order.newTags().size());
        check(order.tasks().getFirst().notes().contains("Read pages 1-30")&&order.tasks().getFirst().due().equals(LocalDate.of(2026,9,12)),"Prepared tasks carry their notes and deadline");
        check(order.tasks().stream().map(Task::order).toList().equals(List.of(0,1,2,3,4)),"Prepared tasks land in export order");
        int before=repo.writes;
        int added=tracker.importTasks(order.newTags(),order.tasks());
        check(added==5&&tracker.state().tasks().size()==5,"The whole batch is added");
        check(tracker.state().tags().size()==4,"The new tags arrive with their tasks");
        check(repo.writes==before+1,"Tags and tasks commit in exactly one write");
        var chapter=tracker.state().tasks().getFirst();
        var biology=tracker.state().tags().stream().filter(t->t.name().equals("Biology")).findFirst().orElseThrow();
        check(chapter.tagId()!=null&&chapter.tagId().equals(biology.id()),"Each task points at the tag its class created");
        check(tracker.state().tasks().get(2).status()==TaskStatus.DONE,"A completed row imports as completed");

        // Re-importing the same export must not double anything, in one write or none.
        var again=NotionImport.prepare(NotionImport.preview(sheet,mapping),tracker.state(),"Study Tasks");
        check(again.tasks().isEmpty()&&again.newTags().isEmpty(),"A re-import of the same export prepares nothing");
        before=repo.writes;
        check(tracker.importTasks(again.newTags(),again.tasks())==0&&repo.writes==before+1,"A repeat import adds nothing and still commits once");
        check(tracker.state().tasks().size()==5,"The vault is unchanged by the repeat");

        // An existing task under the same title and deadline is a duplicate.
        var manual=new Memory();
        var other=new Tracker(manual,Clock.systemUTC());
        other.addTasks(List.of(new Task(UUID.randomUUID(),null,"Read the first chapter","",LocalDate.of(2026,9,12),false,"By hand")));
        var overlapping=NotionImport.prepare(candidates,other.state(),"Study Tasks");
        check(overlapping.tasks().size()==4,"A task already in Yoru is skipped");
        check(!overlapping.newTags().isEmpty(),"Its class still becomes a tag");

        // An existing tag is reused rather than duplicated.
        var withTag=new Memory();
        var tagged=new Tracker(withTag,Clock.systemUTC());
        tagged.addTag("biology",0x123456);
        var reuse=NotionImport.prepare(candidates,tagged.state(),"Study Tasks");
        check(reuse.newTags().stream().noneMatch(t->t.name().equalsIgnoreCase("Biology")),"An existing tag is reused whichever case it is written in");
        check(reuse.tasks().getFirst().tagId().equals(tagged.state().tags().getFirst().id()),"Tasks point at the tag that already existed");

        // A tag an earlier import made keeps its id when the user renames it.
        // Importing again must reuse it, not mint that id a second time.
        var renamed=new Memory();
        var renaming=new Tracker(renamed,Clock.systemUTC());
        renaming.importTasks(order.newTags(),order.tasks());
        var bio=renaming.state().tags().stream().filter(t->t.name().equals("Biology")).findFirst().orElseThrow();
        renaming.editTag(bio.id(),"Bio 101",bio.colour());
        var more=NotionImport.read("Name,Class\nA new reading,Biology\n".getBytes(),"More.csv");
        var next=NotionImport.prepare(NotionImport.preview(more,new NotionImport.Mapping("Name",null,null,"Class")),renaming.state(),"More");
        check(next.newTags().isEmpty()&&next.tasks().getFirst().tagId().equals(bio.id()),"A renamed imported tag is reused, not duplicated");
        check(renaming.importTasks(next.newTags(),next.tasks())==1,"and the import that reuses it succeeds");

        // ---- nothing lands unless all of it does -----------------------------
        var failing=new Memory();
        var fragile=new Tracker(failing,Clock.systemUTC());
        var beforeState=fragile.state();
        failing.fail=true;
        try { fragile.importTasks(order.newTags(),order.tasks()); throw new AssertionError("A failed save was accepted"); }
        catch(IOException expected){}
        check(fragile.state().equals(beforeState)&&failing.state.equals(beforeState),"A failed import leaves the vault exactly as it was");
        check(failing.state.tags().isEmpty()&&failing.state.tasks().isEmpty(),"No tag survives a failed import on its own");
        failing.fail=false;
        check(fragile.importTasks(order.newTags(),order.tasks())==5,"The same import succeeds once the vault can be written");

        var clash=new Memory();
        var stubborn=new Tracker(clash,Clock.systemUTC());
        stubborn.addTag("Biology",0x111111);
        var solid=stubborn.state();
        int settled=clash.writes;
        try {
            stubborn.importTasks(order.newTags(),order.tasks());
            throw new AssertionError("A tag that already exists was accepted");
        } catch(IllegalArgumentException expected){ check(expected.getMessage().contains("Biology"),"A duplicate tag is refused by name"); }
        check(stubborn.state().equals(solid)&&clash.writes==settled,"A refused import writes nothing at all");

        check(NotionImport.colourFor("Biology")==NotionImport.colourFor("biology"),"An imported tag's colour is stable for its name");
        check((NotionImport.colourFor("Biology")&~0xFFFFFF)==0,"And is a plain 24-bit colour");

        // ---- two classes, one assignment name --------------------------------
        // The same title and deadline in two classes is two pieces of work.
        var classes=NotionImport.read(("Name,Class,Due\n"
            +"Quiz 1,Biology,2026-09-12\n"
            +"Quiz 1,Chemistry,2026-09-12\n").getBytes(),"Classes.csv");
        var byClass=new NotionImport.Mapping("Name",null,"Due","Class");
        var twoClasses=new Memory();
        var classTracker=new Tracker(twoClasses,Clock.systemUTC());
        var bothQuizzes=NotionImport.prepare(NotionImport.preview(classes,byClass),classTracker.state(),"Classes");
        check(bothQuizzes.tasks().size()==2,"An assignment of the same name in two classes imports as two tasks");
        check(classTracker.importTasks(bothQuizzes.newTags(),bothQuizzes.tasks())==2,"and the tracker keeps both");
        check(classTracker.alreadyHas("Quiz 1",LocalDate.of(2026,9,12),"Biology"),"Biology's quiz is recorded");
        check(!classTracker.alreadyHas("Quiz 1",LocalDate.of(2026,9,12),"Physics"),
            "and a third class's quiz of that name is not already in Yoru");
        var quizAgain=NotionImport.prepare(NotionImport.preview(classes,byClass),classTracker.state(),"Classes");
        check(quizAgain.tasks().isEmpty(),"Re-importing the same two rows still adds nothing");

        // ---- dates are read or refused, never rounded -------------------------
        var impossible=NotionImport.read("Name,Due\nRead it,2/30/2026\n".getBytes(),"Dates.csv");
        refuses(()->NotionImport.preview(impossible,new NotionImport.Mapping("Name",null,"Due",null)),"Row 1");
        var ordinary=NotionImport.read("Name,Due\nRead it,9/22/2026\n".getBytes(),"Dates.csv");
        check(NotionImport.preview(ordinary,new NotionImport.Mapping("Name",null,"Due",null))
            .getFirst().due().equals(LocalDate.of(2026,9,22)),"An ordinary month-first date still reads");

        // ---- a title that cannot be stored as typed ---------------------------
        var control=NotionImport.read("Name,Class\n\"Read\tchapter\",Biology\n".getBytes(),"Control.csv");
        var cleaned=NotionImport.preview(control,new NotionImport.Mapping("Name",null,null,"Class")).getFirst();
        check(cleaned.title().equals("Read chapter"),"A tab inside a title becomes a space rather than failing the import");
        var prepared=NotionImport.prepare(List.of(cleaned),State.empty(),"Control");
        check(prepared.tasks().size()==1,"and the row imports");

        // ---- notes belong to one page ----------------------------------------
        var shared=zipOf("Study.csv","Name,Class\nReading,Biology\nReading,History\n");
        var twoPages=new java.io.ByteArrayOutputStream();
        try (var zip=new ZipOutputStream(twoPages)) {
            zip.putNextEntry(new ZipEntry("Study.csv"));
            zip.write("Name,Class\nReading,Biology\nReading,History\n".getBytes());
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("Study/Reading 1111111111111111111111111111aaaa.md"));
            zip.write("# Reading\n\nChapter four of the set text.\n".getBytes());
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("Study/Reading 2222222222222222222222222222bbbb.md"));
            zip.write("# Reading\n\nThe primary sources packet.\n".getBytes());
            zip.closeEntry();
        }
        check(shared.length>0,"the single-page fixture is built");
        var ambiguous=NotionImport.read(twoPages.toByteArray(),"Study.zip");
        for (var candidate:NotionImport.preview(ambiguous,new NotionImport.Mapping("Name",null,null,"Class")))
            check(candidate.notes().isEmpty(),
                "Two pages share the title, so neither row takes the other's notes");

        // ---- a title too long for a task -------------------------------------
        // The page is found by the whole title, so its heading and property
        // lines are still recognised and left out of the notes.
        String longTitle="Read the chapter and answer every question at the end of it, "
            +"including the optional ones, before the seminar on Thursday morning in the usual room, "
            +"and bring the worked solutions with you";
        var longRows=new java.io.ByteArrayOutputStream();
        try (var zip=new ZipOutputStream(longRows)) {
            zip.putNextEntry(new ZipEntry("Study.csv"));
            zip.write(("Name,Class\n\""+longTitle+"\",Biology\n").getBytes());
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("Study/"+longTitle+" 3333333333333333333333333333cccc.md"));
            zip.write(("# "+longTitle+"\n\nClass: Biology\n\nThe reading itself.\n").getBytes());
            zip.closeEntry();
        }
        var longCandidate=NotionImport.preview(NotionImport.read(longRows.toByteArray(),"Study.zip"),
            new NotionImport.Mapping("Name",null,null,"Class")).getFirst();
        check(longCandidate.title().length()<=160&&longCandidate.title().endsWith("…"),"An over-long title is shortened");
        check(longCandidate.notes().equals("The reading itself."),
            "and its page's heading and property lines still stay out of the notes");

        System.out.println("PASS: "+checks+" Notion import checks (zip and CSV, auto-map, remap, notes, tags, duplicates, one transaction)");
    }

    private static byte[] tooManyRows() {
        var csv=new StringBuilder("Name,Status,Due\n");
        for (int i=0;i<1001;i++) csv.append("Task ").append(i).append(",Not started,2026-09-12\n");
        return csv.toString().getBytes();
    }

    private static byte[] zipOf(String name,String content)throws IOException {
        var out=new java.io.ByteArrayOutputStream();
        try (var zip=new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(content.getBytes());
            zip.closeEntry();
        }
        return out.toByteArray();
    }
}
