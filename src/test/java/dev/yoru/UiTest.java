package dev.yoru;
import dev.yoru.application.*;
import dev.yoru.domain.Model.*;
import dev.yoru.ui.YoruApp;
import javax.swing.*;
import java.awt.*;
import java.time.*;
/** Exercises actual page navigation and layout without needing a desktop display. */
public final class UiTest {
    static void layout(Container c) {
        c.doLayout();
        for(Component child:c.getComponents())if(child instanceof Container nested)layout(nested);
    }
    static JButton button(Container c,String name) {
        for(Component child:c.getComponents()) {
            if(child instanceof JButton b&&(b.getText().equals(name)||name.equals(b.getName())))return b;
            if(child instanceof Container nested) {
                var b=button(nested,name);
                if(b!=null)return b;
            }
        }
        return null;
    }
    public static void main(String[] args)throws Exception {
        SwingUtilities.invokeAndWait(()-> {
            try {
                Repository repo=new Repository() {
                    State state=State.empty();public State load() {
                        return state;
                    }
                    public void save(State s) {
                        state=s;
                    }
                    public void close() {
                    }
                }
                ; System.setProperty("yoru.game.core","/nonexistent/yoru-test-core.dylib");
                Tracker t=new Tracker(repo,Clock.systemUTC());t.addActivity("Study",30);var id=t.state().activities().getFirst().id();Instant end=Instant.now().minusSeconds(60);t.log(id,end.minusSeconds(1800),end);t.plan(id,end.minusSeconds(1800),end.plusSeconds(1800)); t.addHabit("Daily reading",HabitKind.DAILY,ZoneId.systemDefault(),null);t.addHabit("Quit habit",HabitKind.TIME_SINCE,ZoneId.systemDefault(),end);var app=new YoruApp(t,repo);for(int width:new int[] {
                    1040,1280
                }
                ) {
                    app.setSize(width,900);for(String page:new String[] {
                        "Today","Tasks","Habits","Schedule","Collection","Game","Data","Settings"
                    }
                    ) {
                        button(app,page).doClick();layout(app);var image=new java.awt.image.BufferedImage(width,900,java.awt.image.BufferedImage.TYPE_INT_RGB);app.paint(image.getGraphics());
                    }
                }
                button(app,"Collection").doClick();button(app,"Game").doClick();if(button(app,"game.play")==null&&t.state().game()!=null)throw new AssertionError("Game page lost its save");button(app,"Today").doClick();button(app,"▶  Clock in").doClick();if(t.active()==null)throw new AssertionError("Clock in UI failed");button(app,"Lock & close").doClick(); System.out.println("PASS: eight pages at two widths, no starter picker, populated state, clock-in control");
            }
            catch(Exception e) {
                throw new RuntimeException(e);
            }
        }
        );
    }
}
