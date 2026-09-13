package dev.yoru.ui;

import dev.yoru.application.Repository;
import dev.yoru.application.Tracker;
import dev.yoru.domain.Model.*;
import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.time.Clock;
import java.time.DayOfWeek;

/**
 * The decorative waifu panel on the Today page.
 *
 * The panel is optional ornament, so what is checked is that it degrades
 * instead of breaking: a bundled portrait is drawn, "rotate" cycles through
 * the roster, no choice — or an unknown id — says how to pick one rather than
 * showing a blank card, and a panel taken off the page stops its timer. It
 * belongs to the Today page alone, and the rest of the tracker never sees it.
 *
 * The portraits are the app's own bundled art, so this test exercises the real
 * resource path instead of inventing images.
 */
public final class WaifuUiTest {
    private static int checks;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}

    private static final class Memory implements Repository {
        State state=State.empty();
        public State load(){return state;}
        public void save(State next){state=next;}
        public void close(){}
    }

    private static YoruApp app;
    private static WaifuPanel panel;

    private interface Action { void run() throws Exception; }

    /** Runs on the event thread, the way the window drives it. */
    private static void onEdt(Action action) {
        try {
            SwingUtilities.invokeAndWait(()->{
                try { action.run(); } catch(Exception e) { throw new IllegalStateException(e); }
            });
        } catch(Exception e) { throw new IllegalStateException(e); }
    }

    private static Settings settings(String waifu) {
        return new Settings(ThemeId.WAIFU,TrainerId.BRENDAN,4,300,DayOfWeek.MONDAY,waifu);
    }

    private static Component find(Container root,String name) {
        for(Component child:root.getComponents()) {
            if(name.equals(child.getName()))return child;
            if(child instanceof Container nested) { var found=find(nested,name); if(found!=null)return found; }
        }
        return null;
    }

    private static boolean clickText(Container root,String text) {
        for(Component c:root.getComponents()) {
            if(c instanceof JButton b && text.equals(b.getText())) { b.doClick();return true; }
            if(c instanceof Container child && clickText(child,text))return true;
        }
        return false;
    }

    private static void layout(Container c) {
        c.doLayout();
        for(Component child:c.getComponents()) if(child instanceof Container nested) layout(nested);
    }

    /** Opens the Today page and paints it, the way the window would. */
    private static void openToday() {
        ((JButton)find(app,"Today")).doClick();
        layout(app);
        var g=new BufferedImage(1280,900,BufferedImage.TYPE_INT_RGB).getGraphics();
        app.paint(g);
        g.dispose();
    }

    private static BufferedImage showing() {
        var out=new BufferedImage[1];
        onEdt(()->out[0]=panel.showing());
        return out[0];
    }

    private static void click() {
        panel.dispatchEvent(new MouseEvent(panel,MouseEvent.MOUSE_CLICKED,
            System.currentTimeMillis(),0,5,5,1,false,MouseEvent.BUTTON1));
    }

    private static boolean hasText(Container root,String text) {
        for(Component c:root.getComponents()) {
            if(c instanceof AbstractButton b && text.equals(b.getText()))return true;
            if(c instanceof Container child && hasText(child,text))return true;
        }
        return false;
    }

    /** YoruApp's ticker keeps the JVM alive, so every outcome ends the process explicitly. */
    public static void main(String[] args) {
        try {
            run();
        } catch(Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
        System.exit(0);
    }

    private static void run()throws Exception {
        var repo=new Memory();
        var tracker=new Tracker(repo,Clock.systemUTC());
        tracker.addActivity("Study",0);
        onEdt(()->{ app=new YoruApp(tracker,repo); app.setSize(1280,900); openToday(); });

        // Nothing chosen: Today is about the timer and the partner. Choosing a
        // companion, and any recommendation of one, lives in Settings (#3).
        check(find(app,"waifu.panel")==null&&find(app,"waifu.hint")==null,
            "Today shows no companion panel or hint when none is chosen");
        check(!hasText(app,"Try Moonlight"),"and recommends none");
        check(find(app,"waifu.art")==null,"no choice means no image");

        // Waifu supplies default artwork without changing the saved preference.
        onEdt(()->((JButton)find(app,"Settings")).doClick());
        var moonlight=(JButton)find(app,"settings.theme.WAIFU");
        check(moonlight!=null,"each theme card's button is named for its theme");
        onEdt(moonlight::doClick);
        check(tracker.state().settings().theme()==ThemeId.WAIFU,"the Waifu card chooses the theme");
        check(tracker.state().settings().waifu()==null,"without overwriting the portrait preference");
        onEdt(WaifuUiTest::openToday);
        check(find(app,"waifu.art")!=null,"Waifu has a default illustration");
        for(var theme:ThemeId.values()) {
            onEdt(()-> {
                tracker.settings(new Settings(theme,TrainerId.BRENDAN,4,300,DayOfWeek.MONDAY,"amberglow"));
                openToday();
            });
            check((find(app,"waifu.art")!=null)==(theme==ThemeId.WAIFU),"portrait art is exclusive to Waifu: "+theme);
            check("amberglow".equals(tracker.state().settings().waifu()),"theme switch retains saved selection");
            onEdt(()->((JButton)find(app,"Tasks")).doClick());
            check((find(app,"moonlight.gallery")!=null)==(theme==ThemeId.WAIFU),"gallery is exclusive to Waifu: "+theme);
        }

        // A value that is not in the roster reads as Off, not a crash.
        onEdt(()->{ tracker.settings(settings("not-a-waifu")); openToday(); });
        check(find(app,"waifu.panel")==null&&find(app,"waifu.hint")==null,"an unknown id shows no companion on Today");
        check(find(app,"waifu.art")==null,"and draws no image");

        // One bundled portrait is put on the card at its own size.
        onEdt(()->{
            tracker.settings(settings("hikari"));
            openToday();
            panel=(WaifuPanel)find(app,"waifu.panel");
        });
        check(panel!=null,"a chosen waifu keeps the panel on the page");
        check(find(app,"waifu.hint")==null,"a chosen waifu has no hint to show");
        var portrait=showing();
        check(portrait!=null,"a chosen waifu gives the panel an image to draw");
        check(portrait.getWidth()>=1024,"retired pixel choices resolve to illustrated artwork");
        check(WaifuCatalog.all().size()==2,"only illustrated portraits remain in the picker");

        // Rotate cycles the roster; a click moves to the next portrait.
        onEdt(()->{
            tracker.settings(settings("rotate"));
            openToday();
            panel=(WaifuPanel)find(app,"waifu.panel");
        });
        var first=showing();
        check(first!=null,"rotate draws a portrait");
        onEdt(WaifuUiTest::click);
        check(showing()!=first,"clicking the panel moves to the next portrait");

        onEdt(()-> {
            tracker.settings(settings("nightfall"));openToday();
            panel=(WaifuPanel)find(app,"waifu.panel");
        });
        check(showing().getWidth()>=1024,"illustrated companion has full-size artwork");
        onEdt(()->panel.addNotify());
        check(!panel.cycling(),"one portrait does not waste a rotation timer");
        onEdt(()-> {
            panel.removeNotify();
            ((JButton)find(app,"Settings")).doClick();
            clickText(app,"May");
            check("nightfall".equals(tracker.state().settings().waifu()),"trainer change preserves companion");
            clickText(app,"Save tracking settings");
            check("nightfall".equals(tracker.state().settings().waifu()),"tracking change preserves companion");
            clickText(app,"Use this");
            check("nightfall".equals(tracker.state().settings().waifu()),"theme change preserves companion");
            tracker.settings(settings("rotate"));openToday();
            panel=(WaifuPanel)find(app,"waifu.panel");
        });

        // The full portrait panel is on Today; Moonlight's other pages use a gallery.
        onEdt(()->{ ((JButton)find(app,"Tasks")).doClick(); layout(app); });
        check(find(app,"waifu.panel")==null,"the panel is not on the other pages");
        check(find(app,"moonlight.gallery")!=null,"Moonlight artwork continues on Tasks");

        // Pages are rebuilt wholesale, so a panel taken off one must stop.
        onEdt(()->panel.addNotify());
        check(panel.cycling(),"the panel cycles while it is on a page");
        onEdt(()->panel.removeNotify());
        check(!panel.cycling(),"a panel taken off the page stops cycling");

        // Closing stops the app's ticker, which is what keeps the JVM up once
        // the pages have been rendered.
        onEdt(()->((JButton)find(app,"Lock & close")).doClick());
        System.out.println("PASS: "+checks+" waifu panel checks (bundled art, hint, cycling, page scope, lifecycle)");
    }
}
