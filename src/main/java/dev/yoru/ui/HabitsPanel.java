package dev.yoru.ui;
import dev.yoru.application.Analytics;
import dev.yoru.application.Tracker;
import dev.yoru.domain.Model.*;
import javax.swing.*;
import java.awt.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import static dev.yoru.ui.Theme.*;

/**
 * Daily completion and elapsed abstinence, kept separate from study-time rewards.
 *
 * A factory rather than a panel: this page used to nest its own JScrollPane
 * inside the one the window already gives every page, which produced two
 * scrollbars and a unit increment that belonged to neither. It now returns its
 * stack and lets the window scroll it, like every other page.
 */
final class HabitsPanel {
    /** One formatter for every "when" this page prints, so they cannot disagree. */
    private static final DateTimeFormatter WHEN=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private HabitsPanel() { }

    static JPanel view(Tracker tracker,Runnable refresh) {
        var body=stack();
        body.add(YoruApp.pageHeaderFor("Habits","DAILY CHECK-OFFS · TIME SINCE"));
        if(tracker.state().habits().isEmpty()) {
            body.add(emptyState("No habits yet.",
                "Check off a day, or count the time since you stopped.",null));
            gap(body,SPACE_LG);
        }
        var actions=row();
        actions.add(button("+ Daily check-off",()->create(tracker,refresh,HabitKind.DAILY,body)));
        actions.add(button("+ Time since",()->create(tracker,refresh,HabitKind.TIME_SINCE,body)));
        body.add(actions);
        gap(body,SPACE_LG);
        for(var h:tracker.state().habits()) {
            body.add(habitCard(tracker,refresh,h));
            gap(body,SPACE_MD);
        }
        return body;
    }

    private static JPanel habitCard(Tracker tracker,Runnable refresh,Habit habit) {
        var card=card();
        card.add(label(habit.name(),TYPE_HEADING,TEXT));
        gap(card,SPACE_SM);
        if(habit.kind()==HabitKind.DAILY) daily(tracker,refresh,habit,card);
        else since(tracker,refresh,habit,card);
        return card;
    }

    private static void daily(Tracker tracker,Runnable refresh,Habit habit,JPanel card) {
        var today=LocalDate.now(ZoneId.of(habit.zone()));
        // "day streak" is one compound, so the count never takes a plural —
        // the plural helper stays for the phrase where "day" stands alone.
        // The count is the sentence, so it is drawn in body ink and the accent
        // is saved for the filled day cells below, where it marks which days
        // are done. Drawn whole in the accent this line measured 3.0:1 on Linen
        // and 3.5:1 on Sakura — under AA for a 13 px label, and the numbers are
        // the only reason to read it.
        card.add(label(habit.streak(today)+" day streak · "
            +Theme.plural(habit.checkIns().size(),"day")+" checked off",TYPE_LABEL,TEXT));
        gap(card,SPACE_SM);
        // The app's own check control rather than a stock JCheckBox: a stock
        // box is 14 px of platform chrome sitting among drawn cells and buttons,
        // and its label baseline missed the row rhythm. Same filled-when-on
        // treatment as the day cells, so "done" looks like one thing here.
        boolean checked=habit.checkIns().contains(today);
        var check=button("Done today",()->act(card,refresh,()->tracker.checkIn(habit.id(),today,!checked)));
        check.setName("habit.done");
        check.getAccessibleContext().setAccessibleName(checked?"Done today, checked":"Done today, not checked");
        selected(check,checked);
        card.add(check);
        gap(card,SPACE_MD);
        // A filled cell is a day done and an outlined one is a day not done: the
        // state reads at a glance, without a glyph to decode, and each cell is
        // still a button with a name a screen reader can read out.
        var days=new JPanel(new GridLayout(2,14,SPACE_XS,SPACE_XS));
        days.setOpaque(false);
        days.setAlignmentX(0);
        days.setMaximumSize(new Dimension(14*SPACE_XXL+13*SPACE_XS,2*SPACE_XXL+SPACE_XS));
        for(int i=27;i>=0;i--) {
            var date=today.minusDays(i);
            boolean done=habit.checkIns().contains(date);
            var cell=selected(button("",()->act(card,refresh,()->tracker.checkIn(habit.id(),date,!done))),done);
            cell.setPreferredSize(new Dimension(SPACE_XXL,SPACE_XXL));
            cell.setToolTipText(date+(done?" · done · click to undo":" · click to check off"));
            cell.getAccessibleContext().setAccessibleName(date+(done?" completed":" not completed"));
            days.add(cell);
        }
        card.add(days);
        gap(card,SPACE_SM);
        // The zone id is a developer's string, not the user's: it says nothing
        // the day cells do not already say.
        card.add(bodyLabel("Last 28 days · click a day to correct it"));
    }

    private static void since(Tracker tracker,Runnable refresh,Habit habit,JPanel card) {
        var elapsed=label("",TYPE_FIGURE,CYAN);
        card.add(elapsed);
        gap(card,SPACE_SM);
        Runnable update=()-> {
            long sec=Math.max(0,Duration.between(habit.starts().getLast(),Instant.now()).getSeconds());
            elapsed.setText(String.format("%dd  %02dh  %02dm  %02ds",sec/86400,sec/3600%24,sec/60%60,sec%60));
        };
        update.run();
        // Nothing ticks for a card that is not on screen.
        var timer=new Timer(1000,e->update.run());
        elapsed.addHierarchyListener(e->{if(elapsed.isShowing())timer.start();else timer.stop();});
        var zone=ZoneId.of(habit.zone());
        card.add(bodyLabel("Since "+habit.starts().getLast().atZone(zone).toLocalDateTime().format(WHEN)));
        gap(card,SPACE_MD);
        var buttons=row();
        var again=button("Start again",()-> {
            if(Dialogs.confirm(card,"Start a new period now? Your previous periods stay in history.","Start again","Start again"))
                act(card,refresh,()->tracker.restartHabit(habit.id()));
        });
        again.setToolTipText("End this period and start a new one now");
        buttons.add(again);
        var edit=button("Edit start date",()-> {
            var input=new DateTimeField(habit.starts().getLast(),zone);
            while(Dialogs.confirm(card,input,"Edit current period start","Save")) {
                try{tracker.editHabitStart(habit.id(),input.value());refresh.run();break;}
                catch(Exception error){Dialogs.error(card,"Check date",error.getMessage());}
            }
        });
        edit.setToolTipText("Correct when this period began");
        buttons.add(edit);
        var history=button("History",()-> {
            var rows=stack();
            var starts=habit.starts();
            for(int i=0;i<starts.size();i++) {
                var start=starts.get(i);
                var end=i+1<starts.size()?starts.get(i+1):Instant.now();
                var line=row();
                line.add(label(start.atZone(zone).toLocalDateTime().format(WHEN),TYPE_BODY,TEXT));
                line.add(label(Analytics.report(Duration.between(start,end).getSeconds()),TYPE_BODY,MUTED));
                if(i==starts.size()-1)line.add(label("current",TYPE_CAPTION,CYAN));
                rows.add(line);
            }
            // A bounded, scrolling window on the rows: a long history used to
            // hand JOptionPane an unbounded stack, which grew the dialog past
            // the screen and put OK out of reach.
            var scroll=new JScrollPane(rows);
            scroll.setBorder(controlBorder(LINE));
            scroll.setPreferredSize(new Dimension(560,220));
            Dialogs.info(card,"Your history",scroll);
        });
        history.setToolTipText("Every period this tracker has recorded");
        buttons.add(history);
        card.add(buttons);
    }

    private static void create(Tracker tracker,Runnable refresh,HabitKind kind,Component owner) {
        var form=stack();
        var name=new JTextField(24);
        form.add(new JLabel("Name"));form.add(name);
        var since=new DateTimeField(Instant.now(),ZoneId.systemDefault());
        if(kind==HabitKind.TIME_SINCE){gap(form,SPACE_MD);form.add(new JLabel("Started at · choose a past date and time"));form.add(since);}
        if(!Dialogs.confirm(owner,form,kind==HabitKind.DAILY?"New daily check-off":"New time-since tracker","Create"))return;
        act(owner,refresh,()->tracker.addHabit(name.getText(),kind,ZoneId.systemDefault(),
            kind==HabitKind.DAILY?null:since.value()));
    }

    private interface Action {void run()throws Exception;}

    /** Runs one tracker write, then repaints: the owner is the panel the dialog belongs over. */
    private static void act(Component owner,Runnable refresh,Action action) {
        try{action.run();refresh.run();}
        catch(Exception e){Dialogs.error(owner,e.getMessage());}
    }
}
