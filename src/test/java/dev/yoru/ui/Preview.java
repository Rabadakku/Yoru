package dev.yoru.ui;

import dev.yoru.application.*;
import dev.yoru.domain.Model.*;
import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.time.*;
import java.util.Locale;
import java.util.UUID;

/**
 * Renders every page in every theme to a PNG without a display, so UI changes
 * can be reviewed in a diff or by eye without launching the app.
 *
 *   java -cp build/classes dev.yoru.ui.Preview <output-dir> [width] [height]
 *
 * Files are named {@code <page>-<theme>.png}. One theme per run could not answer
 * the only question the ticket asks — is every page right in every theme — since
 * a reviewer had to remember which file belonged to which palette, and an
 * all-theme run wrote four files under the same name.
 *
 * Populated with representative fixture data: an app full of empty states hides
 * exactly the layout and contrast problems this is meant to catch. The window is
 * rebuilt for each theme rather than recoloured, because components keep the
 * colours they were built with.
 *
 * Each page is rendered twice: at a comfortable desktop size, and at the
 * window's own minimum. A page that only holds together when there is room to
 * spare is exactly the reflow bug a still image is good at finding, and the
 * minimum is the size the frame actually allows.
 */
public final class Preview {
    static final String[] PAGES = {"Today","Tasks","Pages","Habits","Schedule","Collection","Game","Data","Settings"};
    /** The frame's minimum, so the small render is a size the app really opens at. */
    private static final int MIN_WIDTH = 900, MIN_HEIGHT = 640;

    static JButton button(Container root, String name) {
        for (var child : root.getComponents()) {
            if (child instanceof JButton b && (name.equals(b.getName()) || name.equals(b.getText()))) return b;
            if (child instanceof Container nested) { var found = button(nested, name); if (found != null) return found; }
        }
        return null;
    }
    static void layout(Container c) {
        // Twice, because a row that wraps only learns how tall it is once it has
        // been given a width: the first pass settles the widths and the second
        // the heights that follow from them. One pass draws the focus card's
        // controls as the running app draws them for a single frame and no
        // longer — there, a width that changes makes the row ask again.
        pass(c);
        pass(c);
    }

    private static void pass(Container c) {
        c.doLayout();
        for (var child : c.getComponents()) if (child instanceof Container nested) pass(nested);
    }

    /** Steps the animated scenes so previews show motion, not the idle pose. */
    private static void advanceScenes(Container c, int frames) {
        for (var child : c.getComponents()) {
            if (child instanceof TrainerScene trainer) for (int i = 0; i < frames; i++) trainer.advance(true, 60);
            else if (child instanceof BuddyScene buddy) for (int i = 0; i < frames; i++) buddy.advance(true);
            else if (child instanceof Container nested) advanceScenes(nested, frames);
        }
    }

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args.length > 0 ? args[0] : "build/preview");
        int width = args.length > 1 ? Integer.parseInt(args[1]) : 1280;
        int height = args.length > 2 ? Integer.parseInt(args[2]) : 1000;
        renderAll(out, width, height);
        // The windows' timers would keep the event thread, and so the JVM, alive.
        System.exit(0);
    }

    /** Every render into {@code out}. PreviewInventoryTest holds the result to the full list. */
    static void renderAll(Path out, int width, int height) throws Exception {
        Files.createDirectories(out);

        SwingUtilities.invokeAndWait(() -> {
            try {
                for (var theme : ThemeId.values()) {
                    // Before the window is built: a component keeps the colours it
                    // was constructed with, so recolouring one afterwards changes
                    // nothing on screen.
                    Theme.apply(theme);
                    renderEveryPage(out, theme, trackerApp(theme, width, height), width, height, "");
                    // And at the minimum, where a two-column page has to reflow.
                    renderEveryPage(out, theme, trackerApp(theme, MIN_WIDTH, MIN_HEIGHT),
                        MIN_WIDTH, MIN_HEIGHT, "-min");
                    System.out.println("Rendered " + PAGES.length + " pages plus the task calendar in "
                        + theme + " at " + width + "x" + height + " and " + MIN_WIDTH + "x" + MIN_HEIGHT);
                }
                // Every page, plus the arranging and calendar views, in every theme
                // and at both sizes.
                System.out.println("Wrote " + (PAGES.length + 3) * ThemeId.values().length * 2
                    + " renders to " + out);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    /** Every page, in the theme that is already applied, named with that theme. */
    private static void renderEveryPage(Path out, ThemeId theme, YoruApp app, int width, int height,
                                        String sizeSuffix) throws Exception {
        String suffix = "-" + theme.name().toLowerCase(Locale.ROOT) + sizeSuffix;
        for (String page : PAGES) {
            var nav = button(app, page);
            if (nav != null) nav.doClick();
            if (page.equals("Pages")) app.openNote(app.tracker().state().notes().pages().getFirst().id());
            layout(app);
            advanceScenes(app, 26);
            write(out, page.toLowerCase(Locale.ROOT) + suffix, app, width, height);
            if (page.equals("Pages")) {
                button(app, "pages.mode").doClick();
                layout(app);
                write(out, "pages-reading" + suffix, app, width, height);
                button(app, "pages.mode").doClick();
            }
        }
        // The arranging state of the Collection page: pick-up marker and
        // hint visible, so the mode is reviewed as an image too.
        var collectionTab = button(app, "Collection");
        if (collectionTab != null) {
            collectionTab.doClick();
            var arrange = button(app, "Arrange");
            if (arrange != null) {
                arrange.doClick();
                layout(app);
                write(out, "collection-arranging" + suffix, app, width, height);
            }
        }
        // The calendar is a view of the Tasks page rather than a page of its
        // own, so the page loop above never reaches it.
        var tasksTab = button(app, "Tasks");
        if (tasksTab != null) {
            tasksTab.doClick();
            var calendarView = button(app, "view.calendar");
            if (calendarView != null) {
                calendarView.doClick();
                layout(app);
                write(out, "tasks-calendar" + suffix, app, width, height);
            }
        }
    }

    /** Paints the window into one PNG; the only place an image is produced. */
    static void write(Path out, String name, YoruApp app, int width, int height) throws Exception {
        layout(app);
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        app.paint(g);
        g.dispose();
        ImageIO.write(image, "png", out.resolve(name + ".png").toFile());
    }

    /** A tracker full of representative data, in the given theme, inside a window. */
    static YoruApp trackerApp(ThemeId themeId, int width, int height) throws Exception {
        return trackerApp(themeId, width, height, tracker -> { });
    }

    /** A change made to the fixture before its window is built. */
    interface Adjust { void accept(Tracker tracker) throws Exception; }

    /** The same window, with {@code adjust} applied to the fixture first; TextFitTest lengthens every name. */
    static YoruApp trackerApp(ThemeId themeId, int width, int height, Adjust adjust) throws Exception {
        Repository memory = new Repository() {
            State state = State.empty();
            public State load() { return state; }
            public void save(State next) { state = next; }
            public void close() { }
        };
        var tracker = new Tracker(memory, Clock.systemUTC());
        for (String name : new String[]{"Study","Coding","Japanese","Workout"}) tracker.addActivity(name, 0);

        var activities = tracker.state().activities();
        var study = activities.getFirst().id();
        var zone = ZoneId.systemDefault();
        var today = LocalDate.now();

        // Recorded time across several days so the heat map and charts have shape.
        Instant cursor = Instant.now().minusSeconds(60);
        for (int day = 1; day <= 9; day++) {
            var end = today.minusDays(day).atTime(19, 0).atZone(zone).toInstant();
            tracker.log(activities.get(day % activities.size()).id(),
                end.minusSeconds(1800L + day * 900L), end);
        }
        tracker.log(study, cursor.minusSeconds(5400), cursor);

        tracker.plan(study, today.atTime(10, 0).atZone(zone).toInstant(),
            today.atTime(11, 30).atZone(zone).toInstant());
        // A weekly template, so the schedule grid shows the dashed
        // repeating blocks next to the solid recorded ones.
        tracker.repeat(study, java.time.DayOfWeek.MONDAY,
            java.time.LocalTime.of(9, 0), java.time.LocalTime.of(10, 30));
        tracker.repeat(activities.get(2).id(), java.time.DayOfWeek.WEDNESDAY,
            java.time.LocalTime.of(14, 15), java.time.LocalTime.of(15, 45));
        tracker.repeat(activities.get(1).id(), java.time.DayOfWeek.FRIDAY,
            java.time.LocalTime.of(13, 0), java.time.LocalTime.of(14, 0));
        tracker.plan(activities.get(1).id(), today.atTime(14, 0).atZone(zone).toInstant(),
            today.atTime(15, 30).atZone(zone).toInstant());

        // Tags first, so the board renders tagged and untagged rows side by
        // side; an overdue and a due-today row cover the date colours.
        var cs=tracker.addTag("Reading", 0x90D8DA);
        var jpn=tracker.addTag("Language", 0xE8B24C);
        var now=Instant.now();
        tracker.addTasks(java.util.List.of(
            new Task(UUID.randomUUID(), study, cs.id(), "Read chapter 4", "Review the worked examples before Thursday.", today.plusDays(1), TaskStatus.TODO, "reading-list.txt", now, 0),
            new Task(UUID.randomUUID(), activities.get(1).id(), cs.id(), "Finish lab 3", "Submit through the course portal.", today.minusDays(2), TaskStatus.DOING, "Manual entry", now, 1),
            new Task(UUID.randomUUID(), activities.get(2).id(), jpn.id(), "Kanji review deck", "", today, TaskStatus.TODO, "Manual entry", now, 2),
            new Task(UUID.randomUUID(), null, null, "Order textbook", "", null, TaskStatus.DONE, "Manual entry", now, 3),
            new Task(UUID.randomUUID(), study, null, "Ask about open lab hours", "", null, TaskStatus.TODO, "Manual entry", now, 4),
            new Task(UUID.randomUUID(), study, cs.id(), "HW 3.1 polynomials", "", today.plusDays(4), TaskStatus.TODO, "Manual entry", now, 5),
            new Task(UUID.randomUUID(), activities.get(2).id(), jpn.id(), "Kanji quiz", "", today.plusDays(6), TaskStatus.TODO, "Manual entry", now, 6),
            new Task(UUID.randomUUID(), study, cs.id(), "Essay rough draft", "", today.plusDays(7), TaskStatus.TODO, "Manual entry", now, 7),
            new Task(UUID.randomUUID(), null, null, "Return library books", "", null, TaskStatus.TODO, "Manual entry", now, 8),
            new Task(UUID.randomUUID(), study, cs.id(), "Practice quiz", "", today.plusDays(12), TaskStatus.TODO, "Manual entry", now, 9),
            new Task(UUID.randomUUID(), activities.get(2).id(), jpn.id(), "Grammar review", "", today.minusDays(4), TaskStatus.DONE, "Manual entry", now, 10)));

        var folder = tracker.pages().createFolder(null, "Notebook");
        var note = tracker.pages().createPage(folder.id(), "Study notes", """
            # Study notes

            A quiet place to connect **ideas**, tasks and time.
            Read [[Reference]] next, then write a short summary.

            ## Today
            - [x] Review the example
            - [ ] Try it independently

            > [!TIP] Make it stick
            > Explain the idea in your own words.

            ## Example
            ```java
            int minutes = 25;
            ```

            | Topic | Next step |
            | --- | --- |
            | Practice | Review notes |
            """);
        tracker.pages().createPage(folder.id(), "Reference", "# Reference\nReturn to [[Study notes#Today]].\n");
        tracker.pages().linkTask(tracker.state().tasks().getFirst().id(), note.id());

        tracker.addHabit("Evening reset", HabitKind.DAILY, zone, null);
        var habit = tracker.state().habits().getFirst().id();
        for (int day = 0; day < 9; day++) if (day != 3) tracker.checkIn(habit, today.minusDays(day), true);
        tracker.addHabit("Stretch", HabitKind.DAILY, zone, null);
        tracker.addHabit("Time since last soda", HabitKind.TIME_SINCE, zone,
            Instant.now().minusSeconds(18 * 86400 + 7340));

        // A save-backed collection: a trainer with a three-member party,
        // plus rewards still on their way, so the Collection page shows
        // both the storage view and the "on their way" card. An empty
        // vault would hide every layout problem these pages are meant
        // to catch.
        var raw = dev.yoru.game.Gen3Fixture.withTrainer(dev.yoru.game.Gen3Fixture.save(2, 4),
            "TESTER", 0, 12345, 54321);
        raw = dev.yoru.game.Gen3Fixture.withParty(raw, java.util.List.of(
            dev.yoru.game.Gen3Fixture.member(raw, 252, 12, 1),
            dev.yoru.game.Gen3Fixture.member(raw, 255, 10, 2),
            dev.yoru.game.Gen3Fixture.member(raw, 258, 8, 3)));
        // The game's own "received a starter" flag, so the Collection
        // page shows the storage rather than the empty state.
        raw = dev.yoru.game.Gen3Fixture.withFlag(raw, dev.yoru.game.Gen3Save.FLAG_SYS_POKEMON_GET);
        tracker.gameSaved(raw);
        tracker.bankReward(UUID.randomUUID(), 280, 5);
        tracker.bankReward(UUID.randomUUID(), 285, 6);
        // Sunday-first here, matching the tracker Yoru is replacing, so the
        // preview actually exercises a non-default week start (#26), and the
        // theme card shows this render's theme as the selected one.
        tracker.settings(new Settings(themeId, TrainerId.BRENDAN, 4, 300, java.time.DayOfWeek.SUNDAY));
        // A running session, so the animated states are what gets rendered
        // rather than everything frozen in its idle pose.
        tracker.start(study);
        adjust.accept(tracker);

        var app = new YoruApp(tracker, memory);
        app.setSize(width, height);
        return app;
    }
}
