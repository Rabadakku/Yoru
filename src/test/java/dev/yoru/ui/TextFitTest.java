package dev.yoru.ui;

import dev.yoru.application.Tracker;
import dev.yoru.domain.Model.Task;
import dev.yoru.domain.Model.ThemeId;
import java.awt.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.swing.*;

/**
 * Every page keeps its text in view with every name at its longest and text at
 * twice its designed size (#30, and #31 for 200% text).
 *
 * The visual system's acceptance asks for long names and 200% text, and the
 * page renders only ever showed the fixture's short names at designed size.
 * Swing shortens a label or button that no longer fits to "…" without a word,
 * and a component pushed past the edge of whatever holds it is simply cut off.
 * Both are found here from the laid-out component tree, at the desktop size and
 * at the window's minimum, so a change that brings either back fails the suite.
 *
 * A name shortened on purpose passes when its tooltip carries the whole name,
 * because the reader can still get at it.
 *
 *   java -Djava.awt.headless=true -cp build/classes dev.yoru.ui.TextFitTest [render-dir]
 *
 * Given a directory, every page is also written there as a PNG to review by eye.
 * Invented data only.
 */
public final class TextFitTest {
    /** How much larger than designed the text is drawn: 1 in the suite, 2 for the 200% review. */
    private static final float SCALE = Float.parseFloat(System.getProperty("textfit.scale", "1"));
    private static final List<Dimension> SIZES = List.of(new Dimension(1280, 900), new Dimension(900, 640));

    // The longest names the model accepts (Model.requireName).
    private static final int ACTIVITY_NAME = 60, TAG_NAME = 40, TASK_TITLE = 160, HABIT_NAME = 60;

    public static void main(String[] args) throws Exception {
        Path renders = args.length > 0 ? Path.of(args[0]) : null;
        if (renders != null) Files.createDirectories(renders);
        var problems = new LinkedHashSet<String>();
        int[] inspected = {0};
        SwingUtilities.invokeAndWait(() -> {
            try {
                Theme.textScale = SCALE;
                Theme.apply(ThemeId.MIDNIGHT);
                for (var size : SIZES) {
                    var app = Preview.trackerApp(ThemeId.MIDNIGHT, size.width, size.height, TextFitTest::lengthen);
                    for (String page : Preview.PAGES) {
                        var nav = Preview.button(app, page);
                        if (nav == null) { problems.add(page + ": no navigation button"); continue; }
                        nav.doClick();
                        settle(app);
                        inspected[0] += inspect(app, app, page + " at " + size.width + "×" + size.height, problems);
                        if (renders != null)
                            Preview.write(renders, page.toLowerCase(Locale.ROOT) + "-" + size.width, app, size.width, size.height);
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        if (!problems.isEmpty()) {
            problems.forEach(problem -> System.out.println("  " + problem));
            System.out.println("FAIL: " + problems.size() + " pieces of text do not fit with long names at "
                + Math.round(SCALE * 100) + "% text");
            // The windows built here leave Swing's threads running, so an
            // uncaught failure would hang the suite instead of stopping it.
            System.exit(1);
        }
        System.out.println("PASS: " + inspected[0] + " labels and buttons fit with the longest names at "
            + Math.round(SCALE * 100) + "% text, at 1280×900 and 900×640");
        System.exit(0);
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
            tracker.updateTask(new Task(task.id(), task.activityId(), task.tagId(),
                longest(task.title() + ", with every exercise at the end of the section checked against the worked solutions", TASK_TITLE),
                task.notes(), task.due(), task.status(), task.source(), task.createdAt(), task.order(), task.plannedFor()));
        for (var habit : tracker.state().habits())
            tracker.renameHabit(habit.id(), longest(habit.name() + " before the end of every study day", HABIT_NAME));
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

    /** The plain text a label or button shows, or null for anything else. HTML text wraps and is left out. */
    private static String text(JComponent c) {
        String text = c instanceof JLabel l ? l.getText() : c instanceof AbstractButton b ? b.getText() : null;
        if (text == null || text.isBlank() || text.regionMatches(true, 0, "<html>", 0, 6)) return null;
        return text;
    }

    /** Whether Swing shortens {@code text} to fit, or its line is taller than the room it has. */
    private static boolean cut(JComponent c, String text) {
        // A wrapping label is cut when its lines need more height than it was given.
        if (c instanceof WrappingLabel wrapping) return wrapping.heightFor(wrapping.getWidth()) > wrapping.getHeight() + 1;
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
