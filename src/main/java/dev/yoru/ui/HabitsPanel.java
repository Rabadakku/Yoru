package dev.yoru.ui;
import dev.yoru.application.Analytics;
import dev.yoru.application.AnkiStreak;
import dev.yoru.application.HabitStats;
import dev.yoru.application.Tracker;
import dev.yoru.domain.Model.*;
import javax.swing.*;
import java.awt.*;
import java.time.*;
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
    /** How far back a daily habit's grid reaches: four weeks of columns. */
    private static final int WEEKS=4;

    private HabitsPanel() { }

    static JPanel view(Tracker tracker,Runnable refresh) {
        var body=stack();
        body.add(YoruApp.pageHeaderFor("Habits","STREAKS · CONSISTENCY · TIME SINCE",
            named(button("+ Daily check-off",()->create(tracker,refresh,HabitKind.DAILY,body)),"habit.new.daily"),
            named(button("+ Time since",()->create(tracker,refresh,HabitKind.TIME_SINCE,body)),"habit.new.since")));
        if(tracker.state().habits().isEmpty()) {
            body.add(ankiStreak(tracker));
            gap(body,SPACE_LG);
            body.add(emptyState("No manual habits yet.",
                "Check off a day, or count the time since you stopped.",null));
            return body;
        }
        // Two columns of their own kind, so both are on screen at once: the two
        // kinds used to alternate down one column of full-width cards, each tall
        // enough that you saw two of them (#53). Each kind is one card of rows,
        // a list rather than a card per habit, so six daily habits and eight
        // trackers fit beside each other at a desktop size (#52).
        var dailyHabits=tracker.state().habits().stream().filter(h->h.kind()==HabitKind.DAILY).toList();
        var sinceHabits=tracker.state().habits().stream().filter(h->h.kind()==HabitKind.TIME_SINCE).toList();
        var daily=stack();
        var since=stack();
        daily.add(sectionHeader("DAILY · STREAKS AND CONSISTENCY"));
        gap(daily,SPACE_SM);
        since.add(sectionHeader("TIME SINCE"));
        gap(since,SPACE_SM);
        var dailyList=list();
        for(int i=0;i<dailyHabits.size();i++)
            dailyList.add(dailyRow(tracker,refresh,dailyHabits.get(i),i==dailyHabits.size()-1));
        if(dailyHabits.isEmpty()) dailyList.add(bodyLabel("No daily check-offs yet. Tick them off on the Tasks page once you have one."));
        var sinceList=list();
        for(int i=0;i<sinceHabits.size();i++)
            sinceList.add(sinceRow(tracker,refresh,sinceHabits.get(i),i==sinceHabits.size()-1));
        if(sinceHabits.isEmpty()) sinceList.add(bodyLabel("No time-since trackers yet."));
        daily.add(dailyList);
        since.add(sinceList);
        glue(daily);
        glue(since);
        body.add(new Columns(daily,since));
        gap(body,SPACE_LG);
        body.add(ankiStreak(tracker));
        gap(body,SPACE_XL);
        return body;
    }

    /** Imported reviews are their own read-only habit; no second check-off is needed. */
    static JPanel ankiStreak(Tracker tracker) {
        var card=card();
        card.setName("habits.anki");
        card.add(sectionHeader("ANKI · AUTOMATIC"));
        gap(card,SPACE_SM);
        var value=wrapping("",TYPE_BODY,TEXT);
        value.setName("habits.anki.streak");
        value.setAlignmentX(0);
        card.add(value);
        var note=bodyLabel("");
        note.setName("habits.anki.detail");
        note.setAlignmentX(0);
        card.add(note);
        Runnable update=()-> {
            var anki=tracker.state().anki();
            var last=anki.last();
            String summary;
            String detail;
            if(last==null) {
                summary="Your Anki streak appears here after the first sync.";
                detail=anki.enabled()?"Open Anki with AnkiConnect enabled to sync your reviews."
                    :"Connect Anki in Settings → Integrations. Any day with reviews counts automatically.";
            } else {
                var stats=AnkiStreak.of(last,tracker.now(),ZoneId.systemDefault());
                summary="Streak: "+plural(stats.current(),"day")+" · best: "+plural(stats.best(),"day");
                detail="Any day with reviews counts. "
                    +(stats.cached()?"Saved streak as of "+DateText.date(stats.asOf())+".":"Synced today.")
                    +(anki.enabled()?"":" Sync is off.");
            }
            value.setText(summary);
            note.setText(detail);
        };
        update.run();
        // Counts refresh even when no timed sitting is imported and this page stays open.
        var timer=new Timer(60_000,e->update.run());
        card.putClientProperty("anki.refreshTimer",timer);
        card.addHierarchyListener(e->{if(card.isShowing())timer.start();else timer.stop();});
        return card;
    }

    /** One card that a column's rows are listed in, edge to edge, divided by hairlines. */
    private static JPanel list() {
        var list=card();
        // The rows carry their own padding and rules, as the task table's do;
        // the card keeps only enough room to round its corners.
        list.setBorder(new javax.swing.border.EmptyBorder(SPACE_XS,SPACE_SM,SPACE_XS,SPACE_SM));
        return list;
    }

    /**
     * One daily habit, as two lines: its name over the last seven days, then
     * the run it is on and how consistent it has been, which is the number a
     * streak cannot give (#55). The best run and the week so far are in the
     * tooltips and the history, which is behind the name, a click away.
     */
    private static JPanel dailyRow(Tracker tracker,Runnable refresh,Habit habit,boolean last) {
        var today=HabitStats.today(habit);
        var month=HabitStats.lastDays(habit,today,30);
        // The seven days are never squeezed: under that, the figures move down.
        var line=listLine(last,7*grow(SPACE_XL)+6*SPACE_XS);
        line.setName("habit.row."+habit.id());

        var left=stack();
        var name=shortenable(habit.name(),TYPE_BODY,TEXT);
        name.setName("habit.name."+habit.id());
        name.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        name.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                if(SwingUtilities.isLeftMouseButton(e)) showHistory(tracker,refresh,line,habit);
            }
        });
        left.add(name);
        gap(left,SPACE_XS);
        left.add(weekStrip(tracker,refresh,habit,today));
        line.add(left);

        String summary=summary(tracker,habit,today);
        int streak=habit.streak(today);
        // "day streak" is one compound, so the count never makes it a plural.
        var run=figure(streak+"","day streak",summary);
        run.setName("habit.streak."+habit.id());
        run.getAccessibleContext().setAccessibleName("Streak: "+plural(streak,"day"));
        var kept=figure(month.of()==0?"—":month.percent()+"%","last 30 days",
            (month.of()==0?"Started today":month+" kept since "+DateText.date(habit.since())));
        kept.setName("habit.consistency."+habit.id());
        kept.getAccessibleContext().setAccessibleName("Consistency, last 30 days: "
            +(month.of()==0?"not started":month.percent()+" percent, "+month));
        var more=ghost(button("⋯",()->{ }));
        more.setName("habit.more."+habit.id());
        more.setToolTipText("History, rename or delete");
        more.getAccessibleContext().setAccessibleName("Actions for "+habit.name());
        more.addActionListener(e->dailyMenu(tracker,refresh,line,habit).show(more,0,more.getHeight()));
        line.add(trailing(run,kept,more));
        return line;
    }

    /** Everything the row does not say out loud: the run, the best run, the week and the month. */
    static String summary(Tracker tracker,Habit habit,LocalDate today) {
        var week=HabitStats.thisWeek(habit,today,tracker.state().settings().weekStartsOn());
        var month=HabitStats.lastDays(habit,today,30);
        return plural(habit.streak(today),"day")+" in a row · best run "+plural(HabitStats.longestStreak(habit),"day")
            +" · "+week.done()+" of "+Math.max(week.of(),week.done())+" this week · "
            +(month.of()==0?"started today":month+" in the last 30");
    }

    /** A number over what it counts, right-aligned at a row's end, with the detail on hover. */
    private static JPanel figure(String number,String caption,String detail) {
        var column=new JPanel();
        column.setOpaque(false);
        column.setLayout(new BoxLayout(column,BoxLayout.Y_AXIS));
        var value=label(number,TYPE_HEADING,TEXT);
        var what=label(caption,TYPE_CAPTION,MUTED);
        for(var part:new JLabel[]{value,what}) {
            part.setAlignmentX(1f);
            part.setToolTipText(detail);
            column.add(part);
        }
        column.setToolTipText(detail);
        column.getAccessibleContext().setAccessibleDescription(detail);
        return column;
    }

    static JPopupMenu dailyMenu(Tracker tracker,Runnable refresh,JPanel owner,Habit habit) {
        var menu=Menus.popup();
        menu.add(Menus.item("History…","habit.history."+habit.id(),true,()->showHistory(tracker,refresh,owner,habit),null));
        menu.add(Menus.item("Time zone…","habit.zone."+habit.id(),true,()-> {
            var form=new HabitZoneForm(habit);
            while(Dialogs.confirm(owner,form,"Daily habit time zone","Save")) {
                try{form.save(tracker,habit.id());refresh.run();return;}
                catch(Exception failure){Dialogs.error(owner,failure.getMessage());}
            }
        },null));
        habitOrder(menu,tracker,refresh,owner,habit);
        menu.addSeparator();
        menu.add(Menus.item("Rename…","habit.rename."+habit.id(),true,()->rename(tracker,refresh,owner,habit),null));
        menu.add(Menus.item("Delete…","habit.delete."+habit.id(),true,()->delete(tracker,refresh,owner,habit),null));
        return menu;
    }

    private static void habitOrder(JPopupMenu menu,Tracker tracker,Runnable refresh,Component owner,Habit habit) {
        var sameKind=tracker.state().habits().stream().filter(h->h.kind()==habit.kind()).map(Habit::id).toList();
        int index=sameKind.indexOf(habit.id());
        menu.addSeparator();
        menu.add(Menus.item("Move up","habit.up."+habit.id(),index>0,
            ()->act(owner,refresh,()->tracker.moveHabit(habit.id(),-1)),"Already first"));
        menu.add(Menus.item("Move down","habit.down."+habit.id(),index>=0&&index<sameKind.size()-1,
            ()->act(owner,refresh,()->tracker.moveHabit(habit.id(),1)),"Already last"));
    }

    private static void showHistory(Tracker tracker,Runnable refresh,Component owner,Habit habit) {
        Dialogs.info(owner,habit.name(),historyGrid(tracker,refresh,habit));
    }

    /**
     * A row of the list: what it is first, its figures and controls at the end,
     * beside it or under it. Add the subject, then the end.
     */
    private static JPanel listLine(boolean last,int floor) {
        var line=new JPanel(new EndOrUnder(floor)) {
            @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE,getPreferredSize().height); }
        };
        line.setOpaque(false);
        line.setAlignmentX(0);
        line.setBorder(last?listEnd():listRow());
        return line;
    }

    /**
     * The end of a row beside its subject when both fit, and under it when they
     * do not.
     *
     * At the larger text sizes (#31) the figures and controls at a row's end
     * grow as fast as the column they are in, and kept beside the subject they
     * squeezed a habit's seven days into slivers and cut its name to a letter.
     * The subject keeps at least {@code floor} pixels beside the end, or the
     * end moves onto a line of its own.
     */
    static final class EndOrUnder implements LayoutManager {
        /**
         * Where the end goes: beside the subject, under it, or under it at its
         * narrowest when it is wider than even a line of its own (a long
         * time-since counter and its controls at 200% text in a half-width
         * column, see {@link CounterEnd}).
         */
        private enum Place { BESIDE, UNDER, NARROWEST }
        private final int floor;
        /** How the row was last measured, so a layout that disagrees can ask again. */
        private Place measured;
        EndOrUnder(int floor) { this.floor=floor; }

        @Override public void addLayoutComponent(String name,Component c) { }
        @Override public void removeLayoutComponent(Component c) { }

        /** The room inside the row, or inside what holds it when the row has no width yet. */
        private static int room(Container row) {
            int width=row.getWidth();
            for(Container holder=row.getParent();width==0&&holder!=null;holder=holder.getParent()) {
                var padding=holder.getInsets();
                width=Math.max(0,holder.getWidth()-padding.left-padding.right);
            }
            var insets=row.getInsets();
            return width==0?Integer.MAX_VALUE:width-insets.left-insets.right;
        }

        private Place place(Container row) {
            if(row.getComponentCount()<2) return Place.BESIDE;
            int room=room(row),end=row.getComponent(1).getPreferredSize().width;
            if(room>=floor+SPACE_MD+end) return Place.BESIDE;
            return end<=room?Place.UNDER:Place.NARROWEST;
        }

        private static Dimension size(Component end,Place place) {
            return place==Place.NARROWEST?end.getMinimumSize():end.getPreferredSize();
        }

        @Override public Dimension preferredLayoutSize(Container row) {
            var insets=row.getInsets();
            var subject=row.getComponent(0).getPreferredSize();
            var place=place(row);
            measured=place;
            var end=row.getComponentCount()>1?size(row.getComponent(1),place):new Dimension();
            var inner=place==Place.BESIDE?new Dimension(subject.width+SPACE_MD+end.width,Math.max(subject.height,end.height))
                :new Dimension(Math.max(subject.width,end.width),subject.height+SPACE_XS+end.height);
            return new Dimension(inner.width+insets.left+insets.right,inner.height+insets.top+insets.bottom);
        }

        @Override public Dimension minimumLayoutSize(Container row) {
            return new Dimension(0,preferredLayoutSize(row).height);
        }

        @Override public void layoutContainer(Container row) {
            var insets=row.getInsets();
            int x=insets.left,y=insets.top;
            int width=row.getWidth()-insets.left-insets.right,height=row.getHeight()-insets.top-insets.bottom;
            var subject=row.getComponent(0);
            var end=row.getComponentCount()>1?row.getComponent(1):null;
            if(end==null) { subject.setBounds(x,y,width,height); return; }
            var place=place(row);
            var endSize=size(end,place);
            if(place==Place.BESIDE) {
                int subjectWidth=Math.max(0,width-endSize.width-SPACE_MD);
                subject.setBounds(x,y,subjectWidth,height);
                end.setBounds(x+width-endSize.width,y+Math.max(0,(height-endSize.height)/2),endSize.width,Math.min(height,endSize.height));
            } else {
                int subjectHeight=subject.getPreferredSize().height;
                subject.setBounds(x,y,width,subjectHeight);
                end.setBounds(x,y+subjectHeight+SPACE_XS,Math.min(width,endSize.width),endSize.height);
            }
            // Measured for another arrangement, before the row had its width:
            // what holds it set aside the wrong height, and a box layout keeps
            // what it measured until it is told otherwise. Under at the end's
            // narrowest is as tall again as under, so it counts as another.
            if(measured!=null&&measured!=place) {
                measured=place;
                for(Container holder=row.getParent();holder!=null;holder=holder.getParent())
                    if(holder.getLayout() instanceof LayoutManager2 cached) cached.invalidateLayout(holder);
                if(row instanceof JComponent component) component.revalidate();
            }
        }
    }

    /**
     * A time-since row's end: the counter, then its controls on the same line,
     * or on the line under it when the row cannot hold both on one.
     *
     * Counted in calendar units, a tracker that has run for years reads
     * "10y 11mo 30d 23h 59m", and at 150% text in a half-width column that
     * with Start again and ⋯ is wider than the column itself, so moving the
     * end under the name was not enough: the ⋯ was cut off. The preferred size
     * is the single line, which a row asks for first; the minimum is the two
     * lines, which {@link EndOrUnder} falls back to.
     */
    static final class CounterEnd implements LayoutManager {
        @Override public void addLayoutComponent(String name,Component c) { }
        @Override public void removeLayoutComponent(Component c) { }

        private static Dimension padded(Container end,int width,int height) {
            var insets=end.getInsets();
            return new Dimension(width+insets.left+insets.right,height+insets.top+insets.bottom);
        }

        @Override public Dimension preferredLayoutSize(Container end) {
            var counter=end.getComponent(0).getPreferredSize();
            var controls=end.getComponent(1).getPreferredSize();
            return padded(end,counter.width+SPACE_MD+controls.width,Math.max(counter.height,controls.height));
        }

        @Override public Dimension minimumLayoutSize(Container end) {
            var counter=end.getComponent(0).getPreferredSize();
            var controls=end.getComponent(1).getPreferredSize();
            return padded(end,Math.max(counter.width,controls.width),counter.height+SPACE_XS+controls.height);
        }

        @Override public void layoutContainer(Container end) {
            var insets=end.getInsets();
            int x=insets.left,y=insets.top;
            int width=end.getWidth()-insets.left-insets.right,height=end.getHeight()-insets.top-insets.bottom;
            var counter=end.getComponent(0);
            var controls=end.getComponent(1);
            var c=counter.getPreferredSize();
            var k=controls.getPreferredSize();
            if(width>=c.width+SPACE_MD+k.width) {
                counter.setBounds(x,y+Math.max(0,(height-c.height)/2),c.width,Math.min(height,c.height));
                controls.setBounds(x+width-k.width,y+Math.max(0,(height-k.height)/2),k.width,Math.min(height,k.height));
            } else {
                counter.setBounds(x,y,Math.min(width,c.width),c.height);
                controls.setBounds(x,y+c.height+SPACE_XS,Math.min(width,k.width),k.height);
            }
        }
    }

    /** The end of a row, centred on its height, with the controls last. */
    private static JPanel trailing(JComponent... parts) {
        var end=new JPanel();
        end.setOpaque(false);
        end.setLayout(new BoxLayout(end,BoxLayout.X_AXIS));
        for(var part:parts) {
            if(end.getComponentCount()>0) end.add(Box.createHorizontalStrut(SPACE_MD));
            part.setAlignmentY(0.5f);
            end.add(part);
        }
        return end;
    }

    /** The last seven days, each a cell that can still be corrected. */
    private static JPanel weekStrip(Tracker tracker,Runnable refresh,Habit habit,LocalDate today) {
        int side=grow(SPACE_XL);
        var strip=new JPanel(new GridLayout(1,7,SPACE_XS,0));
        strip.setOpaque(false);
        strip.setAlignmentX(0);
        // Its own size in a column that would otherwise stretch seven cells
        // across the whole row.
        var size=new Dimension(7*side+6*SPACE_XS,side);
        strip.setPreferredSize(size);
        strip.setMaximumSize(size);
        for(int i=6;i>=0;i--) {
            var date=today.minusDays(i);
            boolean done=habit.checkIns().contains(date);
            var cell=selected(button("",()->act(strip,refresh,()->tracker.checkIn(habit.id(),date,!done))),done);
            cell.setName("habit.day."+date);
            cell.setPreferredSize(new Dimension(side,side));
            cell.setToolTipText(DateText.date(date)+(done?" · done · click to undo":" · click to check off"));
            cell.getAccessibleContext().setAccessibleName(DateText.date(date)+(done?" completed":" not completed"));
            if(date.equals(today)) cell.setBorder(controlBorder(ringFor(cell.getBackground())));
            strip.add(cell);
        }
        return strip;
    }

    private static final String[] UNITS={"y","mo","d","h","m"},UNIT_WORDS={"year","month","day","hour","minute"};

    /**
     * How long a period has run, from its largest unit down to the minute:
     * "1y 2mo 3d 4h 5m", "3d 0h 5m", "5m". Every unit below the largest is
     * written even when it is zero, so the counter only changes width when it
     * gains a unit. No seconds, which tick for nothing (#52).
     */
    static String elapsed(Instant start,Instant end,ZoneId zone) {
        return units(span(start,end,zone),false);
    }

    /** The same, in words for a screen reader: "1 year, 2 months, 0 days, 4 hours, 5 minutes". */
    static String elapsedWords(Instant start,Instant end,ZoneId zone) {
        return units(span(start,end,zone),true);
    }

    /**
     * Years, months and days on the calendar in the habit's zone, then the hours
     * and minutes that have really passed. A month from 31 January is the end of
     * February, and a day across a clock change is still one day; the hours and
     * minutes after the last whole day are real elapsed time, so a repeated
     * autumn hour still counts.
     */
    private static long[] span(Instant start,Instant end,ZoneId zone) {
        if(!end.isAfter(start)) return new long[5];
        var cursor=start.atZone(zone);
        var finish=end.atZone(zone);
        long years=finish.getYear()-cursor.getYear();
        if(cursor.plusYears(years).isAfter(finish)) years--;
        cursor=cursor.plusYears(years);
        long months=java.time.temporal.ChronoUnit.MONTHS.between(YearMonth.from(cursor),YearMonth.from(finish));
        if(cursor.plusMonths(months).isAfter(finish)) months--;
        cursor=cursor.plusMonths(months);
        long days=java.time.temporal.ChronoUnit.DAYS.between(cursor.toLocalDate(),finish.toLocalDate());
        if(cursor.plusDays(days).isAfter(finish)) days--;
        cursor=cursor.plusDays(days);
        long minutes=Duration.between(cursor,finish).toMinutes();
        return new long[]{years,months,days,minutes/60,minutes%60};
    }

    private static String units(long[] span,boolean words) {
        var out=new java.util.StringJoiner(words?", ":" ");
        boolean started=false;
        for(int i=0;i<span.length;i++) {
            started|=span[i]>0||i==span.length-1;
            if(started) out.add(words?plural((int)span[i],UNIT_WORDS[i]):span[i]+UNITS[i]);
        }
        return out.toString();
    }

    /** When a period began, as every other date on this page is written. */
    static String began(Instant start,ZoneId zone) {
        var local=start.atZone(zone);
        return DateText.date(local.toLocalDate())+", "+DateText.time(local.toLocalTime());
    }

    /**
     * One time-since tracker, as a line (#52): its name and when it began, then
     * how long it has run, to the minute, and Start again. Everything else is in
     * the ⋯ menu.
     */
    private static JPanel sinceRow(Tracker tracker,Runnable refresh,Habit habit,boolean last) {
        // A name needs room for a couple of words beside the time it has run.
        var line=listLine(last,grow(NAME_FLOOR*2));
        line.setName("habit.row."+habit.id());
        var zone=ZoneId.of(habit.zone());
        var left=stack();
        left.add(named(shortenable(habit.name(),TYPE_BODY,TEXT),"habit.name."+habit.id()));
        // Gives way to the figures beside it like the name above it does, with
        // the whole of it in the tooltip.
        left.add(shortenable("since "+began(habit.starts().getLast(),zone),TYPE_CAPTION,MUTED));
        line.add(left);

        var elapsed=label("",TYPE_HEADING,TEXT);
        elapsed.setName("habit.elapsed."+habit.id());
        Runnable update=()->{
            var now=tracker.now();
            elapsed.setText(elapsed(habit.starts().getLast(),now,zone));
            String words=elapsedWords(habit.starts().getLast(),now,zone);
            elapsed.setToolTipText(words);
            elapsed.getAccessibleContext().setAccessibleName(words);
        };
        update.run();
        // A minute is as often as this can change, and nothing ticks off screen.
        var timer=new Timer(60_000,e->update.run());
        elapsed.addHierarchyListener(e->{if(elapsed.isShowing())timer.start();else timer.stop();});
        var again=ghost(button("Start again",()-> {
            if(Dialogs.confirm(line,"Start a new period now? Your previous periods stay in history.","Start again","Start again"))
                act(line,refresh,()->tracker.restartHabit(habit.id()));
        }));
        again.setName("habit.restart."+habit.id());
        again.setToolTipText("End this period and start a new one now");
        again.getAccessibleContext().setAccessibleName("Start "+habit.name()+" again");
        var more=ghost(button("⋯",()->{ }));
        more.setName("habit.more."+habit.id());
        more.setToolTipText("Edit the start, see the history, rename or delete");
        more.getAccessibleContext().setAccessibleName("Actions for "+habit.name());
        more.addActionListener(e->sinceMenu(tracker,refresh,line,habit,zone).show(more,0,more.getHeight()));
        var end=new JPanel(new CounterEnd());
        end.setOpaque(false);
        end.add(elapsed);
        end.add(trailing(again,more));
        line.add(end);
        return line;
    }

    static JPopupMenu sinceMenu(Tracker tracker,Runnable refresh,JPanel card,Habit habit,ZoneId zone) {
        var menu=Menus.popup();
        menu.add(Menus.item("Edit start date…","habit.editStart."+habit.id(),true,()-> {
            var input=new DateTimeField(habit.starts().getLast(),zone);
            while(Dialogs.confirm(card,input,"Edit current period start","Save")) {
                try{tracker.editHabitStart(habit.id(),input.value());refresh.run();break;}
                catch(Exception error){Dialogs.error(card,"Check date",error.getMessage());}
            }
        },null));
        menu.add(Menus.item("History…","habit.periods."+habit.id(),true,
            ()->Dialogs.info(card,"Your history",history(tracker,habit.id(),zone,refresh)),null));
        habitOrder(menu,tracker,refresh,card,habit);
        menu.addSeparator();
        menu.add(Menus.item("Rename…","habit.renameMenu."+habit.id(),true,()->rename(tracker,refresh,card,habit),null));
        menu.add(Menus.item("Delete…","habit.deleteMenu."+habit.id(),true,()->delete(tracker,refresh,card,habit),null));
        return menu;
    }

    private static void rename(Tracker tracker,Runnable refresh,java.awt.Component owner,Habit habit) {
        var input=new JTextField(habit.name(),24);
        input.getAccessibleContext().setAccessibleName("Habit name");
        while(Dialogs.confirm(owner,input,"Rename habit","Save")) {
            try {tracker.renameHabit(habit.id(),input.getText());refresh.run();break;}
            catch(Exception error){Dialogs.error(owner,error.getMessage());}
        }
    }

    private static void delete(Tracker tracker,Runnable refresh,java.awt.Component owner,Habit habit) {
        if(Dialogs.confirm(owner,"Delete this habit and its history? Other habits and study records stay unchanged. A vault backup is kept first.",
            "Delete "+habit.name(),"Delete habit"))
            act(owner,refresh,()->tracker.deleteHabit(habit.id()));
    }

    /** Every day a daily habit has been checked off, with the grid to correct them. */
    static JComponent historyGrid(Tracker tracker,Runnable refresh,Habit habit) {
        return new DailyHabitHistory(tracker,refresh,habit.id());
    }

    static void daily(Tracker tracker,Runnable refresh,Habit habit,JPanel card,LocalDate end) {
        var today=tracker.now().atZone(ZoneId.of(habit.zone())).toLocalDate();
        // The whole of what the row only hints at, in body ink: the accent is
        // saved for the filled day cells below, where it marks which days are
        // done. Drawn in the accent this line measured 3.0:1 on Linen and 3.5:1
        // on Sakura, under AA for a 13 px label.
        card.add(wrapping(summary(tracker,habit,today)+" · "
            +Theme.plural(habit.checkIns().size(),"day")+" checked off in all",TYPE_LABEL,TEXT));
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
        var first=settings.weekOf(end).minusWeeks(WEEKS-1);
        int side=grow(48);
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
            if(date.isAfter(today) || date.getYear()<1900) {
                var blank=new JPanel();
                blank.setOpaque(false);
                days.add(blank);
                continue;
            }
            boolean done=habit.checkIns().contains(date);
            var cell=selected(button(Integer.toString(date.getDayOfMonth()),()->act(card,refresh,()->tracker.checkIn(habit.id(),date,!done))),done);
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
        card.add(bodyLabel(DateText.date(first)+" – "+DateText.date(first.plusWeeks(WEEKS).minusDays(1))+" · click a day to correct it"));
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
        scroll.setPreferredSize(new Dimension(Math.min(grow(640),1000),Math.min(grow(240),440)));
        Runnable[] rebuild=new Runnable[1];
        rebuild[0]=()-> {
            rows.removeAll();
            var habit=tracker.state().habits().stream().filter(h->h.id().equals(habitId)).findFirst().orElse(null);
            if(habit==null) rows.add(bodyLabel("This tracker no longer exists."));
            else {
                var add=button("Add missed restart…",()-> {
                    var input=new DateTimeField(tracker.now(),zone,"Restarted at");
                    var form=missedPeriodForm(input);
                    while(Dialogs.confirm(rows,form,"Add missed restart","Add restart")) {
                        try{tracker.addHabitPeriod(habitId,input.value());refresh.run();rebuild[0].run();return;}
                        catch(Exception error){Dialogs.error(rows,"Check date",error.getMessage());}
                    }
                });
                add.setName("habit.period.add."+habitId);
                rows.add(add);
                gap(rows,SPACE_SM);
                var starts=habit.starts();
                for(int i=0;i<starts.size();i++) {
                    var start=starts.get(i);
                    var end=i+1<starts.size()?starts.get(i+1):tracker.now();
                    String when=began(start,zone);
                    var line=wrappingRow();
                    line.add(label(when,TYPE_BODY,TEXT));
                    line.add(label(elapsed(start,end,zone),TYPE_BODY,MUTED));
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

    static JPanel missedPeriodForm(DateTimeField input) {
        var form=stack();
        form.add(bodyLabel("Add a past restart. Existing starts stay in place; this splits the period containing that time."));
        gap(form,SPACE_SM);
        form.add(input);
        return form;
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
