package dev.yoru.ui;

import dev.yoru.application.Tracker;
import dev.yoru.domain.Model.Habit;
import java.awt.*;
import java.time.*;
import java.util.UUID;
import javax.swing.*;
import static dev.yoru.ui.Theme.*;

/** A bounded four-week window into the whole history, retaining its place after edits. */
final class DailyHabitHistory extends JPanel {
    private final Tracker tracker;
    private final Runnable changed;
    private final UUID id;
    private final JPanel days=stack();
    private final JButton earlier,later,current;
    private final DateField date;
    private LocalDate end;

    DailyHabitHistory(Tracker tracker,Runnable changed,UUID id) {
        super(new BorderLayout(0,SPACE_SM));
        this.tracker=tracker;this.changed=changed;this.id=id;
        setOpaque(false);
        setPreferredSize(new Dimension(Math.min(grow(580),1000),Math.min(grow(420),580)));
        end=today(habit());
        date=new DateField(end,"Show date",false);
        date.setName("habit.history.date");
        var controls=stack();
        var navigation=wrappingRow();
        earlier=button("← 4 weeks",()->show(end.minusWeeks(4)));
        earlier.setName("habit.history.earlier");
        earlier.getAccessibleContext().setAccessibleName("Previous four weeks");
        later=button("4 weeks →",()-> {
            var next=end.plusWeeks(4);
            show(next.isAfter(today(habit()))?today(habit()):next);
        });
        later.setName("habit.history.later");
        later.getAccessibleContext().setAccessibleName("Next four weeks");
        current=button("Today",()->show(today(habit())));
        current.setName("habit.history.today");
        navigation.add(earlier);navigation.add(later);navigation.add(current);
        controls.add(navigation);
        var jump=wrappingRow();
        jump.add(date);
        var go=button("Show date",()-> {
            try{show(date.value());}catch(Exception failure){Dialogs.error(this,"Check date",failure.getMessage());}
        });
        go.setName("habit.history.go");jump.add(go);
        controls.add(jump);
        add(controls,BorderLayout.NORTH);
        var scroll=new JScrollPane(days);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        add(scroll,BorderLayout.CENTER);
        rebuild();
    }

    private Habit habit() {
        return tracker.state().habits().stream().filter(h->h.id().equals(id)).findFirst()
            .orElseThrow(()->new IllegalArgumentException("This habit no longer exists."));
    }
    private LocalDate today(Habit habit) { return tracker.now().atZone(ZoneId.of(habit.zone())).toLocalDate(); }
    void show(LocalDate requested) {
        var today=today(habit());
        if(requested.isAfter(today))throw new IllegalArgumentException("Choose today or a past date.");
        if(requested.getYear()<1900)throw new IllegalArgumentException("Choose a date in 1900 or later.");
        end=requested;
        date.set(end);
        rebuild();
    }
    private static void restoreFocus(Container root,String name) {
        for(var child:root.getComponents()) {
            if(name.equals(child.getName())) {child.requestFocusInWindow();return;}
            if(child instanceof Container nested)restoreFocus(nested,name);
        }
    }
    private void rebuild() {
        var habit=habit();
        days.removeAll();
        HabitsPanel.daily(tracker,()->{
            var focused=KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
            String name=focused==null?null:focused.getName();
            changed.run();rebuild();
            if(name!=null)SwingUtilities.invokeLater(()->restoreFocus(days,name));
        },habit,days,end);
        var settings=tracker.state().settings();
        earlier.setEnabled(end.minusWeeks(4).getYear()>=1900);
        later.setEnabled(settings.weekOf(end).isBefore(settings.weekOf(today(habit()))));
        current.setEnabled(!settings.weekOf(end).equals(settings.weekOf(today(habit()))));
        days.revalidate();days.repaint();
    }
}
