package dev.yoru.ui;

import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.border.MatteBorder;
import java.awt.*;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.time.temporal.WeekFields;
import java.util.Locale;
import java.util.function.Consumer;
import static dev.yoru.ui.Theme.*;

/**
 * A month to pick a day from: the calendar in Notion's date popover (#24).
 *
 * Always six weeks, so stepping between months never resizes the popup under
 * the pointer. The chosen day is filled and bold, and today is outlined, so
 * neither is told by colour alone; each day's accessible name says both.
 *
 * Its buttons never take the keyboard. The calendar opens in a popup over a
 * dialog, and a button that took focus would take it from the field the popup
 * belongs to. The keyboard's way to a date is that field, which reads what is
 * typed and steps a day with the arrow keys.
 */
final class CalendarPanel extends JPanel {
    private final LocalDate today, selected;
    private final Consumer<LocalDate> picked;
    private final DayOfWeek firstDay = WeekFields.of(Locale.getDefault()).getFirstDayOfWeek();
    private final JLabel heading = label("", TYPE_LABEL, TEXT);
    private final JPanel days = new JPanel(new GridLayout(0, 7, SPACE_XS, SPACE_XS));
    private YearMonth month;

    /**
     * @param selected the day shown as chosen, or null for none
     * @param clear    what Clear does, or null where a date is required and there is no Clear
     */
    CalendarPanel(LocalDate selected, LocalDate today, Consumer<LocalDate> picked, Runnable clear) {
        super(new BorderLayout(0, SPACE_SM));
        this.today = today;
        this.selected = selected;
        this.picked = picked;
        this.month = YearMonth.from(selected == null ? today : selected);
        setOpaque(true);
        setBackground(PANEL);
        setBorder(new CompoundBorder(new LineBorder(LINE), new EmptyBorder(SPACE_MD, SPACE_MD, SPACE_MD, SPACE_MD)));

        heading.setFont(labelFont().deriveFont(Font.BOLD));
        var top = new JPanel(new BorderLayout(SPACE_SM, 0));
        top.setOpaque(false);
        top.add(heading, BorderLayout.WEST);
        var steps = new JPanel(new FlowLayout(FlowLayout.RIGHT, SPACE_XS, 0));
        steps.setOpaque(false);
        steps.add(quiet(button("Today", () -> picked.accept(today)), "calendar.today", "Today, " + DateText.longDate(today)));
        steps.add(quiet(button("‹", () -> step(-1)), "calendar.previous", "Previous month"));
        steps.add(quiet(button("›", () -> step(1)), "calendar.next", "Next month"));
        top.add(steps, BorderLayout.EAST);
        add(top, BorderLayout.NORTH);

        days.setOpaque(false);
        add(days, BorderLayout.CENTER);
        if (clear != null) {
            var bottom = new JPanel(new BorderLayout());
            bottom.setOpaque(false);
            bottom.setBorder(new CompoundBorder(new MatteBorder(HAIRLINE, 0, 0, 0, LINE), new EmptyBorder(SPACE_SM, 0, 0, 0)));
            bottom.add(quiet(button("Clear", clear), "calendar.clear", "Clear the date"), BorderLayout.WEST);
            add(bottom, BorderLayout.SOUTH);
        }
        rebuild();
    }

    /** The month on show. */
    YearMonth month() { return month; }

    private void step(int months) {
        month = month.plusMonths(months);
        rebuild();
    }

    private void rebuild() {
        heading.setText(month.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " " + month.getYear());
        days.removeAll();
        for (int i = 0; i < 7; i++) {
            String name = firstDay.plus(i).getDisplayName(TextStyle.SHORT, Locale.ENGLISH).substring(0, 2);
            var weekday = label(name, TYPE_CAPTION, MUTED);
            weekday.setHorizontalAlignment(SwingConstants.CENTER);
            days.add(weekday);
        }
        LocalDate first = month.atDay(1);
        int lead = (first.getDayOfWeek().getValue() - firstDay.getValue() + 7) % 7;
        LocalDate day = first.minusDays(lead);
        for (int i = 0; i < 42; i++, day = day.plusDays(1)) days.add(day(day));
        days.revalidate();
        days.repaint();
    }

    private JButton day(LocalDate date) {
        boolean chosen = date.equals(selected), current = date.equals(today);
        var b = quiet(button(String.valueOf(date.getDayOfMonth()), () -> picked.accept(date)), "calendar.day." + date,
            DateText.longDate(date) + (chosen ? ", selected" : "") + (current ? ", today" : ""));
        b.setHorizontalAlignment(SwingConstants.CENTER);
        if (chosen) {
            b.setBackground(CYAN);
            b.setForeground(DARK ? BG : PANEL);
            b.setFont(labelFont().deriveFont(Font.BOLD));
        } else {
            b.setForeground(YearMonth.from(date).equals(month) ? TEXT : MUTED);
        }
        if (current && !chosen)
            b.setBorder(new CompoundBorder(new LineBorder(ACCENT_TEXT, HAIRLINE, true),
                new EmptyBorder(SPACE_XS - HAIRLINE, SPACE_SM - HAIRLINE, SPACE_XS - HAIRLINE, SPACE_SM - HAIRLINE)));
        return b;
    }

    /** A borderless button on the calendar's own ground that leaves the keyboard where it is. */
    private static JButton quiet(JButton b, String name, String spoken) {
        b.setName(name);
        b.getAccessibleContext().setAccessibleName(spoken);
        b.setFocusable(false);
        b.setBackground(PANEL);
        b.setFont(labelFont());
        b.setBorder(new EmptyBorder(SPACE_XS, SPACE_SM, SPACE_XS, SPACE_SM));
        return b;
    }
}
