package dev.yoru.ui;

import dev.yoru.application.*;
import dev.yoru.domain.Model.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import javax.imageio.ImageIO;
import javax.swing.*;

/** Automatic, read-only streaks, even with no manually created habits. */
public final class AnkiStreakUiTest {
    private static int checks;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    private static Component find(Container root,String name) {
        for(var child:root.getComponents()) {
            if(name.equals(child.getName()))return child;
            if(child instanceof Container nested){var found=find(nested,name);if(found!=null)return found;}
        }
        return null;
    }
    private static String text(Container root,String name){return ((JLabel)find(root,name)).getText();}
    private static int buttons(Container root) {
        int count=0;
        for(var child:root.getComponents()) {
            if(child instanceof AbstractButton)count++;
            if(child instanceof Container nested)count+=buttons(nested);
        }
        return count;
    }
    private static class Memory implements Repository {
        State state=State.empty();
        public State load(){return state;}
        public void save(State next){state=next;}
        public void close(){}
    }
    public static void main(String[] args)throws Exception {
        SwingUtilities.invokeAndWait(()->{try{run(args);}catch(Exception e){throw new RuntimeException(e);}});
        System.out.println("PASS: "+checks+" automatic Anki UI checks (setup, read-only, sync, offline, profile replacement)");
    }
    private static void run(String[] args)throws Exception {
        var now=Instant.parse("2026-09-23T12:00:00Z");
        var today=now.atZone(ZoneId.systemDefault()).toLocalDate();
        var tracker=new Tracker(new Memory(),Clock.fixed(now,ZoneOffset.UTC));
        Theme.install();
        var page=HabitsPanel.view(tracker,()->{});
        var card=(JPanel)find(page,"habits.anki");
        check(card!=null,"The automatic streak exists before creating a manual habit");
        check(text(card,"habits.anki.detail").contains("Settings"),"Disconnected state explains setup");
        check(buttons(card)==0,"Imported streaks cannot be manually checked off");
        var timer=(javax.swing.Timer)card.getClientProperty("anki.refreshTimer");
        check(!timer.isRunning(),"Offscreen streaks do not poll");
        var days=new TreeMap<LocalDate,Long>();
        for(int i=0;i<90;i++)days.put(today.minusDays(i),1L);
        tracker.anki(new Anki(true,"",false,1,new AnkiSnapshot("Practice",1,days,now)));
        for(var listener:timer.getActionListeners())listener.actionPerformed(null);
        check(text(card,"habits.anki.streak").equals("Streak: 90 days · best: 90 days"),"An already-open page picks up imported counts without timed sessions");
        check(!text(card,"habits.anki.detail").contains("as of"),"Fresh data is current");
        tracker.ankiSeen(new AnkiSnapshot("Another practice profile",0,Map.of(),now.minus(Duration.ofDays(3))));
        for(var listener:timer.getActionListeners())listener.actionPerformed(null);
        check(text(card,"habits.anki.streak").contains("Streak: 0 days"),"A different profile replaces the old run");
        check(text(card,"habits.anki.detail").contains("Saved streak as of"),"Offline counts are dated instead of silently reset");
        tracker.anki(tracker.state().anki().enabled(false));
        for(var listener:timer.getActionListeners())listener.actionPerformed(null);
        check(text(card,"habits.anki.detail").contains("Sync is off"),"Retained data clearly says sync is disabled");
        check(tracker.state().habits().isEmpty()&&tracker.state().sessions().isEmpty(),"No duplicate habits or study sessions are created");
        if(args.length>0) {
            var out=Path.of(args[0]);Files.createDirectories(out);
            tracker.anki(new Anki(true,"",false,1,new AnkiSnapshot("Practice",1,days,now.minus(Duration.ofDays(1)))));
            for(var theme:ThemeId.values())for(int size:new int[]{100,200}) {
                TextSize.use(size);
                Theme.apply(theme);
                var view=HabitsPanel.view(tracker,()->{});
                view.setSize(size==100?800:1200,size==100?500:700);
                Preview.layout(view);
                var image=new BufferedImage(view.getWidth(),view.getHeight(),BufferedImage.TYPE_INT_RGB);
                var g=image.createGraphics();g.setColor(Theme.BG);g.fillRect(0,0,image.getWidth(),image.getHeight());view.printAll(g);g.dispose();
                ImageIO.write(image,"png",out.resolve("anki-"+theme.name().toLowerCase(Locale.ROOT)+"-"+size+".png").toFile());
            }
            TextSize.use(100);
        }
    }
}
