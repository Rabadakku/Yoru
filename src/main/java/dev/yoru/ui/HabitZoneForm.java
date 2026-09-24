package dev.yoru.ui;

import dev.yoru.application.Tracker;
import dev.yoru.domain.Model.Habit;
import java.awt.Component;
import java.time.ZoneId;
import java.util.UUID;
import javax.swing.*;
import static dev.yoru.ui.Theme.*;

/** Date-only check-ins keep their meaning when the daily boundary changes. */
final class HabitZoneForm extends JPanel {
    final JComboBox<String> zones;
    HabitZoneForm(Habit habit) {
        setLayout(new BoxLayout(this,BoxLayout.Y_AXIS));setOpaque(false);
        add(bodyLabel("Existing check-ins keep their calendar dates. This changes when a new day starts for this habit."));
        gap(this,SPACE_SM);
        var choices=new java.util.TreeSet<>(ZoneId.getAvailableZoneIds());
        choices.add(habit.zone());
        zones=plainCombo(new JComboBox<>(choices.toArray(String[]::new)));
        zones.setName("habit.zone.choice");
        zones.setAlignmentX(0);
        zones.getAccessibleContext().setAccessibleName("Time zone");
        zones.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list,Object value,int index,boolean selected,boolean focus) {
                return super.getListCellRendererComponent(list,value==null?"":value.toString().replace('_',' '),index,selected,focus);
            }
        });
        zones.setSelectedItem(habit.zone());
        add(zones);
    }
    void save(Tracker tracker,UUID id)throws java.io.IOException {
        tracker.habitZone(id,ZoneId.of((String)zones.getSelectedItem()));
    }
}
