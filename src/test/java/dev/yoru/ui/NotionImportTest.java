package dev.yoru.ui;

import dev.yoru.application.*;
import dev.yoru.domain.Model.*;
import dev.yoru.importer.NotionFixture;
import dev.yoru.importer.NotionImport;
import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.time.*;
import java.util.*;

/**
 * The Notion import preview, driven the way a user drives it.
 *
 * The panel is the only place the mapping can be corrected, so the checks are
 * about that: the columns arrive already mapped, changing one immediately
 * changes the preview, a mapping that cannot work is refused with a reason
 * rather than a half-import, and the board is only ever written by the one
 * tracker call the approval leads to. The export is invented — see
 * {@link NotionFixture} — so no real Notion data is involved.
 */
public final class NotionImportTest {
    private static int checks;
    private static void check(boolean value,String message) { checks++;if(!value)throw new AssertionError(message); }

    private static Component find(Container root,String name) {
        for(var child:root.getComponents()) {
            if(name.equals(child.getName()))return child;
            if(child instanceof Container nested) {var found=find(nested,name);if(found!=null)return found;}
        }
        return null;
    }

    private static final class Memory implements Repository {
        State state=State.empty();boolean fail;int writes;
        public State load(){return state;}
        public void save(State state)throws IOException {if(fail)throw new IOException("disk full");this.state=state;writes++;}
        public void close(){}
    }

    private static void refuses(Action action,String expected)throws Exception {
        try { action.run(); throw new AssertionError("Accepted what should be refused: "+expected); }
        catch(IOException e) { check(e.getMessage().contains(expected),"Refused with a reason: "+e.getMessage()); }
    }

    private interface Action { void run() throws Exception; }

    public static void main(String[] args)throws Exception {
        SwingUtilities.invokeAndWait(()->{
            try { for(var theme:ThemeId.values()) one(theme); }
            catch(Exception e){throw new RuntimeException(e);}
        });
        System.out.println("PASS: "+checks+" Notion import panel checks (auto-map, remap, preview, refusals, one write)");
    }

    private static void one(ThemeId theme) throws Exception {
        Theme.apply(theme);
        var repo=new Memory();
        var tracker=new Tracker(repo,Clock.systemUTC());
        var form=new NotionImportPanel(tracker);
        var title=(JComboBox<?>)find(form,"notion.title");
        var status=(JComboBox<?>)find(form,"notion.status");
        var due=(JComboBox<?>)find(form,"notion.due");
        var tags=(JComboBox<?>)find(form,"notion.tags");
        var rows=(JTable)find(form,"notion.rows");
        var source=(JTextField)find(form,"notion.source");
        var message=(JTextArea)find(form,"notion.message");
        check(title!=null&&status!=null&&due!=null&&tags!=null&&rows!=null&&source!=null&&message!=null,
            "The mapping, the rows and the message are all present");

        check(!title.isEnabled()&&rows.getRowCount()==0,"There is nothing to map until an export is chosen");
        refuses(form::batch,"Choose the Notion export");
        form.showError("Choose the Notion export first. Nothing was saved.");
        check(message.getForeground().equals(Theme.DANGER)&&message.getText().contains("Choose the Notion export"),
            "An unloaded panel says what is missing without hiding it");

        form.load(NotionFixture.zip(),"Study Tasks.zip");
        check(title.getSelectedItem().equals("Name")&&status.getSelectedItem().equals("Status")
            &&due.getSelectedItem().equals("Due")&&tags.getSelectedItem().equals("Class"),
            "The usual columns are mapped before the user touches anything");
        check(rows.getRowCount()==5,"Every row is previewed");
        check(String.valueOf(rows.getValueAt(0,1)).equals("Read the first chapter"),"The preview shows the title");
        check(String.valueOf(rows.getValueAt(0,2)).equals("2026-09-12"),"The preview shows the deadline");
        check(String.valueOf(rows.getValueAt(0,3)).equals("TODO"),"The preview shows the status");
        check(String.valueOf(rows.getValueAt(0,4)).equals("Biology"),"The preview shows the tag the class becomes");
        check(String.valueOf(rows.getValueAt(0,5)).contains("Read pages 1-30"),"The preview shows the page text becoming notes");
        check(String.valueOf(rows.getValueAt(3,4)).equals("Biology (+1 more)"),"A second class is shown as not imported, since a task holds one");
        check(Boolean.TRUE.equals(rows.getValueAt(0,0)),"Rows start ticked");
        check(message.getText().contains("5 of 5 rows ready")&&message.getText().contains("2 with page notes")
            &&message.getText().contains("4 new tags"),"The summary counts what the import would do: "+message.getText());
        check(source.getText().equals("Study Tasks"),"The source label is suggested from the export's name");

        // A remap is the whole point of the preview, and takes effect at once.
        tags.setSelectedItem("Status");
        check(String.valueOf(rows.getValueAt(0,4)).equals("Not started"),"Changing the tag column changes the preview");
        due.setSelectedItem("(not imported)");
        check(form.batch().tasks().getFirst().due()==null,"Leaving the due date unmapped imports without deadlines");
        due.setSelectedItem("Due"); tags.setSelectedItem("Class");
        check(form.batch().tasks().getFirst().due().equals(LocalDate.of(2026,9,12)),"Putting the mapping back restores the deadlines");

        // A row unticked by hand stays unticked through a remap, and out of the import.
        rows.setValueAt(false,2,0);
        tags.setSelectedItem("Status"); tags.setSelectedItem("Class");
        check(!Boolean.TRUE.equals(rows.getValueAt(2,0)),"A row unticked by hand stays unticked after a remap");
        check(form.batch().tasks().size()==4,"and stays out of the import");
        rows.setValueAt(true,2,0);

        title.setSelectedItem("(not imported)");
        refuses(form::batch,"task title");
        form.showError("Choose which column holds the task title.");
        check(rows.getRowCount()==5&&message.getText().contains("task title"),"The rows stay put so the mapping can be corrected");
        title.setSelectedItem("Name");

        // A checkbox export maps and imports the same way.
        form.load(NotionFixture.checkboxZip(),"Study Tasks.zip");
        check(status.getSelectedItem().equals("Done"),"A checkbox property is mapped as the status");
        check(String.valueOf(rows.getValueAt(0,3)).equals("DONE")&&String.valueOf(rows.getValueAt(1,3)).equals("TODO"),
            "A ticked box imports as done and an empty one as open");

        // ---- the one write ---------------------------------------------------
        form.load(NotionFixture.zip(),"Study Tasks.zip");
        var batch=form.batch();
        check(batch.newTags().size()==4&&batch.tasks().size()==5,"The panel prepares the tags and the tasks, classes included");
        check(batch.tasks().getFirst().source().equals("Study Tasks"),"Every imported task carries the source label");
        check(repo.writes==0,"Reviewing, remapping and previewing save nothing at all");
        check(tracker.importTasks(batch.newTags(),batch.tasks())==5,"The approved import adds every row");
        check(repo.writes==1,"Tags and tasks are one write");
        check(tracker.state().tags().size()==4&&tracker.state().tasks().getFirst().tagId()!=null,"The classes became tags in that same write");

        form.load(NotionFixture.zip(),"Study Tasks.zip");
        check(rows.getRowCount()==5&&!Boolean.TRUE.equals(rows.getValueAt(0,0)),"A row already in Yoru opens unticked");
        check(message.getText().contains("already in Yoru")&&message.getText().contains("0 of 5 rows ready"),
            "And the summary says so rather than promising work: "+message.getText());
        check(NotionImport.prepare(form.shown(),tracker.state(),"Study Tasks").tasks().isEmpty(),
            "A re-import prepares nothing to write");
        refuses(form::batch,"ticked");
        form.showError("No rows are ticked, so there is nothing to import.");
        check(message.getText().contains("ticked"),"An empty selection is refused where the user can see it");

        // A vault that cannot be written leaves the board exactly as it was.
        var failing=new Memory();
        var fragile=new Tracker(failing,Clock.systemUTC());
        var fragileForm=new NotionImportPanel(fragile);
        fragileForm.load(NotionFixture.zip(),"Study Tasks.zip");
        var fragileRows=(JTable)find(fragileForm,"notion.rows");
        for(int i=0;i<fragileRows.getRowCount();i++) fragileRows.setValueAt(false,i,0);
        refuses(fragileForm::batch,"ticked");
        for(int i=0;i<fragileRows.getRowCount();i++) fragileRows.setValueAt(true,i,0);
        var prepared=fragileForm.batch();
        var before=fragile.state();
        failing.fail=true;
        refuses(()->fragile.importTasks(prepared.newTags(),prepared.tasks()),"disk full");
        check(fragile.state().equals(before)&&failing.state.equals(before),"A failed import leaves the vault untouched");
        check(failing.state.tags().isEmpty()&&failing.state.tasks().isEmpty(),"Not one tag survives a failed import on its own");
    }
}
