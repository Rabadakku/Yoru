package dev.yoru.ui;

import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import javax.swing.JButton;
import javax.swing.JPanel;
import static dev.yoru.ui.Theme.*;

/**
 * One choice of a few, drawn as a row of buttons with the chosen one filled:
 * the app's one selected treatment ({@link Theme#selected}), where a radio
 * group would bring the look-and-feel's own circles into an otherwise themed
 * form.
 */
final class Segmented extends JPanel {
    private final List<JButton> options = new ArrayList<>();
    private final List<IntConsumer> listeners = new ArrayList<>();
    private int chosen;

    Segmented(String name, int initial, String... labels) {
        super(new WrapFlowLayout(FlowLayout.LEFT, SPACE_XS, SPACE_XS));
        setOpaque(false);
        setAlignmentX(0);
        setName(name);
        for (int i = 0; i < labels.length; i++) {
            int index = i;
            var option = button(labels[i], () -> choose(index));
            option.setName(name + "." + i);
            options.add(option);
            add(option);
        }
        choose(initial);
    }

    int chosen() { return chosen; }

    void onChange(IntConsumer listener) { listeners.add(listener); }

    void choose(int index) {
        chosen = index;
        for (int i = 0; i < options.size(); i++) {
            var option = selected(options.get(i), i == index);
            option.getAccessibleContext().setAccessibleDescription(i == index ? "Selected" : null);
        }
        listeners.forEach(l -> l.accept(index));
    }
}
