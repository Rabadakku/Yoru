package dev.yoru.ui;
import dev.yoru.application.Analytics;
import dev.yoru.application.Tracker;
import dev.yoru.domain.Model.*;
import javax.swing.*;
import java.awt.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Locale;
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

    /** How far back a daily habit's grid reaches: four weeks of columns. */
    private static final int WEEKS=4;

    private HabitsPanel() { }

    static JPanel view(Tracker tracker,Runnable refresh) {
        var body=stack();
        body.add(YoruApp.pageHeaderFor("Habits","DAILY CHECK-OFFS · TIME SINCE",
            button("+ Daily check-off",()->create(tracker,refresh,HabitKind.DAILY,body)),
            button("+ Time since",()->create(tracker,refresh,HabitKind.TIME_SINCE,body))));
        if(tracker.state().habits().isEmpty()) {
            body.add(emptyState("No habits yet.",
                "Check off a day, or count the time since you stopped.",null));
            gap(body,SPACE_LG);
        }
        for(var h:tracker.state().habits()) {
            body.add(habitCard(tracker,refresh,h));
            gap(body,SPACE_MD);
        }
        return body;
    }

    private static JPanel habitCard(Tracker tracker,Runnable refresh,Habit habit) {
        var card=card();
        var rename=ghost(button("Rename",()-> {
            var input=new JTextField(habit.name(),24);
            input.getAccessibleContext().setAccessibleName("Habit name");
            while(Dialogs.confirm(card,input,"Rename habit","Save")) {
                try {tracker.renameHabit(habit.id(),input.getText());refresh.run();break;}
                catch(Exception error){Dialogs.error(card,error.getMessage());}
            }
        }));
        rename.setName("habit.rename."+habit.id());
        rename.getAccessibleContext().setAccessibleName("Rename "+habit.name());
        var delete=ghost(button("Delete",()-> {
            if(Dialogs.confirm(card,"Delete this habit and its history? Other habits and study records stay unchanged. A vault backup is kept first.",
                "Delete "+habit.name(),"Delete habit"))
                act(card,refresh,()->tracker.deleteHabit(habit.id()));
        }));
        delete.setName("habit.delete."+habit.id());
        delete.getAccessibleContext().setAccessibleName("Delete "+habit.name());
        // The one thing this card is for sits with its name, where the eye
        // already is, and the two that manage the card follow it.
        var name=shortenable(habit.name(),TYPE_HEADING,TEXT);
        if(habit.kind()==HabitKind.DAILY) {
            var today=LocalDate.now(ZoneId.of(habit.zone()));
            boolean checked=habit.checkIns().contains(today);
            // The app's own check control rather than a stock JCheckBox: a stock
            // box is 14 px of platform chrome sitting among drawn cells and
            // buttons, and its label baseline missed the row rhythm.
            var check=button("Done today",()->act(card,refresh,()->tracker.checkIn(habit.id(),today,!checked)));
            check.setName("habit.done");
            check.getAccessibleContext().setAccessibleName(checked?"Done today, checked":"Done today, not checked");
            selected(check,checked);
            card.add(cardHead(name,check,rename,delete));
            gap(card,SPACE_MD);
            daily(tracker,refresh,habit,card);
        } else {
            card.add(cardHead(name,rename,delete));
            gap(card,SPACE_MD);
            since(tracker,refresh,habit,card);
        }
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
        gap(card,SPACE_MD);
        // A filled cell is a day done and an outlined one is a day not done: the
        // state reads at a glance, without a glyph to decode, and each cell is
        // still a button with a name a screen reader can read out.
        //
        // The cells stand in weekday columns, four weeks deep, under the
        // initials of the days. Laid out as two rows of fourteen they said how
        // many days were done and nothing about which: whether the gaps are
        // weekends or the middle of the week is the question a check-off habit
        // actually raises, and it is a column apart here instead of a count on
        // the fingers. The week starts on the day the vault's own setting says,
        // so this grid and the task calendar break their weeks in the same place.
        var settings=tracker.state().settings();
        var weekStart=settings.weekStartsOn();
        var first=settings.weekOf(today).minusWeeks(WEEKS-1);
        int side=grow(SPACE_XXL);
        int width=7*side+6*SPACE_XS;
        var initials=new JPanel(new GridLayout(1,7,SPACE_XS,0));
        initials.setOpaque(false);
        initials.setAlignmentX(0);
        for(int i=0;i<7;i++) {
            var day=weekStart.plus(i);
            var initial=label(day.getDisplayName(TextStyle.NARROW,Locale.getDefault()).toUpperCase(Locale.getDefault()),
                TYPE_SECTION,MUTED);
            initial.setHorizontalAlignment(SwingConstants.CENTER);
            // The narrow name repeats itself — two of seven days are "S" — so
            // the column says the day in full to a screen reader.
            initial.getAccessibleContext().setAccessibleName(day.getDisplayName(TextStyle.FULL,Locale.getDefault()));
            initials.add(initial);
        }
        initials.setMaximumSize(new Dimension(width,initials.getPreferredSize().height));
        card.add(initials);
        gap(card,SPACE_XS);
        var days=new JPanel(new GridLayout(WEEKS,7,SPACE_XS,SPACE_XS));
        days.setOpaque(false);
        days.setAlignmentX(0);
        days.setMaximumSize(new Dimension(width,WEEKS*side+(WEEKS-1)*SPACE_XS));
        for(int i=0;i<WEEKS*7;i++) {
            var date=first.plusDays(i);
            // The rest of this week has not happened yet: an empty column keeps
            // the grid square without offering a day to check off in advance.
            if(date.isAfter(today)) {
                var blank=new JPanel();
                blank.setOpaque(false);
                days.add(blank);
                continue;
            }
            boolean done=habit.checkIns().contains(date);
            var cell=selected(button("",()->act(card,refresh,()->tracker.checkIn(habit.id(),date,!done))),done);
            cell.setName("habit.day."+date);
            // Today is outlined whether or not it is done, so the row says
            // where now is without counting back from the end. The ring is
            // chosen against the cell's own fill: the accent on an accent-filled
            // done cell would be a ring nobody can see.
            if(date.equals(today)) cell.setBorder(controlBorder(ringFor(cell.getBackground())));
            cell.setPreferredSize(new Dimension(side,side));
            cell.setToolTipText(date+(done?" · done · click to undo":" · click to check off"));
            cell.getAccessibleContext().setAccessibleName(date+(done?" completed":" not completed"));
            days.add(cell);
        }
        card.add(days);
        gap(card,SPACE_SM);
        // The zone id is a developer's string, not the user's: it says nothing
        // the day cells do not already say.
        card.add(bodyLabel("Last "+WEEKS+" weeks · click a day to correct it"));
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
        var history=button("History",()->Dialogs.info(card,"Your history",history(tracker,habit.id(),zone,refresh)));
        history.setToolTipText("Every period this tracker has recorded");
        buttons.add(history);
        card.add(buttons);
    }

    /**
     * Every period a time-since tracker has recorded, each with Edit and Delete (#21).
     *
     * Only the current period's start could be corrected before, so a mistyped
     * restart further back stayed wrong for good. Periods are addressed by the
     * instant they start, so an edit from a history that has since changed is
     * refused rather than landing on another period, and the list rebuilds
     * itself after each change in the dialog that is still open.
     *
     * A bounded, scrolling window on the rows: a long history used to hand
     * JOptionPane an unbounded stack, which grew the dialog past the screen and
     * put OK out of reach.
     */
    static JComponent history(Tracker tracker,java.util.UUID habitId,ZoneId zone,Runnable refresh) {
        var rows=stack();
        var scroll=new JScrollPane(rows);
        scroll.setBorder(controlBorder(LINE));
        scroll.setPreferredSize(new Dimension(640,240));
        Runnable[] rebuild=new Runnable[1];
        rebuild[0]=()-> {
            rows.removeAll();
            var habit=tracker.state().habits().stream().filter(h->h.id().equals(habitId)).findFirst().orElse(null);
            if(habit==null) rows.add(bodyLabel("This tracker no longer exists."));
            else {
                var starts=habit.starts();
                for(int i=0;i<starts.size();i++) {
                    var start=starts.get(i);
                    var end=i+1<starts.size()?starts.get(i+1):Instant.now();
                    String when=start.atZone(zone).toLocalDateTime().format(WHEN);
                    var line=row();
                    line.add(label(when,TYPE_BODY,TEXT));
                    line.add(label(Analytics.report(Duration.between(start,end).getSeconds()),TYPE_BODY,MUTED));
                    if(i==starts.size()-1)line.add(label("current",TYPE_CAPTION,CYAN));
                    var edit=button("Edit",()-> {
                        var input=new DateTimeField(start,zone,"Start");
                        while(Dialogs.confirm(rows,input,"Edit period start","Save")) {
                            try{tracker.editHabitPeriod(habitId,start,input.value());refresh.run();rebuild[0].run();return;}
                            catch(Exception error){Dialogs.error(rows,"Check date",error.getMessage());}
                        }
                    });
                    edit.setName("habit.period.edit."+start.toEpochMilli());
                    edit.getAccessibleContext().setAccessibleName("Edit the period starting "+when);
                    var delete=button("Delete",()-> {
                        if(!Dialogs.confirmDestructive(rows,"Delete the period starting "+when+"? The time it covered joins its neighbour. A vault backup is kept first.",
                            "Delete period","Delete")) return;
                        try{tracker.deleteHabitPeriod(habitId,start);refresh.run();rebuild[0].run();}
                        catch(Exception error){Dialogs.error(rows,error.getMessage());}
                    });
                    delete.setName("habit.period.delete."+start.toEpochMilli());
                    delete.getAccessibleContext().setAccessibleName("Delete the period starting "+when);
                    delete.setEnabled(starts.size()>1);
                    delete.setToolTipText(starts.size()>1?"Join this period to its neighbour":"A tracker keeps at least one period");
                    line.add(edit);line.add(delete);
                    rows.add(line);
                }
            }
            rows.revalidate();rows.repaint();
        };
        rebuild[0].run();
        return scroll;
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
