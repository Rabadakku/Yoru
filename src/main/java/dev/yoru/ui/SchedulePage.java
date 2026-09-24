package dev.yoru.ui;

import dev.yoru.application.Analytics;
import dev.yoru.application.Tracker;
import dev.yoru.domain.Model.*;
import java.awt.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Comparator;
import java.util.Locale;
import java.util.UUID;
import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import static dev.yoru.ui.Theme.*;

/**
 * The Schedule page: the week grid, planned beside recorded, the blocks planned
 * this week, and the weekly repeats that can be changed one week at a time.
 *
 * Moved out of YoruApp (#45), as Today and Settings were (#12). It keeps the
 * week on screen, and reaches the window through {@link Shell} for everything
 * the window owns: running a change, the time editors and the other pages.
 */
final class SchedulePage {
    private final Shell shell;
    /** The first day of the week on screen; null until the page is first built. */
    private LocalDate week;

    SchedulePage(Shell shell) { this.shell = shell; }

    private Tracker tracker() { return shell.tracker(); }

    private ZoneId zone() { return shell.zone(); }

    /**
     * Keeps the week on screen aligned when the week's first day changes: the
     * grid computed under the old preference would straddle two weeks.
     */
    void weekStartsOn(Settings next) { week = next.weekOf(shell.today()); }

    /** Activity used for a block dragged straight onto the grid. */
    private UUID activityForBlock() {
        var activities=tracker().state().activities();
        if(activities.isEmpty()) throw new IllegalArgumentException("Create an activity before planning time.");
        var running=tracker().active();
        if(running!=null) return running.activityId();
        return activities.getFirst().id();
    }

    /**
     * The editor behind a double-click on the week grid (#32, #33).
     *
     * One dialog for both shapes, because from the calendar's point of view they
     * are the same object — a coloured span you want to correct or remove. What
     * differs is which tracker call it lands on, and that a recorded session is
     * described as time you spent rather than time you planned.
     */
    private void editOnGrid(UUID id,boolean recorded) {
        var state=tracker().state();
        UUID activityId; Instant from,to;
        if(recorded) {
            var session=state.sessions().stream().filter(s->s.id().equals(id)).findFirst().orElse(null);
            if(session==null||session.end()==null) return;
            activityId=session.activityId(); from=session.start(); to=session.end();
        } else {
            var block=state.blocks().stream().filter(b->b.id().equals(id)).findFirst().orElse(null);
            if(block==null) return;
            activityId=block.activityId(); from=block.start(); to=block.end();
        }
        var start=new DateTimeField(from,zone(),"Start");
        var end=new DateTimeField(to,zone(),"End");
        var form=stack();
        form.add(label(recorded?"Recorded session":"Planned block",TYPE_HEADING,TEXT));gap(form,SPACE_SM);
        form.add(label(shell.activityName(activityId)+" · "+recorded("recorded","planned",recorded),TYPE_CAPTION,MUTED));gap(form,SPACE_MD);
        var startCaption=new JLabel("Start · "+zone());startCaption.setLabelFor(start);
        var endCaption=new JLabel("End · "+zone());endCaption.setLabelFor(end);
        form.add(startCaption);form.add(start);gap(form,SPACE_MD);
        form.add(endCaption);form.add(end);gap(form,SPACE_MD);
        form.add(label(recorded?"Correcting a session moves the time it contributed to your totals."
            :"A planned block is a plan; deleting it records nothing.",TYPE_CAPTION,MUTED));

        int choice=Dialogs.choose(shell.owner(),form,recorded?"Edit recorded session":"Edit planned block",
            "Save","Delete","Cancel");
        if(choice==0) shell.perform(()->{
            if(recorded) tracker().editSession(id,activityId,start.value(),end.value());
            else tracker().editBlock(id,activityId,start.value(),end.value());
            shell.show("Schedule");
        });
        else if(choice==1) {
            var warning=stack();
            warning.add(label(recorded?"Delete this recorded session?":"Delete this planned block?",TYPE_HEADING,TEXT));
            gap(warning,SPACE_MD);
            warning.add(label(recorded?"The time it recorded is removed from your totals."
                :"Nothing recorded is affected.",TYPE_BODY,MUTED));
            if(!Dialogs.confirmDestructive(shell.owner(),warning,recorded?"Delete session":"Delete block","Delete")) return;
            shell.perform(()->{
                if(recorded) tracker().deleteSession(id); else tracker().deleteBlock(id);
                shell.show("Schedule");
            });
        }
    }

    private static String recorded(String yes,String no,boolean recorded){ return recorded?yes:no; }

    /** The page, for the week on screen. */
    JPanel view() {
        if(week==null) week=tracker().state().settings().weekOf(shell.today());
        var p=stack();
        // Wrapping: with larger text the five controls take two lines (#31).
        var nav=wrappingRow();
        // A bare arrow is not a name: both get a tooltip and an accessible one.
        var back=button("←",()->{week=week.minusWeeks(1);shell.show("Schedule");});
        back.setToolTipText("Previous week");
        back.getAccessibleContext().setAccessibleName("Previous week");
        nav.add(back);
        nav.add(label(DateText.span(week,week.plusDays(6)),TYPE_HEADING,TEXT));
        var forward=button("→",()->{week=week.plusWeeks(1);shell.show("Schedule");});
        forward.setToolTipText("Next week");
        forward.getAccessibleContext().setAccessibleName("Next week");
        nav.add(forward);
        nav.add(button("This week",()->{
            week=tracker().state().settings().weekOf(shell.today());shell.show("Schedule");
        }));
        // What the page makes goes on its title's line; what moves the grid
        // stays with the grid it moves.
        p.add(YoruApp.pageHeaderFor("Schedule","RECORDED SESSIONS · PLANNED BLOCKS · WEEKLY TEMPLATE",
            named(button("+ Plan block",()->shell.timeDialog(true)),"schedule.plan"),
            named(ghost(button("Weekly template…",()->WeeklyTemplate.open(shell.owner(),tracker(),()->shell.show("Schedule")))),"schedule.template")));
        p.add(nav);
        gap(p,SPACE_MD);

        var grid=new ScheduleGrid(tracker().state(),week,zone(),shell.now(),new ScheduleGrid.Edits() {
            public void create(Instant start,Instant end) { shell.perform(()->tracker().plan(activityForBlock(),start,end)); }
            public void update(UUID id,Instant start,Instant end) {
                var existing=tracker().state().blocks().stream().filter(b->b.id().equals(id)).findFirst().orElse(null);
                if(existing!=null) shell.perform(()->tracker().editBlock(id,existing.activityId(),start,end));
            }
            public void updateSession(UUID id,Instant start,Instant end) {
                var existing=tracker().state().sessions().stream().filter(x->x.id().equals(id)).findFirst().orElse(null);
                if(existing!=null) shell.perform(()->tracker().editSession(id,existing.activityId(),start,end));
            }
            public void open(UUID id,boolean recorded) { editOnGrid(id,recorded); }
            public void moveRepeat(UUID ruleId,LocalDate week,Instant start,Instant end) {
                RepeatWeek.moveOnGrid(shell,ruleId,week,start,end);
            }
            public void openRepeat(UUID ruleId,LocalDate week) { RepeatWeek.open(shell,ruleId,week); }
            public boolean repeatsEditable() { return true; }
        });
        var frame=new JPanel(new BorderLayout());
        frame.setOpaque(true);
        frame.setBackground(PANEL);
        frame.setBorder(new CompoundBorder(controlBorder(LINE),new EmptyBorder(SPACE_MD,SPACE_MD,SPACE_MD,SPACE_MD)));
        frame.setAlignmentX(0);
        frame.add(grid,BorderLayout.CENTER);
        p.add(frame);
        gap(p,SPACE_LG);

        // Four swatches instead of six colour words: the thing being described
        // is a shape, so showing it beats naming it.
        var legend=row();
        var sample=ScheduleGrid.colour(0);
        legend.add(legendItem(new Mark(sample,Mark.SOLID),"recorded"));
        legend.add(legendItem(new Mark(sample,Mark.OUTLINE),"planned"));
        legend.add(legendItem(new Mark(sample,Mark.DASHED),"every week"));
        legend.add(legendItem(new Mark(DANGER,Mark.NOW),"now"));
        p.add(legend);
        gap(p,SPACE_LG);

        // The blocks themselves still need a list to be edited or removed from,
        // until the grid supports direct manipulation.
        var planned=tracker().state().blocks().stream()
            .filter(b->b.start().isBefore(week.plusDays(7).atStartOfDay(zone()).toInstant())
                    && b.end().isAfter(week.atStartOfDay(zone()).toInstant()))
            .sorted(Comparator.comparing(ScheduleBlock::start)).toList();
        var list=card();
        list.add(sectionHeader("PLANNED THIS WEEK"));
        gap(list,SPACE_MD);
        if(planned.isEmpty())
            list.add(emptyState("No blocks planned this week.","Recorded time still appears above; plan a block to compare against it.",null));
        for(var b:planned) {
            // No fixed height: the row's own border sizes it, so it cannot clip
            // a longer activity name.
            var line=new JPanel(new BorderLayout(SPACE_LG,0));
            line.setOpaque(false);
            // Under the last block the rule divides it from the card's edge
            // rather than from another block, and reads as a row lost.
            line.setBorder(b==planned.getLast()?listEnd():listRow());
            var when=b.start().atZone(zone());
            // A locale-stable day and a 24-hour time: the grid, the list and the
            // editor all say the same thing about when a block starts.
            line.add(named(label(when.getDayOfWeek().getDisplayName(TextStyle.SHORT,Locale.ENGLISH)+" "
                +when.format(DateTimeFormatter.ofPattern("HH:mm"))+" – "+b.end().atZone(zone()).format(DateTimeFormatter.ofPattern("HH:mm")),TYPE_CAPTION,GOLD_TEXT),
                "block.when."+b.id()),BorderLayout.WEST);
            // The name gives way and the match figure stays whole: one label cut
            // "…" through both, and the figure was the part lost.
            var what=new JPanel();
            what.setLayout(new BoxLayout(what,BoxLayout.X_AXIS));
            what.setOpaque(false);
            what.add(shortenable(shell.activityName(b.activityId()),TYPE_BODY,TEXT));
            what.add(label(" · "+String.format("%.0f%% matched",100*Analytics.adherence(tracker().state(),b,shell.now())),TYPE_BODY,TEXT));
            what.add(Box.createHorizontalGlue());
            line.add(what,BorderLayout.CENTER);
            var actions=row();
            // Named for a screen reader: a column of "Edit" buttons says nothing about which block each edits.
            String spoken=shell.activityName(b.activityId())+" "+when.getDayOfWeek().getDisplayName(TextStyle.FULL,Locale.ENGLISH)
                +" "+when.format(DateTimeFormatter.ofPattern("HH:mm"));
            var edit=named(ghost(button("Edit",()->shell.editBlock(b))),"block.edit."+b.id());
            edit.getAccessibleContext().setAccessibleName("Edit planned "+spoken);
            actions.add(edit);
            actions.add(named(ghost(button("Delete",()->{
                if(Dialogs.confirmDestructive(shell.owner(),"Delete this planned block?","Delete block","Delete"))
                    shell.perform(()->tracker().deleteBlock(b.id()));
            })),"block.delete."+b.id()));
            ((JButton)actions.getComponent(actions.getComponentCount()-1)).getAccessibleContext()
                .setAccessibleName("Delete planned "+spoken);
            line.add(actions,BorderLayout.EAST);
            list.add(line);
        }
        p.add(list);
        gap(p,SPACE_LG);
        // The weekly template's blocks for this week, each able to be skipped
        // or moved on its own (#59) without the pointer.
        if(!tracker().state().recurring().isEmpty()) {
            p.add(RepeatWeek.card(shell,week));
            gap(p,SPACE_LG);
        }

        var note=card();
        note.add(sectionHeader("PLANNED ≠ REQUIRED",GOLD));
        gap(note,SPACE_MD);
        note.add(bodyLabel("Matched time = work on that activity inside its scheduled block."));
        gap(note,SPACE_SM);
        note.add(bodyLabel("An empty block is information, not a judgment. You can adjust tomorrow."));
        p.add(note);
        return p;
    }

    /**
     * One legend sample, drawn rather than described: a filled block, an
     * outline, a dashed outline, or the now rule.
     */
    private static final class Mark extends JPanel {
        static final int SOLID=0, OUTLINE=1, DASHED=2, NOW=3;
        private final Color colour;
        private final int kind;
        Mark(Color colour,int kind) {
            this.colour=colour; this.kind=kind;
            setOpaque(false);
            var size=new Dimension(SPACE_LG,SPACE_LG);
            setPreferredSize(size); setMaximumSize(size); setAlignmentX(0);
            getAccessibleContext().setAccessibleName(switch(kind) {
                case SOLID->"recorded time"; case OUTLINE->"planned block";
                case DASHED->"weekly repeat"; default->"the current time";
            });
        }
        @Override protected void paintComponent(Graphics graphics) {
            var g=(Graphics2D)graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            int w=getWidth(),h=getHeight();
            if(kind==NOW) {
                g.setColor(colour);
                g.fillRect(0,h/2-RING/2,w,RING);
            } else if(kind==SOLID) {
                g.setColor(colour);
                g.fillRoundRect(0,2,w-1,h-5,RADIUS,RADIUS);
            } else {
                if(kind==DASHED) g.setStroke(new BasicStroke(HAIRLINE,BasicStroke.CAP_BUTT,
                    BasicStroke.JOIN_MITER,10,new float[]{4,4},0));
                g.setColor(colour);
                g.drawRoundRect(0,2,w-1,h-5,RADIUS,RADIUS);
            }
            g.dispose();
        }
    }

    /** A legend entry: the mark itself, then the word for it. */
    private static JPanel legendItem(JComponent mark,String text) {
        var item=tightRow();
        item.add(mark);
        item.add(label(text,TYPE_CAPTION,MUTED));
        return item;
    }
}
