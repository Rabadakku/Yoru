package dev.yoru.ui;
import dev.yoru.application.Analytics;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.Map;

/**
 * Fifty-two weeks of recorded time.
 *
 * Tiers are computed against the user's own daily goal rather than a fixed
 * 15m/30m/1h/2h ladder, so the colours mean the same thing whether the goal is
 * two hours or eight. Days that beat the goal get a rainbow cell, which is the
 * one state worth spotting from across the room.
 */
final class Heatmap extends JPanel {
    private final Map<LocalDate,Long> days;
    private final LocalDate today,start;
    private final int goalHours;
    private final DayOfWeek weekStart;

    /** The day the keyboard is on, or -1 while the mouse is the only reader. */
    private int cursorCol=-1,cursorRow=-1;

    Heatmap(Map<LocalDate,Long> days,LocalDate today,int goalHours,DayOfWeek weekStart) {
        this.days=days;
        this.today=today;
        this.goalHours=goalHours;
        // The vault's own week start, not Monday: the task calendar and the
        // habit grids break their weeks where the setting says, and a column
        // here that began on a different day made the same week two shapes.
        this.weekStart=weekStart;
        start=today.minusWeeks(51).with(TemporalAdjusters.previousOrSame(weekStart));
        setOpaque(false);
        // Room under the grid for the readout line the keyboard cursor draws.
        setPreferredSize(new Dimension(760,158+Theme.SPACE_LG));
        setToolTipText("Daily time");
        // A short name, not a sentence, but the goal is part of what a cell
        // means — a day is only "good" against it — so the name carries it. The
        // day-by-day detail belongs to the cursor, and the description carries
        // that as the cursor moves, which is why the goal cannot live there.
        getAccessibleContext().setAccessibleName(
            "Activity heat map, 52 weeks, against a "+goalHours+" hour daily goal");
        getAccessibleContext().setAccessibleDescription(
            "Arrow keys read a day; each day's recorded time appears under the map");
        setFocusable(true);
        bind("LEFT",-1,0);
        bind("RIGHT",1,0);
        bind("UP",0,-1);
        bind("DOWN",0,1);
    }

    /** One arrow key, moving the reading cursor by a column or a row. */
    private void bind(String key,int dCol,int dRow) {
        getInputMap(javax.swing.JComponent.WHEN_FOCUSED).put(javax.swing.KeyStroke.getKeyStroke(key),key);
        getActionMap().put(key,new javax.swing.AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { moveCursor(dCol,dRow); }
        });
    }

    /** Moves the keyboard cursor one cell, starting at the most recent day. */
    private void moveCursor(int dCol,int dRow) {
        if(cursorCol<0) { cursorCol=51; cursorRow=6; }
        else {
            cursorCol=Math.max(0,Math.min(51,cursorCol+dCol));
            cursorRow=Math.max(0,Math.min(6,cursorRow+dRow));
        }
        getAccessibleContext().setAccessibleDescription(readout(cursorDay()));
        repaint();
    }

    private LocalDate cursorDay() { return start.plusDays(cursorCol*7L+cursorRow); }

    /** One day, written out: the same reading the tooltip gives the mouse. */
    private String readout(LocalDate d) {
        long seconds=days.getOrDefault(d,0L);
        String note=Analytics.heat(seconds,goalHours)==Analytics.HEAT_OVER_GOAL?"  ·  goal beaten":"";
        return d+": "+Analytics.duration(seconds)+note;
    }

    /** A weekday in one letter, in the reader's own language. */
    private static String initial(DayOfWeek day) {
        return day.getDisplayName(java.time.format.TextStyle.NARROW,java.util.Locale.getDefault())
            .toUpperCase(java.util.Locale.getDefault());
    }

    private int step() {
        return Math.max(9,(getWidth()-38)/52);
    }

    public String getToolTipText(MouseEvent e) {
        int col=(e.getX()-32)/step(),row=(e.getY()-28)/17;
        if(e.getX()<32||e.getY()<28||col>51||row>6)return null;
        return readout(start.plusDays(col*7L+row));
    }

    /** Over-goal days cycle hue by date so a good streak reads as a gradient. */
    static Color overGoal(LocalDate day) {
        float hue=Math.floorMod(day.toEpochDay(),12)/12f;
        return Color.getHSBColor(hue,Theme.DARK?0.52f:0.42f,Theme.DARK?0.86f:0.88f);
    }

    /**
     * The heat tier's colour for a day. One function, so every chart that
     * colours by time recorded agrees with the heat map rather than cycling a
     * palette by row index and calling it the same thing.
     */
    static Color colour(LocalDate day,long seconds,int goalHours) {
        int tier=Analytics.heat(seconds,goalHours);
        return tier==Analytics.HEAT_OVER_GOAL?overGoal(day):Theme.HEAT[tier];
    }

    private Color colour(LocalDate day,long seconds) {
        // A day that has not happened is the card it is drawn on, not the page
        // behind it: painted in the page's ink the rest of this week was a
        // black notch out of the map's last column, which reads as damage
        // rather than as a week still to come.
        if(day.isAfter(today))return Theme.PANEL;
        return colour(day,seconds,goalHours);
    }

    protected void paintComponent(Graphics graphics) {
        var g=(Graphics2D)graphics.create();
        g.setFont(Theme.captionFont());
        g.setColor(Theme.MUTED);
        // Monday, Wednesday and Friday, wherever the week start puts them:
        // seven labels in a 13 px band would collide, three are enough to read
        // the rows by, and every other row from the top gives two identical S's
        // on a week that starts on Sunday.
        for(DayOfWeek named:new DayOfWeek[]{DayOfWeek.MONDAY,DayOfWeek.WEDNESDAY,DayOfWeek.FRIDAY})
            g.drawString(initial(named),7,40+Math.floorMod(named.getValue()-weekStart.getValue(),7)*17);
        int last=-1,named=-3;
        for(int c=0;c<52;c++) {
            LocalDate first=start.plusWeeks(c);
            // A month is named once, over the first full column it owns, and
            // only where there is room for the word: the left edge is a few
            // days of the month before it, and two months that turn a week
            // apart printed "SEPOCT" across the top of the map.
            if(first.getMonthValue()!=last) {
                if(c>0&&c-named>=3) {
                    g.setColor(Theme.MUTED);
                    g.drawString(first.getMonth().toString().substring(0,3),32+c*step(),15);
                    named=c;
                }
                last=first.getMonthValue();
            }
            for(int r=0;r<7;r++) {
                LocalDate d=first.plusDays(r);
                g.setColor(colour(d,days.getOrDefault(d,0L)));
                g.fillRoundRect(32+c*step(),28+r*17,step()-3,13,Theme.RADIUS,Theme.RADIUS);
            }
        }
        // The keyboard cursor: the cell it is on, ringed, and the day written
        // out underneath — the same reading the tooltip gives the mouse.
        if(cursorCol>=0) {
            g.setColor(Theme.ringFor(Theme.PANEL));
            g.drawRoundRect(32+cursorCol*step()-1,28+cursorRow*17-1,step()-1,15,Theme.RADIUS,Theme.RADIUS);
            g.setFont(Theme.captionFont());
            g.setColor(Theme.MUTED);
            g.drawString(readout(cursorDay()),32,28+7*17+Theme.SPACE_MD);
        }
        g.dispose();
    }
}
