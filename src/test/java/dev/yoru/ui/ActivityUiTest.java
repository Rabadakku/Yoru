package dev.yoru.ui;

import dev.yoru.application.*;
import dev.yoru.domain.Model;
import dev.yoru.domain.Model.*;
import javax.swing.*;
import java.awt.Component;
import java.awt.Container;
import java.awt.image.BufferedImage;
import java.time.*;
import java.util.List;
import java.util.UUID;

/**
 * Activity management on the Today and Data pages (#38).
 *
 * The pages are rendered for real and their controls are found by name, so a
 * manager that stopped being reachable from either page fails here. The dialogs
 * themselves are modal and cannot be clicked headlessly, so the choice each one
 * makes is driven through the same call the dialog makes — rename, remove
 * keeping the time, remove deleting it — and the wording that has to appear
 * before the choice is read from {@code summary}.
 *
 * Invented data only.
 */
public final class ActivityUiTest {
    private static int checks;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}

    private static final Instant NOW=Instant.parse("2026-09-09T12:00:00Z");
    /** Advanceable, so a timer can be run and clocked out without a real wait. */
    private static final class Time extends Clock {
        Instant now=NOW;
        public Instant instant(){return now;}
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return Clock.fixed(now,zone);}
        void advance(long seconds){now=now.plusSeconds(seconds);}
    }

    private static final class Memory implements Repository {
        State state=State.empty();
        public State load(){return state;}
        public void save(State next){state=next;}
        public void close(){}
    }

    private static Component find(Container root,String name){
        for(Component child:root.getComponents()){
            if(name.equals(child.getName()))return child;
            if(child instanceof Container nested){var found=find(nested,name);if(found!=null)return found;}
        }
        return null;
    }
    private static JButton button(Container root,String name){return (JButton)find(root,name);}
    private static void layout(Container c){c.doLayout();for(Component child:c.getComponents())if(child instanceof Container nested)layout(nested);}
    private static void render(YoruApp app,int width,String page){
        button(app,page).doClick();layout(app);
        app.paint(new BufferedImage(width,900,BufferedImage.TYPE_INT_RGB).getGraphics());
    }

    public static void main(String[] args)throws Exception {
        SwingUtilities.invokeAndWait(()->{
            try {
                var repo=new Memory();
                var coding=UUID.nameUUIDFromBytes(new byte[]{1,2,3});
                var japanese=UUID.nameUUIDFromBytes(new byte[]{4,5,6});
                repo.state=new State(
                    List.of(new Activity(coding,"Coding",30),new Activity(japanese,"Japanese",0)),
                    List.of(
                        new Session(UUID.nameUUIDFromBytes(new byte[]{21}),coding,NOW.minusSeconds(10800),NOW.minusSeconds(7200)),
                        new Session(UUID.nameUUIDFromBytes(new byte[]{22}),coding,NOW.minusSeconds(7200),NOW.minusSeconds(5400)),
                        new Session(UUID.nameUUIDFromBytes(new byte[]{23}),japanese,NOW.minusSeconds(86400),NOW.minusSeconds(84600))),
                    List.of(new ScheduleBlock(UUID.nameUUIDFromBytes(new byte[]{31}),coding,NOW.plusSeconds(86400),NOW.plusSeconds(90000))));
                var clock=new Time();
                var tracker=new Tracker(repo,clock);
                var app=new YoruApp(tracker,repo);
                app.setSize(1280,900);

                // Both pages render, and both carry the manager.
                render(app,1280,"Today");
                check(button(app,"activity.rename."+coding)!=null,"Today offers a rename by identity");
                check(button(app,"activity.rename."+japanese)!=null,"for every activity, not just the first");
                check(button(app,"activity.remove."+coding)!=null,"and a removal for each");
                render(app,1040,"Data");
                var recorded=(JLabel)find(app,"activity.recorded."+coding);
                check(recorded!=null,"the Data page lists each activity's session count and time");
                check(recorded.getText().equals("2 sessions · 01:30:00"),"counted from the records, got "+recorded.getText());
                check(((JLabel)find(app,"activity.recorded."+japanese)).getText().equals("1 session · 00:30:00"),
                    "and counts each activity separately");

                // What the dialogs say before a choice is made.
                var moved=ActivityManager.summary("Coding",tracker.usage(coding));
                check(moved.contains("2 recorded sessions")&&moved.contains("01:30:00"),
                    "the confirmation quotes the count and the duration, got "+moved);
                check(moved.contains("1 planned block"),"and says what is kept either way, got "+moved);
                check(ActivityManager.KEEP.contains(Model.UNCATEGORIZED)&&ActivityManager.DELETE.startsWith("Delete"),
                    "the two outcomes are worded apart");

                // The timer that is running is the one activity that cannot be removed.
                tracker.start(coding);
                render(app,1280,"Today");
                check(!button(app,"activity.remove."+coding).isEnabled(),"the activity being timed cannot be removed from Today");
                check(button(app,"activity.remove."+japanese).isEnabled(),"another activity still can be");
                check(!ActivityManager.canRemove(tracker,coding)&&ActivityManager.canRemove(tracker,japanese),
                    "and the page agrees with the vault");
                try{ActivityManager.remove(tracker,coding,true);throw new AssertionError("a running activity was removed");}
                catch(IllegalArgumentException expected){checks++;}

                // Renaming keeps the identity every session points at.
                var before=tracker.state();
                ActivityManager.rename(tracker,japanese,"Japanese II");
                check(tracker.state().activities().stream().anyMatch(a->a.id().equals(japanese)&&a.name().equals("Japanese II")),
                    "renaming a label keeps the activity's id");
                check(tracker.state().sessions().equals(before.sessions()),"and keeps every session attached to it");

                // Keeping the time moves the sessions off the removed activity.
                ActivityManager.remove(tracker,japanese,true);
                check(tracker.state().activities().stream().noneMatch(a->a.id().equals(japanese)),"remove-keep removes the activity");
                check(tracker.state().sessions().size()==4,"and loses no session");
                check(tracker.state().sessions().stream().noneMatch(s->s.activityId().equals(japanese)),
                    "the sessions all moved off it");
                var bucket=tracker.state().activities().stream()
                    .filter(a->a.name().equalsIgnoreCase(Model.UNCATEGORIZED)).findFirst().orElseThrow();
                check(tracker.state().sessions().stream().anyMatch(s->s.activityId().equals(bucket.id())),
                    "and are filed under the bucket");
                // Deleting the time takes exactly the removed activity's sessions.
                clock.advance(600);
                check(tracker.stop(clock.instant()),"the timer is clocked out before the activity is removed");
                ActivityManager.remove(tracker,coding,false);
                check(tracker.state().activities().stream().noneMatch(a->a.id().equals(coding)),"remove-delete removes the activity");
                check(tracker.state().sessions().size()==1,"and exactly its sessions");

                // The pages still render once the vault has changed under them.
                render(app,1280,"Today");
                render(app,1040,"Data");
                check(button(app,"activity.rename."+bucket.id())!=null,"the bucket is managed like any other activity");

                // Closing stops the app's ticker, which is what keeps the JVM up
                // once the pages have been rendered.
                button(app,"Lock & close").doClick();
                System.out.println("PASS: "+checks+" activity-manager checks (both pages, by identity, refusal while timing)");
            } catch(Exception e) { throw new RuntimeException(e); }
        });
    }
}
