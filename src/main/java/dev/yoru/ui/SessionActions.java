package dev.yoru.ui;

import dev.yoru.application.*;
import dev.yoru.domain.Model.*;
import java.awt.*;
import java.awt.event.*;
import java.time.Duration;
import java.util.*;
import java.util.List;
import java.util.function.*;
import javax.swing.*;
import static dev.yoru.ui.Theme.*;

/** Actions use stable IDs even after sorting/filtering the table. */
final class SessionActions extends JPanel {
    private final Tracker tracker;
    private final JTable table;
    private final List<Session> rows;
    private final Runnable changed;
    private final BooleanSupplier closed;
    private final Consumer<Exception> error;
    private final Predicate<String> confirmDelete;
    private final JButton edit,move,shift,delete,all,clear;
    private final JLabel count=label("",TYPE_LABEL,TEXT);

    SessionActions(Tracker tracker,JTable table,List<Session> rows,Consumer<Session> editOne,
                   Runnable changed,BooleanSupplier closed,Consumer<Exception> error){
        this(tracker,table,rows,editOne,changed,closed,error,null);
    }
    SessionActions(Tracker tracker,JTable table,List<Session> rows,Consumer<Session> editOne,
                   Runnable changed,BooleanSupplier closed,Consumer<Exception> error,Predicate<String> confirmation){
        super(new WrapFlowLayout(FlowLayout.LEFT,SPACE_SM,SPACE_XS));
        this.tracker=tracker;this.table=table;this.rows=List.copyOf(rows);this.changed=changed;this.closed=closed;this.error=error;
        confirmDelete=confirmation==null?message->Dialogs.confirmDestructive(this,message,"Delete selected sessions","Delete"):confirmation;
        setOpaque(false);setName("sessions.actions");setAlignmentX(0);
        count.setName("sessions.selected");add(count);
        all=button("Select shown",()->{if(table!=null)table.selectAll();});all.setName("sessions.all");add(all);
        clear=button("Clear",()->{if(table!=null)table.clearSelection();});clear.setName("sessions.clear");clear.getAccessibleContext().setAccessibleName("Clear session selection");add(clear);
        edit=button("Edit session…",()->{if(!closed.getAsBoolean()&&selection().size()==1)editOne.accept(selectedRows().getFirst());});
        edit.setName("sessions.edit");add(edit);
        move=button("Move to activity…",()->move());move.setName("sessions.move");add(move);
        shift=button("Shift times…",()->shift());shift.setName("sessions.shift");add(shift);
        delete=button("Delete…",this::delete);delete.setName("sessions.delete");add(delete);
        if(table!=null){
            table.setName("sessions.table");
            table.getAccessibleContext().setAccessibleName("Recorded sessions · select multiple rows to edit together");
            table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
            table.getSelectionModel().addListSelectionListener(e->update());
            table.addMouseListener(new MouseAdapter(){@Override public void mouseClicked(MouseEvent e){
                if(SwingUtilities.isLeftMouseButton(e)&&e.getClickCount()==2&&selection().size()==1&&!closed.getAsBoolean())
                    editOne.accept(selectedRows().getFirst());
            }});
            table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE,0),"clearSessions");
            table.getActionMap().put("clearSessions",new AbstractAction(){public void actionPerformed(ActionEvent e){table.clearSelection();}});
        }
        update();
    }
    private List<Session> selectedRows(){
        if(table==null)return List.of();
        return Arrays.stream(table.getSelectedRows()).map(table::convertRowIndexToModel).mapToObj(rows::get).toList();
    }
    Set<UUID> selection(){return new LinkedHashSet<>(selectedRows().stream().map(Session::id).toList());}
    private void update(){
        var selected=selectedRows();boolean any=!selected.isEmpty(),complete=any&&selected.stream().allMatch(s->s.end()!=null);
        count.setText(selected.isEmpty()?"Select sessions":"Selected: "+selected.size());
        all.setEnabled(table!=null&&table.getRowCount()>0);clear.setEnabled(any);edit.setEnabled(selected.size()==1);
        for(var button:List.of(move,shift,delete)){
            button.setEnabled(complete);
            button.setToolTipText(!any?"Select sessions in the table first":!complete?"Clock out before changing this selection":null);
        }
        edit.setToolTipText(selected.size()==1?"Edit this session":"Select exactly one session to edit its start and end");
    }
    boolean apply(Set<UUID> ids,SessionBatch.Change change){
        if(closed.getAsBoolean())return false;
        try{tracker.editSessions(ids,change);changed.run();return true;}
        catch(Exception failure){error.accept(failure);return false;}
    }
    void delete(){
        if(closed.getAsBoolean())return;
        var ids=selection();if(ids.isEmpty())return;
        String message="Delete "+plural(ids.size(),"recorded session")+"? Their recorded time will be removed from totals. A vault backup is kept first.";
        if(selectedRows().stream().anyMatch(AnkiTime::isSitting))
            message+=" Anki may import deleted recent sittings again while study-time importing is enabled.";
        if(!confirmDelete.test(message)||closed.getAsBoolean())return;
        try{tracker.deleteSessions(ids);changed.run();}catch(Exception failure){error.accept(failure);}
    }
    private void move(){
        if(closed.getAsBoolean())return;
        var ids=selection();var form=new MoveForm(tracker.state());
        while(!closed.getAsBoolean()&&Dialogs.confirm(this,form,"Move selected sessions","Move")){
            try{if(apply(ids,form.change()))return;}catch(Exception failure){error.accept(failure);}
        }
    }
    private void shift(){
        if(closed.getAsBoolean())return;
        var ids=selection();var form=new ShiftForm();
        while(!closed.getAsBoolean()&&Dialogs.confirm(this,form,"Shift selected session times","Apply")){
            try{if(apply(ids,form.change()))return;}catch(Exception failure){error.accept(failure);}
        }
    }
    static final class MoveForm extends JPanel{
        final JComboBox<Activity> activities;
        MoveForm(State state){
            setLayout(new BoxLayout(this,BoxLayout.Y_AXIS));setOpaque(false);
            add(bodyLabel("Move all selected sessions to this activity. Their times and durations stay the same."));gap(this,SPACE_SM);
            activities=plainCombo(new JComboBox<>(state.activities().toArray(Activity[]::new)));
            activities.setAlignmentX(0);activities.setName("sessions.activity");activities.getAccessibleContext().setAccessibleName("Destination activity");add(activities);
        }
        SessionBatch.Move change(){
            if(!(activities.getSelectedItem() instanceof Activity activity))throw new IllegalArgumentException("Choose an activity.");
            return new SessionBatch.Move(activity.id());
        }
    }
    static final class ShiftForm extends JPanel{
        final JSpinner minutes=plainSpinner(new JSpinner(new SpinnerNumberModel(0,-525600,525600,1)));
        ShiftForm(){
            setLayout(new BoxLayout(this,BoxLayout.Y_AXIS));setOpaque(false);
            add(bodyLabel("Move every selected start and end by this many minutes. Negative moves earlier; positive moves later. Durations stay the same, including across daylight-saving changes."));gap(this,SPACE_SM);
            minutes.setAlignmentX(0);minutes.setName("sessions.minutes");minutes.getAccessibleContext().setAccessibleName("Minutes to shift");add(minutes);
            gap(this,SPACE_SM);add(bodyLabel("Overlapping sessions and future recorded time are refused before anything is saved."));
        }
        SessionBatch.Shift change()throws java.text.ParseException{
            minutes.commitEdit();return new SessionBatch.Shift(Duration.ofMinutes(((Number)minutes.getValue()).longValue()));
        }
    }
}
