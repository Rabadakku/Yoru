package dev.yoru.ui;

import dev.yoru.application.Tracker;
import dev.yoru.domain.Model.*;
import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.time.*;
import java.time.format.TextStyle;
import java.util.Locale;
import java.util.UUID;
import static dev.yoru.ui.Theme.*;

/**
 * The weekly template: "every Monday, 9:00 AM to 10:30 AM" (#4).
 *
 * Like TagEditor and unlike PartyEditor this is not a draft — Tracker commits
 * each entry on its own, and State refuses an overlap on the same weekday, so
 * the refusal arrives while the dialog is still open and can be corrected.
 *
 * Every entry can be edited in place as well as removed (#21), and times are
 * read as forgivingly as everywhere else a time is typed: "9am", "9:30p", "21:30".
 */
final class WeeklyTemplate extends JPanel {
    private final Tracker tracker;
    private final Runnable changed;
    private final JComboBox<Object> activity=plainCombo(new JComboBox<>());
    private final JComboBox<DayOfWeek> day=days();
    private final JTextField from=new JTextField("9:00 AM",8);
    private final JTextField to=new JTextField("10:30 AM",8);

    WeeklyTemplate(Tracker tracker,Runnable changed) {
        this.tracker=tracker;this.changed=changed;
        setLayout(new BoxLayout(this,BoxLayout.Y_AXIS));setOpaque(false);
        styleInput(from);styleInput(to);
        from.setName("repeat.from");to.setName("repeat.to");
        day.setName("repeat.day");activity.setName("repeat.activity");
        // Four unnamed controls in a row. Only "to" has a caption to point at
        // it; the rest are named outright, since a screen reader reading the
        // form cannot see which combo is the day and which is the activity.
        day.getAccessibleContext().setAccessibleName("Repeat day");
        from.getAccessibleContext().setAccessibleName("Start time");
        to.getAccessibleContext().setAccessibleName("End time");
        activity.getAccessibleContext().setAccessibleName("Activity");
        rebuild();
    }

    /** A weekday picker that says "Tuesday", not "TUESDAY". */
    private static JComboBox<DayOfWeek> days() {
        var picker=plainCombo(new JComboBox<>(DayOfWeek.values()));
        picker.setRenderer(new DefaultListCellRenderer(){
            @Override public Component getListCellRendererComponent(JList<?> list,Object value,int index,boolean sel,boolean focus){
                var c=(JLabel)super.getListCellRendererComponent(list,value,index,sel,focus);
                if(value instanceof DayOfWeek d)c.setText(d.getDisplayName(TextStyle.FULL,Locale.ENGLISH));
                c.setFont(bodyFont());c.setBackground(sel?LINE:PANEL);c.setForeground(TEXT);
                return c;
            }
        });
        return picker;
    }

    private void rebuild() {
        removeAll();
        var rules=tracker.state().recurring();
        add(sectionHeader("EVERY WEEK · "+rules.size()));gap(this,SPACE_SM);
        add(bodyLabel("Repeats on the calendar every week. One-off plans stay as blocks you drag."));gap(this,SPACE_MD);
        for(var value:DayOfWeek.values()) {
            var onDay=rules.stream().filter(r->r.dayOfWeek()==value)
                .sorted(java.util.Comparator.comparing(RecurringBlock::startTime)).toList();
            if(onDay.isEmpty()) continue;
            add(label(value.getDisplayName(TextStyle.FULL,Locale.ENGLISH).toUpperCase(Locale.ENGLISH),TYPE_CAPTION,MUTED));
            gap(this,SPACE_XS);
            for(var rule:onDay) {
                var line=new JPanel(new BorderLayout(SPACE_MD,0));line.setOpaque(false);line.setAlignmentX(0);
                String changed=rule.changes().isEmpty()?"":" · "+rule.changes().size()
                    +(rule.changes().size()==1?" week changed on its own":" weeks changed on their own");
                line.add(label(DateText.time(rule.startTime())+" – "+DateText.time(rule.endTime())+" · "+name(rule.activityId())+changed,TYPE_LABEL,TEXT),BorderLayout.CENTER);
                var actions=tightRow();
                var edit=button("Edit",()->edit(rule));
                edit.setName("repeat.edit."+rule.id());
                edit.getAccessibleContext().setAccessibleName("Edit "+value.getDisplayName(TextStyle.FULL,Locale.ENGLISH)+" "+DateText.time(rule.startTime()));
                var remove=button("Remove",()->{
                    try{tracker.deleteRepeat(rule.id());done();}catch(Exception e){Dialogs.error(this,e.getMessage());}
                });
                remove.setName("repeat.remove."+rule.id());
                actions.add(edit);actions.add(remove);
                line.add(actions,BorderLayout.EAST);
                add(line);gap(this,SPACE_SM);
            }
            gap(this,SPACE_SM);
        }
        if(rules.isEmpty()){add(emptyState("No repeats yet.","Add one below and it appears on the calendar every week.",null));gap(this,SPACE_MD);}

        activity.removeAllItems();
        tracker.state().activities().forEach(activity::addItem);
        gap(this,SPACE_XS);add(label("ADD A REPEATING BLOCK",TYPE_CAPTION,MUTED));gap(this,SPACE_SM);
        var form=tightRow();
        var toCaption=label("to",TYPE_BODY,MUTED);toCaption.setLabelFor(to);
        form.add(day);form.add(from);form.add(toCaption);form.add(to);form.add(activity);
        var add=button("Add",this::create);add.setName("repeat.add");
        form.add(add);
        add(form);gap(this,SPACE_SM);
        add(label("Times like 9:00 AM, 9:30p or 21:30.",TYPE_CAPTION,MUTED));
        revalidate();repaint();
    }

    private String name(UUID id) {
        return tracker.state().activities().stream().filter(a->a.id().equals(id))
            .map(Activity::name).findFirst().orElse("Activity");
    }

    private void create() {
        try {
            if(!(activity.getSelectedItem() instanceof Activity chosen))
                throw new IllegalArgumentException("Create an activity first.");
            tracker.repeat(chosen.id(),(DayOfWeek)day.getSelectedItem(),time(from.getText(),"Start"),time(to.getText(),"End"));
            done();
        } catch(Exception e){Dialogs.error(this,e.getMessage());}
    }

    /** One entry's day, times and activity, in a dialog that reopens on a refusal with what was typed. */
    private void edit(RecurringBlock rule) {
        var picker=days();
        picker.setSelectedItem(rule.dayOfWeek());
        picker.getAccessibleContext().setAccessibleName("Repeat day");
        var start=styleInput(new JTextField(DateText.time(rule.startTime()),8));
        start.getAccessibleContext().setAccessibleName("Start time");
        var end=styleInput(new JTextField(DateText.time(rule.endTime()),8));
        end.getAccessibleContext().setAccessibleName("End time");
        var chosen=plainCombo(new JComboBox<Object>());
        tracker.state().activities().forEach(chosen::addItem);
        for(int i=0;i<chosen.getItemCount();i++)
            if(((Activity)chosen.getItemAt(i)).id().equals(rule.activityId())) chosen.setSelectedIndex(i);
        chosen.getAccessibleContext().setAccessibleName("Activity");
        var form=stack();
        form.add(label("Day",TYPE_LABEL,TEXT));form.add(picker);gap(form,SPACE_MD);
        var times=tightRow();
        times.add(start);times.add(label("to",TYPE_BODY,MUTED));times.add(end);
        form.add(label("Time",TYPE_LABEL,TEXT));form.add(times);
        form.add(label("Times like 9:00 AM, 9:30p or 21:30.",TYPE_CAPTION,MUTED));gap(form,SPACE_MD);
        form.add(label("Activity",TYPE_LABEL,TEXT));form.add(chosen);
        if(!rule.changes().isEmpty()) {
            // Said before it happens: a rule moved to another day has no block
            // left on the dates its changed weeks name (#59).
            gap(form,SPACE_MD);
            form.add(label("Moving it to another day puts back the "+rule.changes().size()
                +(rule.changes().size()==1?" week":" weeks")+" changed on their own.",TYPE_CAPTION,MUTED));
        }
        while(Dialogs.confirm(this,form,"Edit repeating block","Save")) {
            try {
                save(tracker,rule.id(),chosen.getSelectedItem(),(DayOfWeek)picker.getSelectedItem(),start.getText(),end.getText());
                done();
                return;
            } catch(Exception e){Dialogs.error(this,e.getMessage());}
        }
    }

    /** What the edit dialog saves, callable without the modal dialog. */
    static void save(Tracker tracker,UUID id,Object activity,DayOfWeek day,String start,String end) throws IOException {
        if(!(activity instanceof Activity chosen)) throw new IllegalArgumentException("Choose an activity.");
        tracker.editRepeat(id,chosen.id(),day,time(start,"Start"),time(end,"End"));
    }

    /** Read here rather than in the domain so a typo is corrected in place, and named for the field it came from. */
    private static LocalTime time(String typed,String which) {
        try { return DateText.parseTime(typed); }
        catch(IllegalArgumentException e) { throw new IllegalArgumentException(which+" time: "+e.getMessage()); }
    }

    private void done() {
        rebuild();
        changed.run();
        var window=SwingUtilities.getWindowAncestor(this);
        if(window!=null)window.pack();
    }

    static void open(Component parent,Tracker tracker,Runnable changed) {
        Dialogs.choose(parent,new WeeklyTemplate(tracker,changed),"Weekly template","Done");
    }
}
