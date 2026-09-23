package dev.yoru.ui;

import dev.yoru.application.Analytics;
import dev.yoru.application.Tracker;
import dev.yoru.domain.Model.*;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.Locale;
import java.util.UUID;
import javax.swing.*;
import javax.swing.border.LineBorder;
import static dev.yoru.ui.Theme.*;

/**
 * The Today page: the focus session, the partner, the agenda, the statistics,
 * the heat map and the tracked activities.
 *
 * Moved out of YoruApp (#12), which built every page itself alongside the
 * navigation, the ticker and the vault. It reaches the window only through
 * {@link Shell}, as the Collection and Game pages do. The window's ticker still
 * drives it, by {@link #tick}, and a page change lets its live labels go, by
 * {@link #leave}.
 */
final class TodayPage {
    private final Shell shell;
    private JLabel timerLabel, statusLabel;
    private DailyGoal dailyGoal;
    /** The heat map's activity filter; null shows every activity. */
    private final AnkiCard ankiCard;
    /** Whether Today is the page on screen, so Anki time added in the background rebuilds it and nothing else. */
    private boolean shown;

    /**
     * Constructed with the window, before the window's own fields are set, so
     * the tracker and zone are read from the shell when needed, never here.
     */
    TodayPage(Shell shell) {
        this.shell = shell;
        ankiCard = new AnkiCard(new dev.yoru.anki.AnkiConnect()::read, this::tracker, this::zone,
            () -> { if (shown) shell.refresh(); }, () -> shell.show("Data"));
    }

    private Tracker tracker() { return shell.tracker(); }
    private ZoneId zone() { return shell.zone(); }

    /** One step of the window's ticker: the clock reads the time. */
    void tick(boolean animate, Session running, boolean onScreen) {
        ankiCard.syncConnection();
        if(timerLabel!=null&&onScreen)updateTimer();
        if(dailyGoal!=null&&onScreen)dailyGoal.update();
    }

    /** Another page is going on screen: nothing here needs the ticker until Today is built again. */
    void leave() {
        shown=false;
        dailyGoal=null;
        timerLabel=null;statusLabel=null;phaseBar=null;
    }

    void close() { ankiCard.disconnect(); leave(); }

    /**
     * Another vault is open. Anki time is added to the vault that was open when
     * Anki was connected, so connecting again is what agrees to add it here.
     */
    void vaultChanged() { ankiCard.disconnect(); }

    JPanel view() {
        shown=true;
        var tracker=tracker();
        var zone=zone();
        var p=stack();
        long open=tracker.state().tasks().stream().filter(t->!t.done()).count();
        // Anki's one line stands on the title's line (#85, #86): a glance at
        // it is a glance at the page's head, and at the foot of the page it
        // pushed the day past the bottom of a 1280×900 window.
        ankiCard.render();
        p.add(YoruApp.pageHeaderFor("Today","LOCAL VAULT · "+plural((int)open,"open task").toUpperCase(Locale.ROOT),ankiCard));

        // Two columns while there is room for both, stacked when there is not:
        // the companion card used to be a fixed 315 px in an EAST slot, which
        // broke the page below about 900 px rather than reflowing.
        //
        // What is next sits beside what is now: the agenda is the one thing a
        // running timer wants next to it.
        var agenda=schedulePreview();
        agenda.setName("today.agenda");
        var rail=stack();
        rail.add(agenda);
        glue(rail);
        p.add(new Columns(focusCard(),rail));
        gap(p,SPACE_XL);

        var daily=Analytics.daily(tracker.state(),null,zone,Instant.now());
        LocalDate today=LocalDate.now();
        long weekSeconds=0;
        for(int i=0;i<7;i++)weekSeconds+=daily.getOrDefault(today.minusDays(i),0L);
        var stats=new JPanel(new GridLayout(1,3,SPACE_LG,0));
        stats.setName("today.stats");
        stats.setAlignmentX(0);
        stats.setOpaque(false);
        stats.add(stat("TODAY",Analytics.duration(daily.getOrDefault(today,0L))));
        stats.add(week(stat("LAST 7 DAYS",Analytics.report(weekSeconds)),daily,today,
            tracker.state().settings().dailyGoalHours()));
        stats.add(stat("CURRENT STREAK",plural(Analytics.streak(daily,today),"day")));
        // The agenda before the statistics (#9): what comes next, then how it
        // has gone. The agenda is in the hero above, so that order still holds.
        p.add(stats);
        gap(p,SPACE_LG);
        // What is left of the day, side by side: the tasks it wants and the
        // habits still to tick off. Both are summaries; the pages that own them
        // hold the detail (#86).
        p.add(new Columns(tasksCard(today),HabitChecklist.card(tracker,()->shell.show("Today"),shell::error)));
        gap(p,SPACE_XL);
        return p;
    }

    /**
     * The focus session: the picker, the clock and the one control that starts
     * it.
     *
     * With no activities there is nothing to clock into, so the card says so and
     * offers the only thing that helps. Before this the picker was simply empty
     * and the clock button silently opened the new-activity dialog instead of
     * starting a session — a control that did something other than what it said.
     */
    private JPanel focusCard() {
        var tracker=tracker();
        var focus=card();
        phaseBar=null;
        var clock=shell.pomodoro();
        boolean pomodoro=clock!=null&&PomodoroClock.shown();
        // The open-ended timer or the pomodoro (#61), chosen where the clock is.
        if(clock!=null) {
            var mode=new Segmented("today.mode",pomodoro?1:0,"Timer","Pomodoro").compact();
            mode.onChange(i->{PomodoroClock.shown(i==1);shell.show("Today");});
            focus.add(cardHead(sectionHeader("FOCUS SESSION"),mode));
            // The head is a control's height rather than a caption's, so the
            // gap under it gives back what it took.
            gap(focus,SPACE_SM);
        } else {
            focus.add(sectionHeader("FOCUS SESSION"));
            gap(focus,SPACE_LG);
        }
        var activities=tracker.state().activities();
        var active=tracker.active();
        if(activities.isEmpty()) {
            focus.add(emptyState("No activities yet.","Create one to start a session.",
                button("+ Activity",shell::addActivity)));
            return focus;
        }
        var choose=plainCombo(new JComboBox<Activity>(activities.toArray(Activity[]::new)));
        // The card's width, not a number of its own: capped at 480 the picker
        // stopped 74 px short of the clock and the column below it, and two right
        // edges that nearly agree read as a mistake. The height stays on the
        // control ladder (PAD_V either side of a 16 px line).
        choose.setMaximumSize(new Dimension(Integer.MAX_VALUE,controlHeight()));
        choose.setAlignmentX(0);
        choose.getAccessibleContext().setAccessibleName("Activity to track");
        if(active!=null)
            for(int i=0;i<choose.getItemCount();i++)if(choose.getItemAt(i).id().equals(active.activityId()))choose.setSelectedIndex(i);
        choose.setEnabled(active==null);
        focus.add(choose);
        gap(focus,SPACE_LG);
        if(pomodoro) return pomodoroBody(focus,clock,choose);
        timerLabel=figure("00:00:00",TYPE_TIMER,TEXT);
        timerLabel.setName("today.timer");
        focus.add(timerLabel);
        gap(focus,SPACE_SM);
        statusLabel=bodyLabel("");
        focus.add(statusLabel);
        updateTimer();
        gap(focus,SPACE_LG);
        // Wrapping: at the window's minimum the four controls take two lines.
        var controls=wrappingRow();
        controls.add(accentButton(active==null?"▶  Clock in":"■  Clock out",()-> {
            if(tracker.active()==null) shell.perform(()->tracker.start(((Activity)choose.getSelectedItem()).id()));
            else clockOut();
        }));
        controls.add(button("+ Activity",shell::addActivity));
        controls.add(button("+ Log time",()->shell.timeDialog(false)));
        if(active!=null)controls.add(button("Edit timer",()->shell.editTime(active)));
        controls.setName("today.timer.controls");
        focus.add(controls);
        gap(focus,SPACE_LG);
        dailyGoal=new DailyGoal(tracker,zone());
        focus.add(dailyGoal);
        gap(focus,SPACE_LG);
        return focus;
    }

    /** The pomodoro in the focus card: the time left, the phase, its controls and the day's count (#61). */
    private JPanel pomodoroBody(JPanel focus,PomodoroClock clock,JComboBox<Activity> choose) {
        var tracker=tracker();
        var running=tracker.active();
        if(running==null&&clock.activity()!=null)
            for(int i=0;i<choose.getItemCount();i++)if(choose.getItemAt(i).id().equals(clock.activity()))choose.setSelectedIndex(i);
        if(clock.activity()==null&&choose.getSelectedItem() instanceof Activity a) clock.activity(a.id());
        choose.addActionListener(e->{if(choose.getSelectedItem() instanceof Activity a) clock.activity(a.id());});
        timerLabel=figure("25:00",TYPE_TIMER,TEXT);
        timerLabel.setName("today.pomodoro.left");
        focus.add(timerLabel);
        gap(focus,SPACE_SM);
        statusLabel=bodyLabel("");
        statusLabel.setName("today.pomodoro.phase");
        focus.add(statusLabel);
        gap(focus,SPACE_LG);
        var controls=wrappingRow();
        boolean fresh=clock.left().equals(clock.engine().plan().length(clock.phase()));
        var go=accentButton(clock.running()?"❚❚  Pause":fresh?"▶  Start":"▶  Resume",
            ()->shell.perform(()->{if(clock.running())clock.pause();else clock.start();}));
        go.setName("today.pomodoro.go");
        controls.add(go);
        var skip=button("Skip",()->shell.perform(clock::skip));
        skip.setName("today.pomodoro.skip");
        skip.setToolTipText(clock.phase()==dev.yoru.application.Pomodoro.Phase.WORK
            ?"End this work interval now; the time so far is kept":"End this break now");
        controls.add(skip);
        var more=button("+5 min",()->shell.perform(clock::extend));
        more.setName("today.pomodoro.extend");
        more.setToolTipText("Five more minutes on this interval");
        controls.add(more);
        var reset=ghost(button("Reset",()->shell.perform(clock::reset)));
        reset.setName("today.pomodoro.reset");
        reset.setToolTipText("Back to the first work interval");
        controls.add(reset);
        controls.setName("today.timer.controls");
        focus.add(controls);
        gap(focus,SPACE_LG);
        phaseBar=new JProgressBar(0,1000);
        phaseBar.setName("today.pomodoro.progress");
        phaseBar.setUI(new javax.swing.plaf.basic.BasicProgressBarUI());
        phaseBar.setBorderPainted(false);
        phaseBar.setBackground(LINE);
        phaseBar.setForeground(clock.phase()==dev.yoru.application.Pomodoro.Phase.WORK?ACCENT_TEXT:GOLD_TEXT);
        phaseBar.setAlignmentX(0);
        phaseBar.setPreferredSize(new Dimension(SPACE_XXL,SPACE_XS));
        phaseBar.setMaximumSize(new Dimension(Integer.MAX_VALUE,SPACE_XS));
        phaseBar.getAccessibleContext().setAccessibleName("Time through this interval");
        int count=clock.today();
        var tally=label(count==0?"No pomodoros yet today":plural(count,"pomodoro")+" today",TYPE_CAPTION,MUTED);
        tally.setName("today.pomodoro.count");
        focus.add(tally);
        gap(focus,SPACE_SM);
        focus.add(phaseBar);
        var notice=clock.notice();
        if(notice!=null) {
            gap(focus,SPACE_SM);
            var said=wrapping(notice,TYPE_LABEL,ACCENT_TEXT);
            said.setName("today.pomodoro.notice");
            focus.add(said);
        }
        gap(focus,SPACE_LG);
        updateTimer();
        return focus;
    }

    /** How many tasks today wants, and the first few of them, each ready to tick off. */
    private JPanel tasksCard(LocalDate today) {
        var tracker=tracker();
        var card=card();
        var open=tracker.state().tasks().stream()
            .filter(t->!t.done()&&t.workOn()!=null&&!t.workOn().isAfter(today))
            .sorted(java.util.Comparator.comparing(dev.yoru.domain.Model.Task::workOn)).toList();
        long overdue=open.stream().filter(t->t.workOn().isBefore(today)).count();
        card.add(cardHead(sectionHeader("TASKS TODAY"),
            label(open.isEmpty()?"all clear":open.size()+" left"+(overdue>0?" · "+overdue+" overdue":""),
                TYPE_CAPTION,overdue>0?GOLD_TEXT:MUTED)));
        gap(card,SPACE_SM);
        if(open.isEmpty()) {
            card.add(bodyLabel("Nothing due or planned for today."));
            gap(card,SPACE_SM);
        }
        // Three: enough to know what the day holds, few enough to stay a glance.
        for(var task:open.stream().limit(3).toList()) {
            var line=new JPanel(new BorderLayout(SPACE_SM,0));
            line.setOpaque(false);
            line.setAlignmentX(0);
            var check=new JCheckBox(task.title());
            check.setOpaque(false);
            check.setFont(labelFont());
            check.setToolTipText(task.title());
            check.setName("today.task."+task.id());
            check.getAccessibleContext().setAccessibleName(task.title()+", not done");
            check.addActionListener(e->shell.perform(()->tracker.taskStatus(task.id(),
                dev.yoru.domain.Model.TaskStatus.DONE)));
            line.add(check,BorderLayout.CENTER);
            if(task.workOn().isBefore(today))
                line.add(label(DateText.date(task.workOn()),TYPE_CAPTION,GOLD_TEXT),BorderLayout.EAST);
            line.setMaximumSize(new Dimension(Integer.MAX_VALUE,line.getPreferredSize().height));
            card.add(line);
        }
        gap(card,SPACE_SM);
        var all=ghost(button(open.size()>3?"See all "+open.size()+" →":"Open Tasks →",()->shell.show("Tasks")));
        all.setName("today.tasks.all");
        card.add(all);
        return card;
    }

    /**
     * The heat ramp, keyed: the six tiers, the over-goal rainbow, and what the
     * colours mean.
     *
     * Both charts colour by time recorded, so both carry the same key — the
     * chart without one left a reader guessing what the lightest and the darkest
     * chip stood for. The chips are outlined because the lightest tier is very
     * nearly the card it sits on. The Data page's chart draws this too.
     */
    static JPanel heatLegend(LocalDate day,int goalHours) {
        var legend=wrappingRow();
        legend.add(label("LESS",TYPE_CAPTION,MUTED));
        for(Color c:HEAT) legend.add(swatch(c));
        var rainbow=swatch(Heatmap.overGoal(day));
        rainbow.setToolTipText("Goal beaten");
        legend.add(rainbow);
        legend.add(label("MORE · scaled to your "+goalHours
            +"h goal · rainbow = goal beaten",TYPE_CAPTION,MUTED));
        return legend;
    }

    /**
     * One chip of the heat ramp.
     *
     * The hairline is what makes the lightest tier a chip: Sakura's first heat
     * step is #F3E2E8 on a #FFF8FA card, and with no edge it read as a swatch
     * that had not been drawn rather than the pale end of the scale.
     */
    private static JPanel swatch(Color fill) {
        var chip=new JPanel();
        chip.setBackground(fill);
        chip.setPreferredSize(new Dimension(SPACE_LG,SPACE_LG));
        chip.setBorder(new LineBorder(LINE,HAIRLINE));
        return chip;
    }


    /**
     * Two cards side by side, or stacked when the window is too narrow for both.
     *
     * The threshold is met by a layout swap rather than a fixed width on one of
     * the children, so the pair reflows instead of overflowing.
     */

    private JPanel schedulePreview() {
        var tracker=tracker();
        var zone=zone();
        var box = card();
        var top = wrappingRow();
        top.add(sectionHeader("TODAY · SCHEDULE"));
        top.add(button("Expand calendar ↗", () -> shell.show("Schedule")));
        top.add(button("+ Plan block", () -> shell.timeDialog(true)));
        box.add(top);
        var day = LocalDate.now();
        var blocks = tracker.state().blocks().stream().filter(b -> b.start().isBefore(day.plusDays(1).atStartOfDay(zone).toInstant())
                && b.end().isAfter(day.atStartOfDay(zone).toInstant())).sorted(Comparator.comparing(ScheduleBlock::start)).toList();
        if (blocks.isEmpty()) {
            gap(box,SPACE_LG);
            box.add(emptyState("No blocks planned today.","Drag on the Schedule grid, or plan one here.",null));
        }
        var shown = blocks.stream().limit(3).toList();
        for (var b : shown) {
            var line = new JPanel(new BorderLayout(SPACE_XL, 0));
            line.setOpaque(false);
            // The rule divides one block from the next; under the last one it
            // divides it from the card's own edge, which is not a division.
            boolean ends = b == shown.getLast() && blocks.size() <= 3;
            line.setBorder(ends ? listEnd() : listRow());
            line.add(label(b.start().atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm")) + " — "
                    + b.end().atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm")), TYPE_BODY, GOLD_TEXT), BorderLayout.WEST);
            line.add(shortenable(shell.activityName(b.activityId()), TYPE_BODY, TEXT), BorderLayout.CENTER);
            line.add(label(String.format("%.0f%% matched",100*Analytics.adherence(tracker.state(),b,Instant.now())), TYPE_CAPTION, MUTED), BorderLayout.EAST);
            box.add(line);
        }
        if (blocks.size()>3) box.add(label("+ " + (blocks.size()-3) + " more in calendar",TYPE_CAPTION,MUTED));
        return box;
    }

    private JPanel stat(String title,String value) {
        var p=stack();
        p.setBorder(new javax.swing.border.EmptyBorder(SPACE_SM,SPACE_XL,SPACE_SM,SPACE_XL));
        // The same signpost every other card carries: these are cards, and a
        // muted caption made the three of them read as a footnote to the page.
        p.add(sectionHeader(title));
        gap(p,SPACE_MD);
        p.add(label(value,TYPE_FIGURE,TEXT));
        return p;
    }

    /**
     * The week behind the figure: one bar a day, in the heat map's own colours.
     *
     * The tile said nine hours and nothing about how they fell — four long days
     * and three empty ones read the same as seven even ones. The bars are the
     * chart on the Data page at a glance, and the same colour means the same
     * thing on both.
     */
    private JPanel week(JPanel tile,java.util.Map<LocalDate,Long> daily,LocalDate today,int goalHours) {
        gap(tile,SPACE_MD);
        // Bars of their own width rather than a seventh of the tile: stretched
        // across it they were seven wide slabs a few pixels apart, which read
        // as a row of chips and not as a week. Narrow ones, taller than they
        // are wide, are a chart even at this size.
        int height=grow(SPACE_XXL);
        // Wide enough for the widest initial, measured rather than assumed: a
        // bar's own width is a spacing step, and on a machine whose caption face
        // draws a broader W than this one's, the letter under the bar was cut.
        int letters=0;
        for(int i=0;i<7;i++)
            letters=Math.max(letters,label(initial(today.minusDays(i).getDayOfWeek()),
                TYPE_CAPTION,MUTED).getPreferredSize().width);
        int wide=Math.max(grow(SPACE_MD),letters+SPACE_XS);
        int width=7*wide+6*SPACE_SM;
        var days=new JPanel(new GridLayout(1,7,SPACE_SM,0));
        days.setOpaque(false);
        days.setAlignmentX(0);
        days.setMaximumSize(new Dimension(width,height));
        var initials=new JPanel(new GridLayout(1,7,SPACE_SM,0));
        initials.setOpaque(false);
        initials.setAlignmentX(0);
        long most=3600;
        for(int i=6;i>=0;i--) most=Math.max(most,daily.getOrDefault(today.minusDays(i),0L));
        for(int i=6;i>=0;i--) {
            LocalDate day=today.minusDays(i);
            long seconds=daily.getOrDefault(day,0L);
            var column=stack();
            glue(column);
            // A day with nothing recorded is a hairline on the floor, as it is
            // in the fourteen-day chart: a stub reads as a bar that failed.
            int tall=seconds==0?HAIRLINE:Math.max(RING,(int)(height*seconds/most));
            var bar=new Theme.Bar(seconds==0?LINE:Heatmap.colour(day,seconds,goalHours));
            bar.setMaximumSize(new Dimension(wide,tall));
            bar.setPreferredSize(new Dimension(wide,tall));
            bar.setToolTipText(day+" · "+Analytics.report(seconds));
            column.add(bar);
            days.add(column);
            // The day under its bar, as the fourteen-day chart numbers its own:
            // seven unlabelled bars leave the reader counting backwards from
            // whichever end they guess is today. Today's is the one in body ink.
            var initial=label(initial(day.getDayOfWeek()),TYPE_CAPTION,day.equals(today)?TEXT:MUTED);
            initial.setHorizontalAlignment(SwingConstants.CENTER);
            initial.getAccessibleContext().setAccessibleName(day.getDayOfWeek().getDisplayName(
                java.time.format.TextStyle.FULL,java.util.Locale.getDefault()));
            initials.add(initial);
        }
        tile.add(days);
        gap(tile,SPACE_XS);
        initials.setMaximumSize(new Dimension(width,initials.getPreferredSize().height));
        tile.add(initials);
        return tile;
    }
    /** A weekday in one letter, in the reader's own language. */
    private static String initial(java.time.DayOfWeek day) {
        return day.getDisplayName(java.time.format.TextStyle.NARROW,java.util.Locale.getDefault())
            .toUpperCase(java.util.Locale.getDefault());
    }

    private void updateTimer() {
        var clock=shell.pomodoro();
        if(clock!=null&&PomodoroClock.shown()&&phaseBar!=null) {
            long seconds=Math.max(0,clock.left().toSeconds());
            timerLabel.setText(String.format("%02d:%02d",seconds/60,seconds%60));
            statusLabel.setText(clock.phaseName()+(clock.running()?"":" · "+(clock.left().equals(clock.engine().plan()
                .length(clock.phase()))?"ready":"paused")));
            phaseBar.setValue((int)Math.round(clock.progress()*1000));
            phaseBar.getAccessibleContext().setAccessibleDescription(statusLabel.getText()+", "+timerLabel.getText()+" left");
            return;
        }
        var a=tracker().active();
        timerLabel.setText(a==null?"00:00:00":Analytics.duration(Math.max(0,Duration.between(a.start(),tracker().now()).getSeconds())));
        statusLabel.setText(a==null?"OPEN-ENDED · ready when you are":"● CLOCKED IN · "+shell.activityName(a.activityId()));
    }
    /** How far through the pomodoro's interval, while it is on screen. */
    private JProgressBar phaseBar;

    private void clockOut() {
        var tracker=tracker();
        var end=new DateTimeField(Instant.now(),zone(),"End");var form=stack();
        form.add(label("Finish now, or select when you actually stopped.",TYPE_LABEL,TEXT));gap(form,SPACE_MD);form.add(end);
        var now=new JCheckBox("Use the exact current time",true);now.setOpaque(false);now.setForeground(TEXT);form.add(now);
        if(Dialogs.confirm(shell.owner(),form,"Clock out","Clock out"))shell.perform(()->{
            // Said out loud. A session vanishing with no explanation looks like
            // the app lost it, which is the one thing this must not feel like.
            if(!tracker.stop(now.isSelected()?Instant.now():end.value()))
                Dialogs.info(shell.owner(),"That session was under the minimum, so it was not recorded.\n\n"
                    +"Change the minimum in Settings if short sessions should count.");
        });
    }
}
