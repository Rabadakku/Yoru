package dev.yoru.ui;

import dev.yoru.application.Tracker;
import dev.yoru.domain.Model.HabitKind;
import dev.yoru.domain.Model.Task;
import dev.yoru.domain.Model.ThemeId;
import java.awt.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.swing.*;

/**
 * Every page keeps its text in view with every name at its longest, at every
 * text size Settings offers (#30, #31).
 *
 * The visual system's acceptance asks for long names and 200% text, and the
 * page renders only ever showed the fixture's short names at designed size.
 * Swing shortens a label or button that no longer fits to "…" without a word,
 * and a component pushed past the edge of whatever holds it is simply cut off.
 * Both are found here from the laid-out component tree, for each size at the
 * desktop size and at that size's smallest window, so a change that brings
 * either back fails the suite.
 *
 * A name shortened on purpose passes when its tooltip carries the whole name,
 * because the reader can still get at it.
 *
 *   java -Djava.awt.headless=true [-Dtextfit.size=200] -cp build/classes dev.yoru.ui.TextFitTest [render-dir]
 *
 * Given a directory, every page is also written there as a PNG to review by eye.
 * Invented data only.
 */
public final class TextFitTest {
    /** The desktop size every step is checked at, besides the step's own smallest window. */
    private static final Dimension DESKTOP = new Dimension(1280, 900);

    // The longest names the model accepts (Model.requireName).
    private static final int ACTIVITY_NAME = 60, TAG_NAME = 40, TASK_TITLE = 160, HABIT_NAME = 60, LIST_NAME = 40;

    public static void main(String[] args) throws Exception {
        Path renders = args.length > 0 ? Path.of(args[0]) : null;
        if (renders != null) Files.createDirectories(renders);
        // Every offered size, or one of them: -Dtextfit.size=200.
        String only = System.getProperty("textfit.size");
        List<Integer> steps = only == null ? TextSize.STEPS : List.of(Integer.parseInt(only));
        var problems = new LinkedHashSet<String>();
        int[] inspected = {0};
        SwingUtilities.invokeAndWait(() -> {
            try {
                for (int step : steps) {
                    TextSize.use(step);
                    Theme.apply(ThemeId.MIDNIGHT);
                    var desktop = Preview.trackerApp(ThemeId.MIDNIGHT, DESKTOP.width, DESKTOP.height, TextFitTest::lengthen);
                    // Larger text widens the navigation bar, and the window's
                    // smallest size with it: check the sizes the window can take.
                    var smallest = desktop.windowMinimum();
                    desktop.setSize(Math.max(DESKTOP.width, smallest.width), Math.max(DESKTOP.height, smallest.height));
                    inspected[0] += pages(desktop, step, renders, problems);
                    var narrow = Preview.trackerApp(ThemeId.MIDNIGHT, smallest.width, smallest.height, TextFitTest::lengthen);
                    inspected[0] += pages(narrow, step, renders, problems);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        String sizes = steps.stream().map(step -> step + "%").collect(java.util.stream.Collectors.joining(", "));
        if (!problems.isEmpty()) {
            problems.forEach(problem -> System.out.println("  " + problem));
            System.out.println("FAIL: " + problems.size() + " pieces of text do not fit with the longest names at " + sizes);
            // The windows built here leave Swing's threads running, so an
            // uncaught failure would hang the suite instead of stopping it.
            System.exit(1);
        }
        System.out.println("PASS: " + inspected[0] + " labels and buttons fit with the longest names at "
            + sizes + " text, at the desktop size and each size's smallest window");
        System.exit(0);
    }

    /** Checks, and optionally renders, every page of {@code app} at its current size. */
    private static int pages(YoruApp app, int step, Path renders, Set<String> problems) throws Exception {
        int inspected = 0;
        int width = app.getWidth(), height = app.getHeight();
        for (String page : Preview.PAGES) {
            var nav = Preview.button(app, page);
            if (nav == null) { problems.add(page + ": no navigation button"); continue; }
            nav.doClick();
            settle(app);
            inspected += inspect(app, app, page + " at " + step + "%, " + width + "×" + height, problems);
            if (renders != null)
                Preview.write(renders, page.toLowerCase(Locale.ROOT) + "-" + step + "-" + width, app, width, height);
            if (page.equals("Tasks")) {
                Preview.button(app, "tasks.select").doClick();
                Preview.button(app, "tasks.bulk.all").doClick();
                settle(app);
                inspected += inspect(app, app, "Task selection at " + step + "%, " + width + "×" + height, problems);
                if (renders != null) Preview.write(renders, "tasks-bulk-" + step + "-" + width, app, width, height);
                Preview.button(app, "tasks.select").doClick();
            }
        }
        return inspected;
    }

    /** Every name the fixture has, lengthened to the most the model accepts. */
    private static void lengthen(Tracker tracker) throws Exception {
        var phrases = List.of("Organic chemistry problem sets", "Conversational Japanese practice",
            "Data structures and algorithms review", "Strength training and mobility work");
        var activities = tracker.state().activities();
        for (int i = 0; i < activities.size(); i++)
            tracker.renameActivity(activities.get(i).id(),
                longest((i + 1) + ". " + phrases.get(i % phrases.size()), ACTIVITY_NAME));
        for (var tag : tracker.state().tags())
            tracker.editTag(tag.id(), longest(tag.name() + " for the second-year seminar", TAG_NAME), tag.colour());
        for (var task : tracker.state().tasks())
            tracker.updateTask(new Task(task.id(), task.activityId(), task.tagIds(),
                longest(task.title() + ", with every exercise at the end of the section checked against the worked solutions", TASK_TITLE),
                task.notes(), task.due(), task.status(), task.source(), task.createdAt(), task.order(), task.plannedFor(),
                task.pageIds(), task.listId()));
        for (var list : tracker.state().lists())
            tracker.editList(list.id(), longest(list.name() + " for the autumn term", LIST_NAME), list.colour());
        for (var habit : tracker.state().habits())
            tracker.renameHabit(habit.id(), longest(habit.name() + " before the end of every study day", HABIT_NAME));
        // And every time-since counter near its widest, counted in calendar
        // units: "10y 11mo 28d 23h 59m" beside Start again and ⋯ is wider than
        // the column at 150% text, and the ⋯ was cut off.
        for (var habit : tracker.state().habits())
            if (habit.kind() == HabitKind.TIME_SINCE)
                tracker.editHabitStart(habit.id(), tracker.now().atZone(ZoneId.of(habit.zone()))
                    .minusYears(10).minusMonths(11).minusDays(28).minusHours(23).minusMinutes(59)
                    .truncatedTo(ChronoUnit.MINUTES).toInstant());
    }

    /** {@code words} repeated to exactly {@code limit} characters, less any space it ends on. */
    private static String longest(String words, int limit) {
        var name = new StringBuilder(words);
        while (name.length() < limit) name.append(' ').append(words);
        return name.substring(0, limit).strip();
    }

    /**
     * Lays the window out until nothing moves, as a shown window does.
     *
     * A label that wraps is measured against a width its container only learns
     * during the same pass, so one pass measures it for the wrong width. The app
     * lays a window out again whenever a size changes; with no screen here that
     * never happens by itself, so it is repeated by hand.
     */
    private static void settle(Container window) {
        String before = null;
        for (int pass = 0; pass < 6; pass++) {
            invalidate(window);
            Preview.layout(window);
            String now = bounds(window, new StringBuilder()).toString();
            if (now.equals(before)) return;
            before = now;
        }
    }

    private static void invalidate(Component c) {
        c.invalidate();
        if (c instanceof Container nested) for (var child : nested.getComponents()) invalidate(child);
    }

    private static StringBuilder bounds(Component c, StringBuilder out) {
        out.append(c.getX()).append(',').append(c.getY()).append(',').append(c.getWidth()).append(',').append(c.getHeight()).append(';');
        if (c instanceof Container nested) for (var child : nested.getComponents()) bounds(child, out);
        return out;
    }

    /** Checks every visible label and button under {@code root}; returns how many it checked. */
    private static int inspect(Container root, Component window, String where, Set<String> problems) {
        int inspected = 0;
        for (var child : root.getComponents()) {
            if (!child.isVisible()) continue;
            if (child instanceof JComponent c && c.getWidth() > 0 && c.getHeight() > 0) {
                if (c instanceof JComboBox<?> || c instanceof JSpinner) {
                    inspected++;
                    String squeezed = squeezed(c);
                    if (squeezed != null) problems.add(where + ": " + squeezed);
                    continue;
                }
                String text = text(c);
                if (text != null) {
                    inspected++;
                    String quoted = "\"" + (text.length() > 48 ? text.substring(0, 47) + "…" : text) + "\"";
                    if (cut(c, text) && !whole(c, text)) problems.add(where + ": " + quoted + " is cut short");
                    String edge = pastEdge(c, window);
                    if (edge != null) problems.add(where + ": " + quoted + " runs past " + edge);
                }
            }
            if (child instanceof Container nested) inspected += inspect(nested, window, where, problems);
        }
        return inspected;
    }

    /**
     * How a combo box or spinner is too small for the value it shows, or null.
     *
     * Its height must hold a line of its text. Its width is checked only when it
     * was set by hand: a box that stretches with its row, like the activity
     * picker, may show a long name shortened, and its list shows all of it.
     */
    private static String squeezed(JComponent c) {
        JComponent field = c instanceof JSpinner spinner && spinner.getEditor() instanceof JSpinner.DefaultEditor editor
            ? editor.getTextField() : c;
        String what = c instanceof JComboBox<?> combo
            ? "the box showing \"" + combo.getSelectedItem() + "\""
            : "the spinner showing " + ((JSpinner) c).getValue();
        var insets = field.getInsets();
        var metrics = field.getFontMetrics(field.getFont());
        int room = field.getHeight() - insets.top - insets.bottom, line = metrics.getAscent() + metrics.getDescent();
        if (room + 1 < line) return what + " is shorter than its text (" + room + " < " + line + ")";
        boolean handSized = c.isPreferredSizeSet() || c.isMaximumSizeSet() && c.getMaximumSize().width < Short.MAX_VALUE;
        if (c instanceof JComboBox<?> combo && handSized) {
            int natural = combo.getUI().getMinimumSize(combo).width;
            if (c.getWidth() + 1 < natural) return what + " is narrower than its value (" + c.getWidth() + " < " + natural + ")";
        }
        return null;
    }

    /** The plain text a label or button shows, or a read-only wrapped text area, or null for anything else. HTML text wraps and is left out. */
    private static String text(JComponent c) {
        String text = c instanceof JLabel l ? l.getText() : c instanceof AbstractButton b ? b.getText()
            : c instanceof JTextArea area && !area.isEditable() && area.getLineWrap() ? area.getText() : null;
        if (text == null || text.isBlank() || text.regionMatches(true, 0, "<html>", 0, 6)) return null;
        return text;
    }

    /** Whether Swing shortens {@code text} to fit, or its line is taller than the room it has. */
    private static boolean cut(JComponent c, String text) {
        // A wrapping label or text area is cut when its lines need more height than it was given.
        if (c instanceof WrappingLabel wrapping) return wrapping.heightFor(wrapping.getWidth()) > wrapping.getHeight() + 1;
        if (c instanceof JTextArea area) {
            var insets = area.getInsets();
            var root = area.getUI().getRootView(area);
            root.setSize(area.getWidth() - insets.left - insets.right, Integer.MAX_VALUE);
            float needed = root.getPreferredSpan(javax.swing.text.View.Y_AXIS) + insets.top + insets.bottom;
            root.setSize(area.getWidth() - insets.left - insets.right, area.getHeight() - insets.top - insets.bottom);
            return needed > area.getHeight() + 1;
        }
        var insets = c.getInsets();
        var view = new Rectangle(insets.left, insets.top,
            c.getWidth() - insets.left - insets.right, c.getHeight() - insets.top - insets.bottom);
        var metrics = c.getFontMetrics(c.getFont());
        String shown;
        if (c instanceof JLabel l) {
            shown = SwingUtilities.layoutCompoundLabel(l, metrics, text, l.getIcon(),
                l.getVerticalAlignment(), l.getHorizontalAlignment(),
                l.getVerticalTextPosition(), l.getHorizontalTextPosition(),
                view, new Rectangle(), new Rectangle(), l.getIconTextGap());
        } else {
            var b = (AbstractButton) c;
            shown = SwingUtilities.layoutCompoundLabel(b, metrics, text, b.getIcon(),
                b.getVerticalAlignment(), b.getHorizontalAlignment(),
                b.getVerticalTextPosition(), b.getHorizontalTextPosition(),
                view, new Rectangle(), new Rectangle(), b.getIconTextGap());
        }
        return !shown.equals(text) || metrics.getAscent() + metrics.getDescent() > view.height + 1;
    }

    /** A shortened name is still readable when its tooltip says the whole of it. */
    private static boolean whole(JComponent c, String text) {
        String tip = c.getToolTipText();
        return tip != null && tip.contains(text);
    }

    /**
     * What {@code c} runs past, or null when it fits: the edge of any container
     * holding it, or the side of a scrolling view. A page scrolls down, so a
     * component below the fold is fine; nothing may run off to the side.
     */
    private static String pastEdge(Component c, Component window) {
        var bounds = new Rectangle(0, 0, c.getWidth(), c.getHeight());
        for (Component at = c; at != window && at.getParent() != null; at = at.getParent()) {
            bounds.translate(at.getX(), at.getY());
            var holder = at.getParent();
            if (holder instanceof JViewport view) {
                if (bounds.x < -1 || bounds.x + bounds.width > view.getWidth() + 1) return "the side of its scrolling view";
                bounds = bounds.intersection(new Rectangle(0, 0, view.getWidth(), view.getHeight()));
                if (bounds.isEmpty()) return null;
            } else if (bounds.x < -1 || bounds.y < -1
                    || bounds.x + bounds.width > holder.getWidth() + 1
                    || bounds.y + bounds.height > holder.getHeight() + 1) {
                String name = holder.getName() != null && !holder.getName().isEmpty() ? holder.getName() : holder.getClass().getName();
                return "the edge of its " + name + " (" + bounds + " in " + holder.getWidth() + "×" + holder.getHeight() + ")";
            }
        }
        return null;
    }
}
