package dev.yoru.ui;

import dev.yoru.application.*;
import dev.yoru.domain.Model.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import javax.imageio.ImageIO;
import java.time.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.*;
import javax.swing.*;
import javax.swing.table.*;

/** Tests the actual controls against sorted/filtered views and failed saves. */
public final class SessionActionsTest {
    private static int checks;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    private static class Memory implements Repository{
        State state;boolean fail;Memory(State state){this.state=state;}
        public State load(){return state;}public void save(State next)throws java.io.IOException{if(fail)throw new java.io.IOException("Synthetic failure");state=next;}public void close(){}
    }
    private static JButton button(Container root,String name){for(var child:root.getComponents())if(child instanceof JButton b&&name.equals(b.getName()))return b;throw new AssertionError(name);}
    public static void main(String[] args)throws Exception{
        SwingUtilities.invokeAndWait(()->{try{run();if(args.length>0)render(Path.of(args[0]));}catch(Exception e){throw new RuntimeException(e);}});
        System.out.println("PASS: "+checks+" session action checks (sorted/filtered IDs, forms, selection, failure retry, closed vault, confirmation)");
    }
    private static void run()throws Exception{
        Theme.install();
        var now=Instant.parse("2026-09-24T20:00:00Z");var activity=new Activity(UUID.randomUUID(),"Reading",0);var target=new Activity(UUID.randomUUID(),"Writing",0);
        var first=new Session(UUID.randomUUID(),activity.id(),now.minusSeconds(18000),now.minusSeconds(14400));
        var imported=new Session(new UUID(0x416E6B6900008000L,0x8000000000000021L),activity.id(),now.minusSeconds(10800),now.minusSeconds(7200));
        var running=new Session(UUID.randomUUID(),activity.id(),now.minusSeconds(600),null);
        var rows=List.of(first,imported,running);
        var repo=new Memory(State.empty().withCore(List.of(activity,target),rows,List.of()));
        var t=new Tracker(repo,Clock.fixed(now,ZoneOffset.UTC));
        var table=new JTable(new DefaultTableModel(new Object[][]{{"C"},{"A"},{"B"}},new String[]{"Order"}));
        var sorter=new TableRowSorter<TableModel>(table.getModel());table.setRowSorter(sorter);sorter.toggleSortOrder(0);
        var errors=new ArrayList<Exception>();var edits=new ArrayList<Session>();var changes=new AtomicInteger();var closed=new AtomicBoolean();var confirmations=new ArrayList<String>();var allowDelete=new AtomicBoolean();
        var actions=new SessionActions(t,table,rows,edits::add,changes::incrementAndGet,closed::get,errors::add,message->{confirmations.add(message);return allowDelete.get();});
        check(!button(actions,"sessions.move").isEnabled()&&!button(actions,"sessions.delete").isEnabled(),"No selection disables mutations");
        table.setRowSelectionInterval(0,0);
        check(actions.selection().equals(Set.of(imported.id())),"Sorted view selection maps to stable session IDs");
        button(actions,"sessions.edit").doClick();check(edits.equals(List.of(imported)),"Single edit uses the selected model session");
        table.addRowSelectionInterval(2,2);
        check(!button(actions,"sessions.edit").isEnabled()&&button(actions,"sessions.move").isEnabled(),"Several completed rows enable bulk but disable single edit");
        sorter.toggleSortOrder(0);
        check(actions.selection().equals(Set.of(first.id(),imported.id())),"Changing table order retains the same selected IDs");
        var form=new SessionActions.MoveForm(t.state());form.activities.setSelectedItem(target);
        repo.fail=true;
        check(!actions.apply(actions.selection(),form.change())&&errors.size()==1,"A failed form save is reported");
        check(actions.selection().equals(Set.of(first.id(),imported.id()))&&t.state().sessions().equals(rows),"Failed writes retain selection and records");
        repo.fail=false;check(actions.apply(actions.selection(),form.change()),"The unchanged selection can be retried");
        check(t.state().sessions().get(0).activityId().equals(target.id())&&t.state().sessions().get(1).activityId().equals(target.id()),"The actual move form applies to every selected row");
        var shift=new SessionActions.ShiftForm();shift.minutes.setValue(-30);
        check(actions.apply(actions.selection(),shift.change()),"The shift form accepts negative minutes");
        check(t.state().sessions().getFirst().start().equals(first.start().minusSeconds(1800)),"Shift form moves timestamps by elapsed minutes");
        ((JSpinner.DefaultEditor)shift.minutes.getEditor()).getTextField().setText("not a number");
        try{shift.change();throw new AssertionError("Invalid minutes accepted");}catch(java.text.ParseException expected){checks++;}
        var saved=t.state();closed.set(true);
        check(!actions.apply(actions.selection(),new SessionBatch.Move(activity.id())),"Closed vault prevents a stale action");
        actions.delete();check(confirmations.isEmpty()&&t.state().equals(saved),"Closed vault cannot confirm or delete");closed.set(false);
        button(actions,"sessions.all").doClick();
        check(table.getSelectedRowCount()==3&&!button(actions,"sessions.delete").isEnabled(),"A selection containing the running timer disables bulk changes");
        table.getActionMap().get("clearSessions").actionPerformed(new ActionEvent(table,0,"clear"));
        check(table.getSelectedRowCount()==0,"Escape clears selection");
        sorter.setRowFilter(new RowFilter<>(){public boolean include(Entry<? extends TableModel,? extends Integer> entry){return !entry.getStringValue(0).equals("B");}});
        button(actions,"sessions.all").doClick();
        check(actions.selection().equals(Set.of(first.id(),imported.id())),"Select shown excludes filtered-out records");
        actions.delete();check(t.state().equals(saved),"Cancelling the confirmation keeps all records");
        check(confirmations.getLast().contains("Anki may import"),"Deleting an imported sitting explains reimport behavior");
        allowDelete.set(true);repo.fail=true;actions.delete();
        check(t.state().equals(saved)&&table.getSelectedRowCount()==2,"Delete failure retains the selection for retry");
        repo.fail=false;actions.delete();
        check(t.state().sessions().equals(List.of(running)),"Confirmed delete removes only the selected completed sessions");
        var empty=new SessionActions(t,null,List.of(),edits::add,()->{},()->false,errors::add);
        check(!button(empty,"sessions.all").isEnabled()&&!button(empty,"sessions.edit").isEnabled(),"An empty table has no active actions");
    }
    private static void render(Path out)throws Exception{
        Files.createDirectories(out);
        for(var theme:ThemeId.values())for(int scale:new int[]{100,200}){
            TextSize.use(scale);Theme.apply(theme);
            var now=Instant.parse("2026-09-24T20:00:00Z");
            var activity=new Activity(UUID.randomUUID(),"Reading",0);
            var sessions=List.of(new Session(UUID.randomUUID(),activity.id(),now.minusSeconds(18000),now.minusSeconds(14400)),
                new Session(UUID.randomUUID(),activity.id(),now.minusSeconds(10800),now.minusSeconds(7200)));
            var tracker=new Tracker(new Memory(State.empty().withCore(List.of(activity),sessions,List.of())),Clock.fixed(now,ZoneOffset.UTC));
            var table=Theme.plainTable(new JTable(new DefaultTableModel(new Object[][]{
                {"Reading","Sep 24, 3:00 PM","Sep 24, 4:00 PM","1h"},
                {"Reading","Sep 24, 5:00 PM","Sep 24, 6:00 PM","1h"}},new String[]{"Activity","Start (local)","End (local)","Duration"})));
            table.setFont(Theme.bodyFont());
            var controls=new SessionActions(tracker,table,sessions,s->{},()->{},()->false,e->{throw new AssertionError(e);});
            table.setRowSelectionInterval(0,1);
            var panel=new JPanel(new BorderLayout(0,Theme.SPACE_MD));panel.setBackground(Theme.BG);
            panel.setBorder(BorderFactory.createEmptyBorder(24,24,24,24));
            panel.add(new JScrollPane(table),BorderLayout.CENTER);panel.add(controls,BorderLayout.SOUTH);
            panel.setSize(scale==100?900:1200,scale==100?330:460);Preview.layout(panel);
            for(var child:controls.getComponents())check(child.getY()+child.getHeight()<=controls.getHeight(),"Session action remains within its wrapping toolbar");
            var image=new BufferedImage(panel.getWidth(),panel.getHeight(),BufferedImage.TYPE_INT_RGB);
            var g=image.createGraphics();panel.printAll(g);g.dispose();
            ImageIO.write(image,"png",out.resolve("sessions-"+theme.name().toLowerCase(Locale.ROOT)+"-"+scale+".png").toFile());
        }
        TextSize.use(100);
    }

}
