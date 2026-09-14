package dev.yoru.ui;

import dev.yoru.application.Repository;
import dev.yoru.application.Tracker;
import dev.yoru.domain.Model.State;
import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.time.Clock;
import java.time.Instant;
import static dev.yoru.ui.Theme.*;

/**
 * Renders representative dialogs to PNG.
 *
 * Dialogs were the one surface the page preview could not reach, and that is
 * exactly where a contrast regression shipped: JOptionPane builds its own
 * buttons, so they bypass Theme.button entirely and depend only on UIManager
 * defaults. Panes are constructed here the same way Dialogs constructs them
 * (PLAIN_MESSAGE, null icon, explicit options) so what is rendered is what ships.
 *
 *   java -cp build/classes dev.yoru.ui.DialogPreview <output-dir>
 */
public final class DialogPreview {

    private static void layout(Container c) {
        c.doLayout();
        for (var child : c.getComponents()) if (child instanceof Container nested) layout(nested);
    }

    private static void render(Path out, String name, Object body, String[] options) throws Exception {
        var pane = new JOptionPane(body, JOptionPane.PLAIN_MESSAGE, JOptionPane.DEFAULT_OPTION,
            null, options, options[0]);
        pane.setSize(pane.getPreferredSize());
        layout(pane);
        // A margin makes the button fill visible against the dialog ground.
        int pad = 16, w = pane.getWidth() + pad * 2, h = pane.getHeight() + pad * 2;
        var image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        g.setColor(UIManager.getColor("OptionPane.background"));
        g.fillRect(0, 0, w, h);
        g.translate(pad, pad);
        pane.paint(g);
        g.dispose();
        ImageIO.write(image, "png", out.resolve(name + ".png").toFile());
    }

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args.length > 0 ? args[0] : "build/preview/dialogs");
        Files.createDirectories(out);
        SwingUtilities.invokeAndWait(() -> {
            try {
                Theme.install();

                // The launcher's welcome card: the first screen anyone sees.
                var welcome = card();
                welcome.add(Logo.lockup(34, CYAN));
                gap(welcome, 14);
                welcome.add(label("Your local workspace", 18, TEXT));
                gap(welcome, 12);
                welcome.add(label("Create a workspace once. Reopen it here next time.", 13, MUTED));
                gap(welcome, 12);
                welcome.add(label("Your data stays on this computer. No account required.", 12, MUTED));
                render(out, "welcome", welcome,
                    new String[]{"Open recent", "Create workspace", "Browse…", "Cancel"});

                // Mirrors Dialogs.error.
                var failure = stack();
                failure.add(label("!  YORU COULDN'T DO THAT", 11, DANGER));
                gap(failure, 12);
                failure.add(label("End must be after start.", 14, TEXT));
                render(out, "error", failure, new String[]{"OK"});

                // Mirrors Dialogs.confirmDestructive.
                var destructive = stack();
                destructive.add(label("!  THIS CANNOT BE UNDONE", 11, DANGER));
                gap(destructive, 12);
                destructive.add(label("Delete this recorded session.", 14, TEXT));
                render(out, "destructive", destructive, new String[]{"Delete", "Cancel"});

                // A form-bearing dialog with inputs, the densest control mix.
                var form = stack();
                form.add(label("Activity", 12, MUTED));
                form.add(plainCombo(new JComboBox<>(new String[]{"Study", "Coding", "Japanese"})));
                gap(form, 12);
                form.add(label("Start · America/New_York", 12, MUTED));
                form.add(new DateTimeField(java.time.Instant.now(), java.time.ZoneId.of("America/New_York")));
                render(out, "form", form, new String[]{"Save", "Cancel"});

                // Mirrors Dialogs.input carrying an existing value, so a rename
                // starts from the current name.
                var nickname = stack();
                nickname.add(label("Nickname: up to 20 characters. Leave blank to use the species name.", 13, TEXT));
                gap(nickname, 10);
                var field = new JTextField(28);
                field.setText("Blaze");
                styleInput(field);
                nickname.add(field);
                render(out, "nickname", nickname, new String[]{"Continue", "Cancel"});

                // The tag manager, which owns the only colour surface in the app.
                Repository memory = new Repository() {
                    State state = State.empty();
                    public State load() { return state; }
                    public void save(State next) { state = next; }
                    public void close() { }
                };
                var tracker = new Tracker(memory, Clock.systemUTC());
                tracker.addTag("Reading", 0x90D8DA);
                tracker.addTag("Language", 0xE8B24C);
                tracker.addTag("Reading", 0xA98BD4);
                render(out, "tags", new TagEditor(tracker, () -> { }), new String[]{"Done"});

                // The weekly template: the only dialog that groups by weekday.
                var planner = new Tracker(new Repository() {
                    State state = State.empty();
                    public State load() { return state; }
                    public void save(State next) { state = next; }
                    public void close() { }
                }, Clock.systemUTC());
                planner.addActivity("Calculus", 0);
                planner.addActivity("English", 0);
                var calculus = planner.state().activities().getFirst().id();
                var english = planner.state().activities().get(1).id();
                planner.repeat(calculus, java.time.DayOfWeek.MONDAY,
                    java.time.LocalTime.of(9, 0), java.time.LocalTime.of(10, 30));
                planner.repeat(english, java.time.DayOfWeek.MONDAY,
                    java.time.LocalTime.of(13, 0), java.time.LocalTime.of(14, 15));
                planner.repeat(calculus, java.time.DayOfWeek.WEDNESDAY,
                    java.time.LocalTime.of(9, 0), java.time.LocalTime.of(10, 30));
                planner.repeat(english, java.time.DayOfWeek.FRIDAY,
                    java.time.LocalTime.of(11, 0), java.time.LocalTime.of(12, 0));
                render(out, "weekly", new WeeklyTemplate(planner, () -> { }), new String[]{"Done"});

                System.out.println("Rendered 7 dialogs to " + out);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        System.exit(0);
    }
}
