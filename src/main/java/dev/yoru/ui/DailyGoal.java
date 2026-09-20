package dev.yoru.ui;

import dev.yoru.application.Analytics;
import dev.yoru.application.Tracker;
import java.awt.*;
import java.time.ZoneId;
import javax.swing.*;
import static dev.yoru.ui.Theme.*;

/** Today's recorded time against the same goal that colours the heat map. */
final class DailyGoal extends JPanel {
    private final Tracker tracker;
    private final ZoneId zone;
    private final JLabel caption=wrapping("",TYPE_CAPTION,MUTED);
    private final JProgressBar progress=new JProgressBar(0,100);
    private long lastSecond=Long.MIN_VALUE;

    DailyGoal(Tracker tracker,ZoneId zone) {
        this.tracker=tracker; this.zone=zone;
        setOpaque(false); setAlignmentX(0);
        setLayout(new BoxLayout(this,BoxLayout.Y_AXIS));
        setName("today.goal");
        progress.setName("today.goal.progress");
        progress.setUI(new javax.swing.plaf.basic.BasicProgressBarUI());
        progress.setBorderPainted(false);
        progress.setBackground(LINE); progress.setForeground(ACCENT_TEXT);
        progress.setAlignmentX(0);
        progress.setPreferredSize(new Dimension(SPACE_XXL,SPACE_XS));
        progress.setMaximumSize(new Dimension(Integer.MAX_VALUE,SPACE_XS));
        add(caption); gap(this,SPACE_SM); add(progress);
        update();
    }

    void update() {
        var now=tracker.now();
        if(now.getEpochSecond()==lastSecond)return;
        lastSecond=now.getEpochSecond();
        var today=now.atZone(zone).toLocalDate();
        long seconds=Analytics.daily(tracker.state(),null,zone,now).getOrDefault(today,0L);
        long goal=tracker.state().settings().dailyGoalHours()*3600L;
        int percent=(int)Math.min(100,seconds*100/goal);
        String text=Analytics.report(seconds)+" / "+tracker.state().settings().dailyGoalHours()
            +"h today"+(seconds>=goal?" · Goal reached":" · "+percent+"%");
        caption.setText(text);
        progress.setValue(percent);
        progress.getAccessibleContext().setAccessibleName("Daily study goal");
        progress.getAccessibleContext().setAccessibleDescription(text);
    }
}
