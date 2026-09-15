package dev.yoru.ui;

import dev.yoru.application.Analytics;
import dev.yoru.application.Encounters;
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
    private JLabel timerLabel, statusLabel, encounterLabel;
    private BuddyCard buddyCard;
    private TrainerScene trainerScene;
    /** The heat map's activity filter; null shows every activity. */
    private UUID heatActivity;

    /**
     * Constructed with the window, before the window's own fields are set, so
     * the tracker and zone are read from the shell when needed, never here.
     */
    TodayPage(Shell shell) { this.shell = shell; }

    private Tracker tracker() { return shell.tracker(); }
    private ZoneId zone() { return shell.zone(); }

    /** One step of the window's ticker: the partner and the trainer move, and the clock reads the time. */
    void tick(boolean animate, Session running, boolean onScreen) {
        if (buddyCard != null) buddyCard.tick(animate);
        if (trainerScene != null) trainerScene.advance(animate,
            running==null?0:Duration.between(running.start(),Instant.now()).getSeconds());
        if(timerLabel!=null&&onScreen)updateTimer();
    }

    /** Another page is going on screen: nothing here needs the ticker until Today is built again. */
    void leave() {
        buddyCard=null;trainerScene=null;encounterLabel=null;
        timerLabel=null;statusLabel=null;
    }

    /** A reset may remove the activity the heat map was filtered to. */
    void forgetActivityFilter() { heatActivity=null; }

    JPanel view() {
        var tracker=tracker();
        var zone=zone();
        var p=stack();
        long open=tracker.state().tasks().stream().filter(t->!t.done()).count();
        p.add(YoruApp.pageHeaderFor("Today","LOCAL VAULT · "+plural((int)open,"open task").toUpperCase(Locale.ROOT)));

        // Two columns while there is room for both, stacked when there is not:
        // the companion card used to be a fixed 315 px in an EAST slot, which
        // broke the page below about 900 px rather than reflowing.
        String portrait=WaifuCatalog.forTheme(tracker.state().settings());
        if (portrait!=null && !WaifuCatalog.imagesFor(portrait).isEmpty()) {
            p.add(new Hero(focusCard(),new WaifuPanel(portrait)));
            gap(p,SPACE_LG);
            p.add(companionColumn());
        } else {
            // No companion chosen: Today is the timer and the partner. Choosing
            // one, and any recommendation of one, lives in Settings (#3).
            p.add(new Hero(focusCard(),companionColumn()));
        }
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
        stats.add(stat("LAST 7 DAYS",Analytics.report(weekSeconds)));
        stats.add(stat("CURRENT STREAK",plural(Analytics.streak(daily,today),"day")));
        // The agenda before the statistics (#9): what comes next, then how it has gone.
        var agenda=schedulePreview();
        agenda.setName("today.agenda");
        p.add(agenda);
        gap(p,SPACE_LG);
        p.add(stats);
        gap(p,SPACE_LG);
        p.add(heatCard(today));
        gap(p,SPACE_LG);
        p.add(activitiesCard(today));
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
        focus.add(sectionHeader("FOCUS SESSION"));
        gap(focus,SPACE_LG);
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
        choose.setMaximumSize(new Dimension(Integer.MAX_VALUE,SPACE_XXL));
        choose.setAlignmentX(0);
        choose.getAccessibleContext().setAccessibleName("Activity to track");
        if(active!=null)
            for(int i=0;i<choose.getItemCount();i++)if(choose.getItemAt(i).id().equals(active.activityId()))choose.setSelectedIndex(i);
        choose.setEnabled(active==null);
        focus.add(choose);
        gap(focus,SPACE_LG);
        timerLabel=label("00:00:00",TYPE_TIMER,TEXT);
        focus.add(timerLabel);
        gap(focus,SPACE_SM);
        statusLabel=bodyLabel("");
        focus.add(statusLabel);
        updateTimer();
        gap(focus,SPACE_LG);
        // The trainer sits with the clock, since the clock is what drives it.
        trainerScene=new TrainerScene(tracker.state().settings().trainer()==TrainerId.MAY?"may":"brendan");
        focus.add(trainerScene);
        if (!trainerScene.hasTrainerArtwork()) {
            gap(focus,SPACE_SM);
            focus.add(bodyLabel("Trainer artwork is missing. Import your existing scene artwork in Settings to restore walking and running."));
            focus.add(button("Restore scene artwork", () -> shell.show("Settings")));
        }
        gap(focus,SPACE_LG);
        var controls=row();
        controls.add(accentButton(active==null?"▶  Clock in":"■  Clock out",()-> {
            if(tracker.active()==null) shell.perform(()->tracker.start(((Activity)choose.getSelectedItem()).id()));
            else clockOut();
        }));
        controls.add(button("+ Activity",shell::addActivity));
        controls.add(button("+ Log time",()->shell.timeDialog(false)));
        if(active!=null)controls.add(button("Edit timer",()->shell.editTime(active)));
        focus.add(controls);
        return focus;
    }

    /**
     * The companion, and the one line about the game that is not its business.
     *
     * The encounter countdown used to live inside the partner card under a name
     * that promised time together. The card is about the time you have studied;
     * what the game owes you is a caption underneath it.
     */
    private JPanel companionColumn() {
        // A card like the one on the left, so the hero pair reads as two columns
        // of equal standing — and the encounters caption is a line on the floor
        // of that card, inside its padding and on the card title's left edge,
        // rather than a caption floating in the gutter under a shorter card.
        var column=card();
        buddyCard=new BuddyCard(tracker(),zone(),
            ()->shell.show("Collection"),()->shell.show("Game"));
        // Its own height and no more: the slack above the caption belongs to the
        // floor, so the companion never grows a gap between its blocks.
        buddyCard.setMaximumSize(new Dimension(Integer.MAX_VALUE,buddyCard.getPreferredSize().height));
        column.add(buddyCard);
        glue(column);
        encounterLabel=subtitle("");
        column.add(encounterLabel);
        updateEncounterLine();
        return column;
    }


    /** The 52-week heat map, or the first-run state when there is nothing to draw. */
    private JPanel heatCard(LocalDate today) {
        var tracker=tracker();
        var heat=card();
        var top=row();
        top.add(sectionHeader("ACTIVITY · 52 WEEKS"));
        var filter=plainCombo(new JComboBox<String>());
        filter.addItem("All activities");
        tracker.state().activities().forEach(a->filter.addItem(a.name()));
        if(heatActivity!=null)filter.setSelectedItem(shell.activityName(heatActivity));
        filter.getAccessibleContext().setAccessibleName("Heat map activity filter");
        filter.addActionListener(e-> {
            int index=filter.getSelectedIndex();heatActivity=index==0?null:tracker.state().activities().get(index-1).id();shell.show("Today");
        }
        );
        top.add(filter);
        heat.add(top);
        var days=Analytics.daily(tracker.state(),heatActivity,zone(),Instant.now());
        // An empty 52x7 grid reads as a broken chart rather than as a first run.
        if(days.values().stream().noneMatch(seconds->seconds>0)) {
            gap(heat,SPACE_LG);
            heat.add(emptyState("No time recorded yet.","Your first session fills this in.",null));
            return heat;
        }
        heat.add(new Heatmap(days,today,tracker.state().settings().dailyGoalHours()));
        heat.add(heatLegend(today,tracker.state().settings().dailyGoalHours()));
        return heat;
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
        var legend=row();
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

    /** Every activity with today's time and the two controls that change it. */
    private JPanel activitiesCard(LocalDate today) {
        var tracker=tracker();
        var categories=card();
        categories.add(sectionHeader("TRACKED ACTIVITIES"));
        gap(categories,SPACE_MD);
        if(tracker.state().activities().isEmpty()) {
            categories.add(emptyState("No activities yet.","Create one to start a session.",null));
            return categories;
        }
        // One grid for the whole list, shared with the Data page: the controls
        // sit in their own column rather than after each row's text, so they line
        // up down the card instead of stepping along with the label lengths.
        var table=ActivityManager.activityTable();
        int row=0;
        for(var a:tracker.state().activities()) {
            long sec=Analytics.daily(tracker.state(),a.id(),zone(),Instant.now()).getOrDefault(today,0L);
            // Management sits with the picker's own list, by identity: the buttons
            // carry the activity's id, so renaming one can never move another's time.
            var rename=button("Rename",()->ActivityManager.rename(shell.owner(),tracker,a,()->shell.show("Today")));
            rename.setName("activity.rename."+a.id());
            // "Delete", not "Remove": this is the control that can destroy the
            // recorded time, and the dialog it opens says so.
            var remove=button("Delete",()->ActivityManager.remove(shell.owner(),tracker,a,()->shell.show("Today")));
            remove.setName("activity.remove."+a.id());
            boolean timing=!ActivityManager.canRemove(tracker,a.id());
            remove.setEnabled(!timing);
            remove.setToolTipText(timing?"Clock out before deleting this activity":"Delete this activity");
            ActivityManager.activityRow(table,row++,label(a.name(),TYPE_PROSE,TEXT),
                label(Analytics.duration(sec)+(a.targetMinutes()==0?" · open-ended":" · target "+a.targetMinutes()+"m"),TYPE_BODY,MUTED),
                ActivityManager.targetButton(shell.owner(),tracker,a,()->shell.show("Today")),rename,remove);
        }
        categories.add(table);
        return categories;
    }

    /**
     * Two cards side by side, or stacked when the window is too narrow for both.
     *
     * The threshold is met by a layout swap rather than a fixed width on one of
     * the children, so the pair reflows instead of overflowing.
     */
    private static final class Hero extends JPanel {
        private static final int STACK_BELOW=760;
        private final JComponent left,right;
        private boolean stacked;
        Hero(JComponent left,JComponent right) {
            this.left=left; this.right=right;
            setOpaque(false);
            setAlignmentX(0);
            apply(false);
            addComponentListener(new ComponentAdapter() {
                @Override public void componentResized(ComponentEvent e) { apply(getWidth()<STACK_BELOW); }
            });
        }
        private void apply(boolean stack) {
            if(getComponentCount()>0&&stack==stacked)return;
            stacked=stack;
            removeAll();
            if(stack) {
                // A vertical box, not a 2x1 grid: a grid forces both rows to the
                // same height, so the shorter card was padded with empty space
                // instead of being the size its own content asks for.
                setLayout(new BoxLayout(this,BoxLayout.Y_AXIS));
                holdToOwnHeight(left);
                holdToOwnHeight(right);
                add(left);
                add(Box.createVerticalStrut(SPACE_LG));
                add(right);
            } else {
                setLayout(new GridLayout(1,2,SPACE_LG,0));
                add(left); add(right);
            }
            revalidate(); repaint();
        }

        /** In the vertical box each card takes its own height; the box pads nothing. */
        private static void holdToOwnHeight(JComponent card) {
            card.setMaximumSize(new Dimension(Integer.MAX_VALUE,card.getPreferredSize().height));
        }
    }

    private JPanel schedulePreview() {
        var tracker=tracker();
        var zone=zone();
        var box = card();
        var top = row();
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
        for (var b : blocks.stream().limit(3).toList()) {
            var line = new JPanel(new BorderLayout(SPACE_XL, 0));
            line.setOpaque(false); line.setBorder(listRow());
            line.add(label(b.start().atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm")) + " — "
                    + b.end().atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm")), TYPE_BODY, GOLD_TEXT), BorderLayout.WEST);
            line.add(label(shell.activityName(b.activityId()), TYPE_BODY, TEXT), BorderLayout.CENTER);
            line.add(label(String.format("%.0f%% matched",100*Analytics.adherence(tracker.state(),b,Instant.now())), TYPE_CAPTION, MUTED), BorderLayout.EAST);
            box.add(line);
        }
        if (blocks.size()>3) box.add(label("+ " + (blocks.size()-3) + " more in calendar",TYPE_CAPTION,MUTED));
        return box;
    }

    private JPanel stat(String title,String value) {
        var p=card();
        p.add(label(title,TYPE_CAPTION,MUTED));
        gap(p,SPACE_MD);
        p.add(label(value,TYPE_FIGURE,TEXT));
        return p;
    }
    private void updateTimer() {
        var a=tracker().active();
        timerLabel.setText(a==null?"00:00:00":Analytics.duration(Duration.between(a.start(),Instant.now()).getSeconds()));
        updateEncounterLine();
        statusLabel.setText(a==null?"OPEN-ENDED · ready when you are":"● CLOCKED IN · "+shell.activityName(a.activityId()));
    }
    /** How close the next encounter is, as a caption under the companion it will join. */
    private void updateEncounterLine() {
        if(encounterLabel==null)return;
        var state=tracker().state();
        long waiting=Encounters.available(state);
        encounterLabel.setText(waiting>0?plural((int)waiting,"encounter")+" waiting in Collection"
            :Encounters.towardNext(state)/60+" / 30m to the next encounter");
    }
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
