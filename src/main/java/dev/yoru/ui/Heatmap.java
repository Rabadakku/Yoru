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

    // ---- geometry ----------------------------------------------------------
    //
    // The map is drawn rather than laid out, so nothing here grows when the
    // reader asks for larger text (#31) unless it is asked to. At 200% the
    // month names were drawn half above the top of the panel, the weekday
    // initials ran into the first column, and the cells stayed the size they
    // were designed at under text twice as tall. Every measurement below
    // follows the caption face the map writes in.

    private FontMetrics caption() { return getFontMetrics(Theme.captionFont()); }

    /** The band over the grid, which the month names are written in. */
    private int header() { return Math.max(Theme.grow(28),caption().getHeight()+Theme.SPACE_XS); }

    /** The column left of the grid, which the weekday initials sit in. */
    private int gutter() { return Math.max(Theme.grow(32),caption().stringWidth("W")+Theme.SPACE_MD); }

    /** One day's row, and the cell drawn inside it. */
    private int rowStep() { return Theme.grow(17); }
    private int cell() { return Theme.grow(13); }

    private int step() {
        return Math.max(Theme.grow(9),(getWidth()-gutter()-Theme.grow(6))/52);
    }

    /** Room for the grid, plus the line the keyboard cursor reads out under it. */
    @Override public Dimension getPreferredSize() {
        return new Dimension(Theme.grow(760),header()+7*rowStep()+Theme.SPACE_SM+caption().getHeight());
    }

    public String getToolTipText(MouseEvent e) {
        int col=(e.getX()-gutter())/step(),row=(e.getY()-header())/rowStep();
        if(e.getX()<gutter()||e.getY()<header()||col>51||row>6)return null;
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
        var fm=caption();
        // Monday, Wednesday and Friday, wherever the week start puts them:
        // seven labels in one row band would collide, three are enough to read
        // the rows by, and every other row from the top gives two identical S's
        // on a week that starts on Sunday.
        for(DayOfWeek named:new DayOfWeek[]{DayOfWeek.MONDAY,DayOfWeek.WEDNESDAY,DayOfWeek.FRIDAY}) {
            int row=Math.floorMod(named.getValue()-weekStart.getValue(),7);
            g.drawString(initial(named),(gutter()-fm.stringWidth(initial(named)))/2,
                header()+row*rowStep()+(cell()+fm.getAscent()-fm.getDescent())/2);
        }
        int last=-1,free=0;
        int months=header()-(header()-fm.getHeight())/2-fm.getDescent();
        for(int c=0;c<52;c++) {
            LocalDate first=start.plusWeeks(c);
            // A month is named once, over the first full column it owns, and
            // only where the word fits beside the one before it: the left edge
            // is a few days of the month before it, and two months that turn a
            // week apart printed "SEPOCT" across the top of the map.
            if(first.getMonthValue()!=last) {
                String name=first.getMonth().toString().substring(0,3);
                int x=gutter()+c*step();
                if(c>0&&x>=free) {
                    g.setColor(Theme.MUTED);
                    g.drawString(name,x,months);
                    free=x+fm.stringWidth(name)+Theme.SPACE_SM;
                }
                last=first.getMonthValue();
            }
            for(int r=0;r<7;r++) {
                LocalDate d=first.plusDays(r);
                g.setColor(colour(d,days.getOrDefault(d,0L)));
                g.fillRoundRect(gutter()+c*step(),header()+r*rowStep(),step()-Theme.grow(3),cell(),
                    Theme.RADIUS,Theme.RADIUS);
            }
        }
        // The keyboard cursor: the cell it is on, ringed, and the day written
        // out underneath — the same reading the tooltip gives the mouse.
        if(cursorCol>=0) {
            g.setColor(Theme.ringFor(Theme.PANEL));
            g.drawRoundRect(gutter()+cursorCol*step()-1,header()+cursorRow*rowStep()-1,
                step()-Theme.grow(3)+1,cell()+1,Theme.RADIUS,Theme.RADIUS);
            g.setFont(Theme.captionFont());
            g.setColor(Theme.MUTED);
            g.drawString(readout(cursorDay()),gutter(),header()+7*rowStep()+fm.getAscent());
        }
        g.dispose();
    }
}
