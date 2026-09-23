package dev.yoru.ui;

import dev.yoru.application.Pomodoro.Cue;
import dev.yoru.application.Pomodoro.Plan;
import java.awt.*;
import javax.swing.*;
import static dev.yoru.ui.Theme.*;

/**
 * The pomodoro's settings (#61): how long each interval is, whether the next
 * starts by itself, and the sound an interval ends with — which voice, how
 * loud, heard before choosing, and off altogether. These are this computer's,
 * like the study music's volume, and apart from it.
 */
final class PomodoroSettings {
    private PomodoroSettings() { }

    static JPanel card(Shell shell) {
        var clock = shell.pomodoro();
        var plan = PomodoroClock.plan();
        var card = Theme.card();
        card.add(sectionHeader("TRACKING · POMODORO"));
        gap(card, SPACE_SM);
        card.add(bodyLabel("Work in intervals with breaks between. Work is recorded as time under the activity you choose; breaks are not."));
        gap(card, SPACE_MD);

        var work = spinner("pomodoro.work", plan.workMinutes(), 1, 180);
        var shortBreak = spinner("pomodoro.short", plan.shortMinutes(), 1, 60);
        var longBreak = spinner("pomodoro.long", plan.longMinutes(), 1, 120);
        var every = spinner("pomodoro.every", plan.longEvery(), 1, 12);
        var grid = new JPanel(new GridLayout(2, 4, SPACE_MD, SPACE_XS));
        grid.setOpaque(false);
        grid.setAlignmentX(0);
        for (var caption : new String[]{"WORK · MINUTES", "SHORT BREAK", "LONG BREAK", "LONG BREAK AFTER"})
            grid.add(label(caption, TYPE_CAPTION, MUTED));
        grid.add(work);
        grid.add(shortBreak);
        grid.add(longBreak);
        grid.add(every);
        grid.setMaximumSize(new Dimension(Integer.MAX_VALUE, grid.getPreferredSize().height));
        card.add(grid);
        gap(card, SPACE_MD);
        var auto = new JCheckBox("Start the next interval by itself", plan.autoStart());
        auto.setOpaque(false);
        auto.setForeground(TEXT);
        auto.setAlignmentX(0);
        auto.setName("pomodoro.autostart");
        card.add(auto);
        gap(card, SPACE_MD);
        var save = button("Save pomodoro settings", () -> {
            try {
                var next = new Plan((Integer) work.getValue(), (Integer) shortBreak.getValue(), (Integer) longBreak.getValue(),
                    (Integer) every.getValue(), auto.isSelected());
                if (clock != null) clock.plan(next);
                Dialogs.info(shell.owner(), "Saved. A running interval keeps its length; the next one takes the new one.");
            } catch (IllegalArgumentException refused) {
                Dialogs.error(shell.owner(), refused.getMessage());
            }
        });
        save.setName("pomodoro.save");
        card.add(save);
        gap(card, SPACE_LG);

        card.add(label("SOUND WHEN AN INTERVAL ENDS", TYPE_CAPTION, MUTED));
        gap(card, SPACE_XS);
        var on = new JCheckBox("Play a sound", Cues.on());
        on.setOpaque(false);
        on.setForeground(TEXT);
        on.setAlignmentX(0);
        on.setName("pomodoro.sound");
        on.addActionListener(e -> Cues.on(on.isSelected()));
        card.add(on);
        gap(card, SPACE_SM);
        var voices = Cues.Voice.values();
        var names = new String[voices.length];
        for (int i = 0; i < voices.length; i++) names[i] = voices[i].label;
        var voice = new Segmented("pomodoro.voice", Cues.voice().ordinal(), names);
        voice.onChange(i -> Cues.voice(voices[i]));
        card.add(voice);
        gap(card, SPACE_SM);
        var volume = label("VOLUME · " + Cues.volume() + "%", TYPE_CAPTION, MUTED);
        card.add(volume);
        var level = new JSlider(0, 100, Cues.volume());
        level.setOpaque(false);
        level.setAlignmentX(0);
        level.setMaximumSize(new Dimension(240, SPACE_XXL));
        level.setName("pomodoro.volume");
        Theme.plainSlider(level);
        level.setPaintTicks(true);
        level.setMajorTickSpacing(25);
        level.setMinorTickSpacing(5);
        level.getAccessibleContext().setAccessibleName("Pomodoro sound volume");
        level.addChangeListener(e -> {
            Cues.volume(level.getValue());
            volume.setText("VOLUME · " + level.getValue() + "%");
        });
        card.add(level);
        gap(card, SPACE_SM);
        var previews = new JPanel(new WrapFlowLayout(FlowLayout.LEFT, SPACE_SM, SPACE_XS));
        previews.setOpaque(false);
        previews.setAlignmentX(0);
        var workEnds = button("Hear the end of work", () -> Cues.preview(Cue.WORK_DONE, voices[voice.chosen()], level.getValue()));
        workEnds.setName("pomodoro.preview.work");
        var breakEnds = button("Hear the end of a break", () -> Cues.preview(Cue.BREAK_DONE, voices[voice.chosen()], level.getValue()));
        breakEnds.setName("pomodoro.preview.break");
        previews.add(workEnds);
        previews.add(breakEnds);
        card.add(previews);
        gap(card, SPACE_SM);
        card.add(wrapping("With the sound off, or no sound out, the end of an interval still shows on Today and asks for "
            + "your attention in the Dock. The study music stops during breaks, since no time is being recorded.",
            TYPE_CAPTION, MUTED));
        return card;
    }

    private static JSpinner spinner(String name, int value, int min, int max) {
        var spinner = plainSpinner(new JSpinner(new SpinnerNumberModel(value, min, max, 1)));
        spinner.setName(name);
        spinner.setPreferredSize(new Dimension(grow(SPACE_XXL * 3), controlHeight()));
        return spinner;
    }
}
