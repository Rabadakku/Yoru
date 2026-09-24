package dev.yoru.ui;

import dev.yoru.application.Analytics;
import dev.yoru.application.SessionCsv;
import dev.yoru.application.Tracker;
import dev.yoru.domain.Model.*;
import java.awt.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import static dev.yoru.ui.Theme.*;

/**
 * The Data page: the activities and their totals, the year's heat map, Anki's
 * month, the fortnight's chart, where the time went, every session, and the
 * exports and imports.
 *
 * Moved out of YoruApp (#45), as Today and Settings were (#12). It keeps the
 * heat map's filter and the focus mix's window, and reaches the window through
 * {@link Shell}, and through {@code rebuild} after an import replaces the
 * vault, since an imported palette rebuilds the whole window.
 */
final class DataPage {
    private final Shell shell;
    private final Runnable rebuild;
    /** Whether the window has closed, so a late action on this page does nothing. */
    private final java.util.function.BooleanSupplier closed;
    /** How tall the fourteen-day chart's plot stands, goal line and all. */
    private static final int CHART_HEIGHT=120;
    private final DateTimeFormatter dateTime=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    /** The activity the heat map is filtered to, or null for all of them. */
    private UUID heatActivity;
    /** Window for the focus-distribution chart, in days; 0 means all time. */
    private int mixDays=7;

    DataPage(Shell shell, Runnable rebuild, java.util.function.BooleanSupplier closed) {
        this.shell = shell;
        this.rebuild = rebuild;
        this.closed = closed;
    }

    private Tracker tracker() { return shell.tracker(); }

    private ZoneId zone() { return shell.zone(); }

    private JTable table(String[] headers,Object[][] rows) {
        var t=new JTable(new DefaultTableModel(rows,headers) {
            public boolean isCellEditable(int r,int c) {
                return false;
            }
        }
        );
        // One table styling for the whole app: the shared row height, grid and
        // selection colours, rather than three near-copies that disagreed.
        plainTable(t);
        t.setBackground(PANEL);
        t.setForeground(TEXT);
        t.setSelectionForeground(CYAN);
        t.setFont(bodyFont());
        t.getTableHeader().setBackground(LINE);
        t.getTableHeader().setForeground(TEXT);
        t.getTableHeader().setFont(bodyFont());
        return t;
    }

    /**
     * The 52-week heat map, on the page that owns the numbers.
     *
     * It was the largest thing on Today, which is a glance at the day rather
     * than a year in review (#86).
     */
    private JPanel heatCard(LocalDate today) {
        var heat=card();
        var top=row();
        top.add(sectionHeader("ACTIVITY · 52 WEEKS"));
        var filter=plainCombo(new JComboBox<String>());
        filter.addItem("All activities");
        tracker().state().activities().forEach(a->filter.addItem(a.name()));
        if(heatActivity!=null)filter.setSelectedItem(shell.activityName(heatActivity));
        filter.getAccessibleContext().setAccessibleName("Heat map activity filter");
        filter.addActionListener(e-> {
            int index=filter.getSelectedIndex();heatActivity=index==0?null:tracker().state().activities().get(index-1).id();shell.show("Data");
        }
        );
        top.add(filter);
        heat.add(top);
        var days=Analytics.daily(tracker().state(),heatActivity,zone(),shell.now());
        // An empty 52x7 grid reads as a broken chart rather than as a first run.
        if(days.values().stream().noneMatch(seconds->seconds>0)) {
            gap(heat,SPACE_LG);
            heat.add(emptyState("No time recorded yet.","Your first session fills this in.",null));
            return heat;
        }
        heat.add(new Heatmap(days,today,tracker().state().settings().dailyGoalHours(),
            tracker().state().settings().weekStartsOn()));
        heat.add(TodayPage.heatLegend(today,tracker().state().settings().dailyGoalHours()));
        return heat;
    }

    /**
     * Anki's reviews, day by day, on the page that holds the numbers (#85).
     *
     * Today keeps one line; the month of history that used to sit under it, as
     * seven lines of text, is drawn here as the chart it always was.
     */
    private JPanel ankiHistory(Anki anki) {
        var last=anki.last();
        var card=card();
        card.add(cardHead(sectionHeader("ANKI REVIEWS · LAST 30 DAYS"),
            label("profile “"+last.profile()+"” · read "+AnkiCard.ago(last.fetchedAt(),tracker().now()),TYPE_CAPTION,MUTED)));
        gap(card,SPACE_LG);
        var today=shell.today();
        long most=1;
        for(int i=0;i<30;i++) most=Math.max(most,last.days().getOrDefault(today.minusDays(i),0L));
        int floor=grow(CHART_HEIGHT);
        var bars=new JPanel(new GridLayout(1,30,SPACE_XS/2,0)) {
            @Override public Dimension getPreferredSize() { return tall(super.getPreferredSize()); }
            @Override public Dimension getMinimumSize() { return tall(super.getMinimumSize()); }
            @Override public Dimension getMaximumSize() { return tall(super.getMaximumSize()); }
            private Dimension tall(Dimension d) { return new Dimension(d.width,floor); }
        };
        bars.setOpaque(false);
        bars.setAlignmentX(0);
        for(int i=29;i>=0;i--) {
            var day=today.minusDays(i);
            long reviews=last.days().getOrDefault(day,0L);
            var column=new JPanel(new BorderLayout());
            column.setOpaque(false);
            var bar=new JPanel();
            bar.setBackground(reviews==0?LINE:CYAN);
            int height=reviews==0?HAIRLINE*2:(int)Math.max(HAIRLINE*2,floor*reviews/most);
            bar.setPreferredSize(new Dimension(SPACE_SM,height));
            bar.setToolTipText(DateText.date(day)+" · "+plural((int)reviews,"review"));
            column.add(bar,BorderLayout.SOUTH);
            bars.add(column);
        }
        card.add(bars);
        gap(card,SPACE_SM);
        var scale=row();
        scale.add(label(DateText.date(today.minusDays(29)),TYPE_CAPTION,MUTED));
        scale.add(label("most in a day: "+plural((int)most,"review"),TYPE_CAPTION,MUTED));
        scale.add(label("today",TYPE_CAPTION,MUTED));
        card.add(scale);
        gap(card,SPACE_MD);
        long tracked=dev.yoru.application.AnkiTime.recordedOn(tracker().state(),today,zone(),tracker().now());
        card.add(bodyLabel(tracked==0?"No Anki time has been added to your tracked time today."
            :Analytics.report(tracked)+" of Anki time is in your tracked time today, under “"
             +dev.yoru.application.AnkiTime.ACTIVITY+"”. Correct or delete those sessions like any other."));
        return card;
    }

    /** The page. */
    JPanel view() {
        // An activity the heat map was filtered to may since have been deleted or reset away.
        if(heatActivity!=null&&tracker().state().activities().stream().noneMatch(a->a.id().equals(heatActivity))) heatActivity=null;
        var p=stack();
        // An ellipsis on every action that opens a dialog, and none on the ones
        // that do not: the label is then the promise of what happens next.
        p.add(YoruApp.pageHeaderFor("Data","DURATION · HISTORY · EXPORT",
            named(button("+ Log time",()->shell.timeDialog(false)),"sessions.log"),
            ghost(button("Export sessions CSV…",this::export)),
            ghost(button("Export vault JSON…",this::exportVault)),
            ghost(button("Import vault JSON…",this::importVault))));
        // The activity manager lives here as well as beside the Today picker: this
        // page is where the session counts and durations a removal would act on
        // are already on screen.
        p.add(ActivityManager.activities(tracker(),shell.owner(),()->shell.show("Data")));
        gap(p,SPACE_LG);
        p.add(heatCard(shell.today()));
        gap(p,SPACE_LG);
        var anki=tracker().state().anki();
        if(anki.enabled()&&anki.last()!=null) { p.add(ankiHistory(anki)); gap(p,SPACE_LG); }

        var days=Analytics.daily(tracker().state(),null,zone(),shell.now());
        var chart=card();
        chart.add(sectionHeader("LAST 14 DAYS · HOURS"));
        gap(chart,SPACE_LG);
        boolean anyTime=false;
        for(int i=0;i<14;i++)anyTime|=days.getOrDefault(shell.today().minusDays(i),0L)>0;
        if(!anyTime) {
            // Fourteen empty bars is not a chart, it is a rendering fault.
            chart.add(emptyState("No time recorded yet.","Your first session fills this in.",null));
        } else {
            int goal=tracker().state().settings().dailyGoalHours();
            long max=3600;
            for(int i=0;i<14;i++)max=Math.max(max,days.getOrDefault(shell.today().minusDays(i),0L));
            // The goal is part of the scale, so the fortnight is drawn against
            // what the days were for and not only against their own best one: a
            // quiet fortnight used to stretch to fill the card and look like a
            // busy one. The dashes across the bars are where the goal falls, so
            // the chart answers "did I get there" without any arithmetic.
            long goalSeconds=Math.max(1,goal*3600L);
            max=Math.max(max,goalSeconds);
            int floor=grow(CHART_HEIGHT);
            int span=floor-SPACE_XL; // air over the goal line, so it reads as part of the chart
                                     // rather than as a rule under the durations above it
            int goalLine=(int)(span*goalSeconds/max);
            // The chart keeps its full height whatever the fortnight held, so
            // the goal line has somewhere to be drawn even when no day reached
            // it, and two visits to this page compare like with like.
            var bars=new JPanel(new GridLayout(1,14,SPACE_SM,0)) {
                @Override public Dimension getPreferredSize() { return tall(super.getPreferredSize()); }
                @Override public Dimension getMinimumSize() { return tall(super.getMinimumSize()); }
                @Override public Dimension getMaximumSize() { return tall(super.getMaximumSize()); }
                private Dimension tall(Dimension d) { return new Dimension(d.width,floor); }
                @Override protected void paintComponent(Graphics g) {
                    super.paintComponent(g);
                    var ink=(Graphics2D)g.create();
                    ink.setColor(LINE);
                    // A miter limit under 1 is rejected outright, and dashes are
                    // the only thing this stroke ever draws.
                    ink.setStroke(new BasicStroke(HAIRLINE,BasicStroke.CAP_BUTT,BasicStroke.JOIN_ROUND,
                        HAIRLINE,new float[]{SPACE_XS,SPACE_XS},0));
                    int y=getHeight()-goalLine;
                    ink.drawLine(0,y,getWidth(),y);
                    ink.dispose();
                }
            };
            bars.setOpaque(false);
            bars.setToolTipText("The dashed line is your "+goal+"h daily goal");
            // The day numbers sit in their own row under the floor, and the
            // durations in their own row over it, so the bars share one
            // baseline and one ceiling instead of each starting and ending
            // wherever its own column's label left off.
            // Two lines of room whatever the text size: a column is narrow, and
            // at 200% "2h 45m" wraps. A grid asks its cells how tall they are
            // before it has told them how wide they will be, so a wrapping
            // caption reports one line and loses its second — the band is sized
            // for the wrap up front, and the captions sit on its floor.
            int caption=label("0m",TYPE_CAPTION,MUTED).getPreferredSize().height;
            var durations=new JPanel(new GridLayout(1,14,SPACE_SM,0)) {
                @Override public Dimension getPreferredSize() { return band(super.getPreferredSize()); }
                @Override public Dimension getMinimumSize() { return band(super.getMinimumSize()); }
                @Override public Dimension getMaximumSize() { return band(super.getMaximumSize()); }
                private Dimension band(Dimension d) { return new Dimension(d.width,2*caption); }
            };
            durations.setOpaque(false);
            durations.setAlignmentX(0);
            var dayNumbers=new JPanel(new GridLayout(1,14,SPACE_SM,0));
            dayNumbers.setOpaque(false);
            dayNumbers.setAlignmentX(0);
            for(int i=13;i>=0;i--) {
                LocalDate d=shell.today().minusDays(i);
                long sec=days.getOrDefault(d,0L);
                boolean none=sec==0;
                var cell=stack();
                glue(cell);
                // A day with nothing on it is a hairline on the baseline rather
                // than the two-pixel stub of the lightest tier it used to be:
                // that stub read as a bar that had failed to draw, so a quiet
                // week looked like a broken chart instead of a quiet week. The
                // rest are coloured by the time recorded, not by the column's
                // index, so the chart agrees with the heat map above it.
                int height=none?HAIRLINE:Math.max(RING,(int)(span*sec/max));
                var bar=new Theme.Bar(none?LINE:Heatmap.colour(d,sec,goal));
                bar.setPreferredSize(new Dimension(25,height));
                bar.setMaximumSize(new Dimension(60,height));
                bar.setToolTipText(d+" · "+Analytics.report(sec));
                cell.add(bar);
                bars.add(cell);
                // A day with nothing on it says nothing: fourteen captions, half
                // of them "0m", were a row of noise over a row of hairlines.
                // Wraps onto two lines when a larger text size leaves the column too narrow (#31).
                var duration=wrapping(none?"":Analytics.report(sec),TYPE_CAPTION,MUTED);
                duration.setVerticalAlignment(SwingConstants.BOTTOM);
                durations.add(duration);
                // Today's number is the one in body ink: the chart then says
                // which end is now without counting the columns.
                dayNumbers.add(label(""+d.getDayOfMonth(),TYPE_CAPTION,d.equals(shell.today())?TEXT:MUTED));
            }
            bars.setAlignmentX(0);
            bars.setBorder(new javax.swing.border.MatteBorder(0,0,HAIRLINE,0,LINE));
            chart.add(durations);
            gap(chart,SPACE_SM);
            chart.add(bars);
            gap(chart,SPACE_SM);
            chart.add(dayNumbers);
            gap(chart,SPACE_MD);
            chart.add(TodayPage.heatLegend(shell.today(),tracker().state().settings().dailyGoalHours()));
        }
        p.add(chart);

        // Where the time went, not just how much (#6). The 14-day chart above
        // and the heat map both total every activity together, so a week spent
        // entirely on one subject and a week split four ways draw identically.
        gap(p,SPACE_LG);
        var asOf=shell.now();
        var mixFrom=mixDays==0?Instant.EPOCH:asOf.minus(Duration.ofDays(mixDays));
        var mix=card();
        var mixHead=row();
        mixHead.add(sectionHeader("FOCUS DISTRIBUTION"));
        for(int span:new int[]{7,30,0}) {
            var pick=button(span==0?"All time":"Last "+span+" days",()->{mixDays=span;shell.show("Data");});
            // The current range keeps its filled accent rather than being
            // disabled, so the active choice stays the most prominent.
            mixHead.add(selected(pick,mixDays==span));
        }
        mix.add(mixHead);
        gap(mix,SPACE_MD);
        long inWindow=Analytics.recorded(tracker().state(),mixFrom,asOf,asOf);
        if(mixDays>0) {
            var previousFrom=mixFrom.minus(Duration.ofDays(mixDays));
            var swing=Analytics.change(inWindow,Analytics.recorded(tracker().state(),previousFrom,mixFrom,asOf));
            mix.add(label(Analytics.duration(inWindow)+" · "+(swing.isPresent()
                ?String.format("%.0f%% %s than the previous %d days",Math.abs(swing.getAsDouble())*100,
                    swing.getAsDouble()<0?"less":"more",mixDays)
                :"nothing in the "+mixDays+" days before to compare against"),TYPE_CAPTION,MUTED));
            gap(mix,SPACE_MD);
        }
        var slices=Analytics.distribution(tracker().state(),mixFrom,asOf,asOf);
        if(slices.isEmpty()) mix.add(emptyState("No time recorded yet.","Your first session fills this in.",null));
        else {
            var mixRows=new ArrayList<FocusBars.Row>();
            for(var slice:slices)mixRows.add(new FocusBars.Row(shell.activityName(slice.activityId()),slice.seconds(),slice.share()));
            mix.add(new FocusBars(mixRows));
        }
        p.add(mix);
        gap(p,SPACE_LG);

        var now=shell.now();
        var all=tracker().state().sessions();
        var sessions=all.stream().filter(x->Analytics.counts(x,tracker().state(),now))
            .sorted(Comparator.comparing(Session::start).reversed()).toList();
        int excluded=all.size()-sessions.size();
        Object[][] rows=new Object[sessions.size()][4];
        for(int i=0;i<sessions.size();i++) {
            var s=sessions.get(i);
            rows[i]=new Object[] {
                shell.activityName(s.activityId()),s.start().atZone(zone()).format(dateTime),s.end()==null?"Running":s.end().atZone(zone()).format(dateTime),Analytics.duration(Duration.between(s.start(),s.end()==null?shell.now():s.end()).getSeconds())
            }
            ;
        }
        // Header row with nothing under it reads as a bug; the empty state says
        // what fills it instead.
        var t=sessions.isEmpty()?null:table(new String[] {
            "Activity","Start (local)","End (local)","Duration"
        }
        ,rows);
        if(t==null) p.add(emptyState("No sessions recorded yet.","Clock in, or log time by hand.",null));
        else {
            var sc=new JScrollPane(t);
            sc.setBorder(Theme.controlBorder(LINE));
            p.add(sc);
        }
        if(excluded>0) {
            gap(p,SPACE_SM);
            // Said out loud rather than silently dropped: the sessions are still
            // in the vault and in the JSON export, they just do not count.
            p.add(bodyLabel(plural(excluded,"session")+" under "
                +tracker().state().settings().minSessionSeconds()/60+"m "
                +(excluded==1?"is":"are")+" not counted, and not listed above."));
            p.add(bodyLabel("They were recorded before the minimum applied at clock-out. "
                +"New short sessions are no longer stored at all."));
            gap(p,SPACE_SM);
            var clear=row();
            clear.add(button("Delete them",()->{
                var warning=stack();
                warning.add(label("Delete "+plural(excluded,"short session")+"?",TYPE_HEADING,TEXT));
                gap(warning,SPACE_MD);
                warning.add(label("They count toward nothing already. This deletes them for good.",TYPE_BODY,MUTED));
                if(!Dialogs.confirmDestructive(shell.owner(),warning,"Delete short sessions","Delete"))return;
                shell.perform(()->{int gone=tracker().purgeShortSessions();
                    Dialogs.info(shell.owner(),plural(gone,"short session")+" deleted.");shell.show("Data");});
            }));
            p.add(clear);
        }
        gap(p,SPACE_MD);
        p.add(new SessionActions(tracker(),t,sessions,shell::editTime,
            ()->shell.show("Data"),closed,failure->Dialogs.error(shell.owner(),failure.getMessage())));
        gap(p,SPACE_XL);
        Object[][] dailyRows=days.entrySet().stream().sorted(Map.Entry.<LocalDate,Long>comparingByKey().reversed()).map(e->new Object[] {
            e.getKey(),Analytics.duration(e.getValue())
        }
        ).toArray(Object[][]::new);
        p.add(sectionHeader("Daily totals · "+zone().getId()));
        gap(p,SPACE_MD);
        if(dailyRows.length==0) p.add(emptyState("No time recorded yet.","Your first session fills this in.",null));
        else {
            var totals=new JScrollPane(table(new String[] {
                "Date","Time"
            }
            ,dailyRows));
            // Without this the pane paints the look-and-feel's own edge, which
            // is white on every dark theme; the sessions pane above takes the
            // shared one, and a table has nowhere to show it but here.
            totals.setBorder(Theme.controlBorder(LINE));
            p.add(totals);
        }
        return p;
    }

    private void export() {
        Path file=Dialogs.saveFile(shell.owner(),"Export sessions","yoru-sessions.csv");
        if(file==null)return;
        shell.perform(()->{
            Files.writeString(file,SessionCsv.write(tracker().state()));
            Dialogs.info(shell.owner(),"Export saved. This CSV is unencrypted; share it only where you intend.");
        });
    }
    /** The whole vault as readable JSON: the debugging and acceptance-check tool. */
    private void exportVault() {
        Path file=Dialogs.saveFile(shell.owner(),"Export vault","yoru-vault-"+shell.today()+".json");
        if(file==null)return;
        shell.perform(()->{
            Files.writeString(file,dev.yoru.persistence.PortableVault.export(tracker().state(),shell.now()));
            Dialogs.info(shell.owner(),"Vault exported to "+file.getFileName()+".\n\n"
                +"This file is NOT encrypted. It holds everything in your vault as plain\n"
                +"readable text. Keep it where you keep the vault.");
        });
    }

    /**
     * Replaces the whole vault from an export. Parsed and validated in full
     * before anything is written, so a file that is wrong anywhere leaves the
     * open vault untouched.
     */
    private void importVault() {
        Path file=Dialogs.chooseFile(shell.owner(),"Choose a Yoru vault export","Yoru export (JSON)","json");
        if(file==null)return;
        final State incoming;
        try { incoming=dev.yoru.persistence.PortableVault.parse(Files.readString(file)); }
        catch(Exception e) { shell.error(new Exception("That file could not be read as a Yoru export.\n\n"+e.getMessage())); return; }

        var current=tracker().state();
        var warning=stack();
        warning.add(label("Replace everything in this workspace?",TYPE_HEADING,TEXT));gap(warning,SPACE_MD);
        warning.add(label(file.getFileName().toString(),TYPE_BODY,CYAN));gap(warning,SPACE_MD);
        warning.add(label("Incoming: "+summary(incoming),TYPE_BODY,TEXT));
        warning.add(label("Replacing: "+summary(current),TYPE_BODY,MUTED));gap(warning,SPACE_MD);
        warning.add(bodyLabel("Everything currently in this workspace is discarded. Export it first"));
        warning.add(bodyLabel("if you might want it back."));
        if(!Dialogs.confirmDestructive(shell.owner(),warning,"Import vault","Replace everything"))return;
        shell.perform(()->{
            tracker().restore(incoming);
            Dialogs.info(shell.owner(),"Vault replaced from "+file.getFileName()+".");
            rebuild.run();
        });
    }

    private static String summary(State state) {
        return state.activities().size()+" activities, "+state.sessions().size()+" sessions, "
            +state.tasks().size()+" tasks, "+state.habits().size()+" habits, "
            +state.notes().pages().size()+" pages";
    }
}
