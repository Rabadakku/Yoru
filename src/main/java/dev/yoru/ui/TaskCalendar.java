package dev.yoru.ui;

import dev.yoru.domain.Model.*;
import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.time.*;
import java.time.format.TextStyle;
import java.util.List;
import java.util.*;

/**
 * A month of tasks laid out by due date, with drag to reschedule (#5).
 *
 * Weeks begin on whichever day Settings says, so this and ScheduleGrid never
 * disagree about where a week starts.
 *
 * The strip under the grid holds tasks with no due date. It is part of this
 * component rather than a separate one so that dragging works the same way in
 * both directions: onto a day to schedule, back onto the strip to unschedule.
 */
final class TaskCalendar extends JPanel {
    /** date is null when a task is dropped back onto the undated strip. */
    interface Edits {
        void reschedule(UUID taskId, LocalDate date);
        /** A day was double-clicked where no task sits: a new task for that day (#67). */
        default void create(LocalDate date) { }
    }

    private static final int HEADER = 22, CHIP = 16, CHIP_GAP = 2, DAY_LABEL = 15, TRAY = 58;
    /** Where a chip's label starts, and the clear edge it may not run into. */
    private static final int CHIP_TEXT_X = 6, CHIP_TEXT_EDGE = 2;
    /** A day cell holds one line of a title; the backlog strip has room for two. */
    private static final int TRAY_LINES = 2;
    /** The widest an undated chip grows before its title has to wrap. */
    private static final int TRAY_CHIP_MAX = 150;
    /** What a cut title ends in, so a reader can tell it was cut rather than mis-typed. */
    private static final String ELLIPSIS = "…";

    /**
     * The metrics every chip label is measured in.
     *
     * Layout needs them to decide how tall a wrapped backlog chip is; paint
     * needs the same ones to decide where the ellipsis falls. Measuring with a
     * different context in each place is how a label ends up one glyph too wide
     * for the box it was sized for. The caption face is fixed for the life of
     * the JVM, so one scratch image is enough.
     */
    private static final FontMetrics CHIP_METRICS = chipMetrics();

    private static FontMetrics chipMetrics() {
        var scratch = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics();
        var metrics = scratch.getFontMetrics(Theme.captionFont());
        scratch.dispose();
        return metrics;
    }

    /** One chip: {@code lines} is 1 in a day cell, and up to {@link #TRAY_LINES} in the backlog. */
    private record Chip(UUID task, Rectangle bounds, String label, Color colour, boolean done, int lines) { }

    private final YearMonth month;
    private final LocalDate today;
    private final List<Task> tasks;
    private final Map<UUID, Tag> tags = new HashMap<>();
    private final List<Chip> chips = new ArrayList<>();
    private final Map<LocalDate, Rectangle> cells = new LinkedHashMap<>();
    private Rectangle trayBounds = new Rectangle();
    private final LocalDate gridStart;
    private final int weeks;
    private final DayOfWeek weekStart;

    private UUID dragging;
    private Point cursor;
    private LocalDate hovered;
    private boolean overTray;

    TaskCalendar(State state, YearMonth month, LocalDate today, Edits edits) {
        this.month = month;
        this.today = today;
        this.tasks = state.tasks();
        state.tags().forEach(t -> tags.put(t.id(), t));
        setOpaque(false);
        setToolTipText("");

        weekStart = state.settings().weekStartsOn();
        var first = month.atDay(1);
        // Back up to this month's first grid row, however the week is set to begin.
        gridStart = first.with(java.time.temporal.TemporalAdjusters.previousOrSame(weekStart));
        long span = java.time.temporal.ChronoUnit.DAYS.between(gridStart, month.atEndOfMonth()) + 1;
        weeks = (int) Math.ceil(span / 7.0);

        setPreferredSize(new Dimension(880, HEADER + weeks * 92 + TRAY));
        setMinimumSize(new Dimension(520, HEADER + weeks * 64 + TRAY));
        getAccessibleContext().setAccessibleName(month + ", tasks by due date");
        if (edits != null) install(edits);
    }

    // ------------------------------------------------------------------ layout

    private int columnWidth() { return Math.max(60, getWidth() / 7); }
    private int rowHeight() { return Math.max(52, (getHeight() - HEADER - TRAY) / Math.max(1, weeks)); }

    /**
     * Recomputes cell and chip rectangles. Called from paintComponent, and by
     * tests directly so a drag can be aimed without painting first.
     */
    void relayout() {
        cells.clear();
        chips.clear();
        int colW = columnWidth(), rowH = rowHeight();
        for (int week = 0; week < weeks; week++) {
            for (int day = 0; day < 7; day++) {
                var date = gridStart.plusDays(week * 7L + day);
                cells.put(date, new Rectangle(day * colW, HEADER + week * rowH, colW, rowH));
            }
        }
        trayBounds = new Rectangle(0, HEADER + weeks * rowH, getWidth(), TRAY);

        var byDate = new TreeMap<LocalDate, List<Task>>();
        var undated = new ArrayList<Task>();
        for (var task : tasks) {
            // Placed on the day it wants attention — the plan if there is one,
            // otherwise the deadline. Dragging moves the plan (#25).
            if (task.workOn() == null) undated.add(task);
            else byDate.computeIfAbsent(task.workOn(), d -> new ArrayList<>()).add(task);
        }
        byDate.forEach((date, list) -> {
            var cell = cells.get(date);
            if (cell == null) return;
            int room = Math.max(0, (cell.height - DAY_LABEL - 4) / (CHIP + CHIP_GAP));
            for (int i = 0; i < list.size() && i < room; i++) {
                var task = list.get(i);
                chips.add(new Chip(task.id(),
                    new Rectangle(cell.x + 3, cell.y + DAY_LABEL + i * (CHIP + CHIP_GAP), cell.width - 6, CHIP),
                    list.size() > room && i == room - 1 ? "+" + (list.size() - room + 1) + " more" : task.title(),
                    colourOf(task), task.status() == TaskStatus.DONE, 1));
            }
        });
        int x = 6;
        for (var task : undated) {
            // Width from the label's own metrics, not a per-character guess: the
            // guess under-measured, so the chip was narrower than the title it
            // held and the strip clipped a readable title in half.
            int width = Math.min(TRAY_CHIP_MAX,
                Math.max(70, CHIP_METRICS.stringWidth(task.title()) + CHIP_TEXT_X * 2));
            // The backlog is a strip, not a cell, so a title that will not fit on
            // one line wraps instead of being cut off at the edge.
            int lines = wrap(task.title(), textRoom(width), TRAY_LINES).size();
            if (x + width > getWidth() - 6) break;
            chips.add(new Chip(task.id(), new Rectangle(x, trayBounds.y + 24, width, chipHeight(lines)),
                task.title(), colourOf(task), task.status() == TaskStatus.DONE, lines));
            x += width + 6;
        }
    }

    /** How much of a chip's width a label may actually use. */
    private static int textRoom(int chipWidth) { return chipWidth - CHIP_TEXT_X - CHIP_TEXT_EDGE; }

    /** A chip's height: one row, plus the caption face's own leading for each line after the first. */
    private static int chipHeight(int lines) { return CHIP + (lines - 1) * CHIP_METRICS.getHeight(); }

    /**
     * A title broken onto at most {@code maxLines} lines that each fit {@code width}.
     *
     * Breaks at spaces, and inside a word only when that word is wider than the
     * chip on its own — a wrapped chip must not spill past its own edge either.
     * Whatever is left of the last line is elided, so wrapping degrades into a
     * readable cut rather than a silent one.
     */
    private static List<String> wrap(String text, int width, int maxLines) {
        var lines = new ArrayList<String>();
        var rest = text.strip();
        while (lines.size() < maxLines - 1 && CHIP_METRICS.stringWidth(rest) > width) {
            int cut = rest.length();
            // Back up to the last space that still leaves the line inside the chip.
            while (cut > 0 && CHIP_METRICS.stringWidth(rest.substring(0, cut)) > width)
                cut = rest.lastIndexOf(' ', cut - 1);
            if (cut <= 0) break;   // one word wider than the chip: let the elision below take it
            lines.add(rest.substring(0, cut).stripTrailing());
            rest = rest.substring(cut).stripLeading();
        }
        if (!rest.isEmpty() || lines.isEmpty()) lines.add(elide(rest, width));
        return lines;
    }

    /**
     * The longest prefix of {@code text} that fits {@code width}, ending in an ellipsis.
     *
     * Cutting at the pixel boundary is what made a long title end mid-glyph
     * ("Return library boc"); this walks whole code points instead, so the last
     * thing on the line is always a complete character followed by the "…" that
     * says there was more. Trailing space goes, so the ellipsis never stands off
     * from the word it follows.
     */
    private static String elide(String text, int width) {
        if (CHIP_METRICS.stringWidth(text) <= width) return text;
        int room = width - CHIP_METRICS.stringWidth(ELLIPSIS);
        var head = new StringBuilder();
        int used = 0;
        for (int i = 0; i < text.length(); ) {
            int code = text.codePointAt(i);
            var glyph = new String(Character.toChars(code));
            int advance = CHIP_METRICS.stringWidth(glyph);
            if (used + advance > room) break;
            head.append(glyph);
            used += advance;
            i += Character.charCount(code);
        }
        while (head.length() > 0 && Character.isWhitespace(head.charAt(head.length() - 1)))
            head.setLength(head.length() - 1);
        return head.append(ELLIPSIS).toString();
    }

    private Color colourOf(Task task) {
        var tag = task.tagId() == null ? null : tags.get(task.tagId());
        return tag == null ? Theme.LINE : new Color(tag.colour());
    }

    /** The day cell under a point, or null when the point is outside the grid. */
    LocalDate dateAt(Point at) {
        for (var entry : cells.entrySet()) if (entry.getValue().contains(at)) return entry.getKey();
        return null;
    }

    /** The task under a point, or null. */
    UUID taskAt(Point at) {
        for (var chip : chips) if (chip.bounds().contains(at)) return chip.task();
        return null;
    }

    /** Centre of a day cell, so a drag can be aimed at a date rather than a pixel. */
    Point centreOf(LocalDate date) {
        var cell = cells.get(date);
        return cell == null ? null : new Point(cell.x + cell.width / 2, cell.y + cell.height - 6);
    }

    Point centreOfTray() { return new Point(trayBounds.width / 2, trayBounds.y + 12); }

    Point pointOn(UUID task) {
        for (var chip : chips) if (chip.task().equals(task)) return new Point(chip.bounds().x + 6, chip.bounds().y + 8);
        return null;
    }

    // ------------------------------------------------------------------- input

    private void install(Edits edits) {
        var handler = new MouseAdapter() {
            /** The day the picked-up chip was drawn on, or null for the undated strip. */
            private LocalDate from;
            @Override public void mousePressed(MouseEvent e) {
                relayout();
                dragging = taskAt(e.getPoint());
                from = dateAt(e.getPoint());
                cursor = e.getPoint();
                repaint();
            }
            @Override public void mouseDragged(MouseEvent e) {
                if (dragging == null) return;
                cursor = e.getPoint();
                hovered = dateAt(cursor);
                overTray = trayBounds.contains(cursor);
                repaint();
            }
            @Override public void mouseReleased(MouseEvent e) {
                if (dragging == null) return;
                var task = dragging;
                var target = dateAt(e.getPoint());
                boolean tray = trayBounds.contains(e.getPoint());
                dragging = null; hovered = null; overTray = false; cursor = null;
                repaint();
                // A drop that lands nowhere is a no-op, not a task with no date:
                // releasing outside the component has to be a way to change your mind.
                // Put back where it was picked up is not a move either: a click on
                // a chip would otherwise plan a task for the day it was only due.
                if (target != null && !target.equals(from)) edits.reschedule(task, target);
                else if (tray && from != null) edits.reschedule(task, null);
            }
            @Override public void mouseClicked(MouseEvent e) {
                // A day is where a task made from the calendar belongs: the
                // form opens due that day rather than today (#67).
                if (e.getClickCount() != 2 || !SwingUtilities.isLeftMouseButton(e)) return;
                relayout();
                if (taskAt(e.getPoint()) != null) return;
                var date = dateAt(e.getPoint());
                if (date != null) edits.create(date);
            }
        };
        addMouseListener(handler);
        addMouseMotionListener(handler);
    }

    @Override public String getToolTipText(MouseEvent event) {
        relayout();
        var task = taskAt(event.getPoint());
        if (task != null) return tasks.stream().filter(t -> t.id().equals(task)).findFirst()
            .map(t -> t.title() + (t.due() == null ? "  ·  no due date" : "  ·  due " + t.due())).orElse(null);
        var date = dateAt(event.getPoint());
        return date == null ? null : date.toString() + "  ·  double-click to add a task, or drop one here to move it";
    }

    // ------------------------------------------------------------------ paint

    @Override protected void paintComponent(Graphics graphics) {
        relayout();
        var g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int colW = columnWidth();

        g.setFont(Theme.captionFont());
        g.setColor(Theme.MUTED);
        for (int day = 0; day < 7; day++)
            g.drawString(weekStart.plus(day).getDisplayName(TextStyle.SHORT, Locale.ENGLISH).toUpperCase(Locale.ENGLISH),
                day * colW + 6, 14);

        for (var entry : cells.entrySet()) {
            var date = entry.getKey();
            var cell = entry.getValue();
            boolean outside = date.getMonth() != month.getMonth();
            if (date.equals(hovered)) { g.setColor(Theme.shade(Theme.PANEL, Theme.DARK ? 22 : -14)); g.fillRect(cell.x, cell.y, cell.width, cell.height); }
            else if (date.equals(today)) { g.setColor(Theme.shade(Theme.PANEL, Theme.DARK ? 10 : -6)); g.fillRect(cell.x, cell.y, cell.width, cell.height); }
            g.setColor(Theme.LINE);
            g.drawRect(cell.x, cell.y, cell.width, cell.height);
            g.setFont(Theme.captionFont());
            g.setColor(date.equals(today) ? Theme.CYAN : outside ? Theme.shade(Theme.MUTED, Theme.DARK ? -30 : 30) : Theme.MUTED);
            g.drawString(String.valueOf(date.getDayOfMonth()), cell.x + 5, cell.y + 11);
        }

        g.setColor(Theme.LINE);
        g.drawRect(trayBounds.x, trayBounds.y, Math.max(0, trayBounds.width - 1), trayBounds.height - 1);
        if (overTray) { g.setColor(Theme.shade(Theme.PANEL, Theme.DARK ? 22 : -14)); g.fillRect(trayBounds.x + 1, trayBounds.y + 1, trayBounds.width - 2, trayBounds.height - 2); }
        g.setFont(Theme.captionFont());
        g.setColor(Theme.MUTED);
        g.drawString("No date · drag onto a day to plan it", 6, trayBounds.y + 14);

        for (var chip : chips) paintChip(g, chip);

        if (dragging != null && cursor != null) {
            var held = chips.stream().filter(c -> c.task().equals(dragging)).findFirst().orElse(null);
            if (held != null) {
                var ghost = new Chip(held.task(),
                    new Rectangle(cursor.x - 8, cursor.y - 8, held.bounds().width, held.bounds().height),
                    held.label(), held.colour(), held.done(), held.lines());
                g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.75f));
                paintChip(g, ghost);
            }
        }
        g.dispose();
    }

    private void paintChip(Graphics2D g, Chip chip) {
        var box = chip.bounds();
        g.setColor(Theme.shade(Theme.PANEL, Theme.DARK ? 16 : -10));
        g.fillRoundRect(box.x, box.y, box.width, box.height, Theme.RADIUS, Theme.RADIUS);
        g.setColor(chip.colour());
        g.fillRect(box.x, box.y, 3, box.height);
        g.setFont(Theme.captionFont());
        g.setColor(chip.done() ? Theme.shade(Theme.MUTED, Theme.DARK ? -20 : 20) : Theme.TEXT);
        var clip = g.getClip();
        g.clipRect(box.x + 5, box.y, box.width - 7, box.height);
        // A day cell takes the one line it was measured for; the backlog wraps,
        // and the ghost of a chip being dragged keeps whatever shape it had.
        var lines = chip.lines() > 1
            ? wrap(chip.label(), textRoom(box.width), chip.lines())
            : List.of(elide(chip.label(), textRoom(box.width)));
        for (int line = 0; line < lines.size(); line++)
            g.drawString(lines.get(line), box.x + CHIP_TEXT_X, box.y + 12 + line * CHIP_METRICS.getHeight());
        g.setClip(clip);
    }
}
