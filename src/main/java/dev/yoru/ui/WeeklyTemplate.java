package dev.yoru.ui;

import dev.yoru.application.Tracker;
import dev.yoru.domain.Model.*;
import javax.swing.*;
import java.awt.*;
import java.time.*;
import java.time.format.TextStyle;
import java.util.Locale;
import static dev.yoru.ui.Theme.*;

/**
 * The weekly template: "every Monday, 09:00 to 10:30" (#4).
 *
 * Like TagEditor and unlike PartyEditor this is not a draft — Tracker commits
 * each entry on its own, and State refuses an overlap on the same weekday, so
 * the refusal arrives while the dialog is still open and can be corrected.
 */
final class WeeklyTemplate extends JPanel {
    private final Tracker tracker;
    private final Runnable changed;
    private final JComboBox<Object> activity=plainCombo(new JComboBox<>());
    private final JComboBox<DayOfWeek> day=plainCombo(new JComboBox<>(DayOfWeek.values()));
    private final JTextField from=new JTextField("09:00",7);
    private final JTextField to=new JTextField("10:30",7);

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
        day.setRenderer(new DefaultListCellRenderer(){
            @Override public Component getListCellRendererComponent(JList<?> list,Object value,int index,boolean sel,boolean focus){
                var c=(JLabel)super.getListCellRendererComponent(list,value,index,sel,focus);
                if(value instanceof DayOfWeek d)c.setText(d.getDisplayName(TextStyle.FULL,Locale.ENGLISH));
                c.setFont(bodyFont());c.setBackground(sel?LINE:PANEL);c.setForeground(TEXT);
                return c;
            }
        });
        rebuild();
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
                var line=new JPanel(new BorderLayout(12,0));line.setOpaque(false);line.setAlignmentX(0);
                line.add(label(rule.startTime()+" – "+rule.endTime()+" · "+name(rule.activityId()),TYPE_LABEL,TEXT),BorderLayout.CENTER);
                var remove=button("Remove",()->{
                    try{tracker.deleteRepeat(rule.id());done();}catch(Exception e){Dialogs.error(this,e.getMessage());}
                });
                remove.setName("repeat.remove."+rule.id());
                line.add(remove,BorderLayout.EAST);
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
        add(label("24-hour times, HH:MM.",TYPE_CAPTION,MUTED));
        revalidate();repaint();
    }

    private String name(java.util.UUID id) {
        return tracker.state().activities().stream().filter(a->a.id().equals(id))
            .map(Activity::name).findFirst().orElse("Activity");
    }

    private void create() {
        try {
            if(!(activity.getSelectedItem() instanceof Activity chosen))
                throw new IllegalArgumentException("Create an activity first.");
            tracker.repeat(chosen.id(),(DayOfWeek)day.getSelectedItem(),time(from,"start"),time(to,"end"));
            done();
        } catch(Exception e){Dialogs.error(this,e.getMessage());}
    }

    /** Parsed here rather than in the domain so a typo is corrected in place. */
    private static LocalTime time(JTextField field,String which) {
        try { return LocalTime.parse(field.getText().strip()); }
        catch(java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException("Enter the "+which+" time as HH:MM, like 09:00.");
        }
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
