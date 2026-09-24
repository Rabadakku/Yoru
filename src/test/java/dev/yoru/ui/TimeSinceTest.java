package dev.yoru.ui;

import dev.yoru.application.*;
import dev.yoru.domain.Model.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.time.*;
import javax.imageio.ImageIO;
import javax.swing.*;

public final class TimeSinceTest {
    private static int checks;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    private static void span(String start,String end,String expected){
        check(HabitsPanel.elapsed(Instant.parse(start),Instant.parse(end),ZoneOffset.UTC).equals(expected),start+" to "+end+" should be "+expected);
    }
    private static Component find(Container root,String name){
        for(var c:root.getComponents()){if(name.equals(c.getName()))return c;if(c instanceof Container nested){var found=find(nested,name);if(found!=null)return found;}}return null;
    }
    private static boolean hasText(Container root,String text){
        for(var c:root.getComponents()){if(c instanceof JLabel l&&text.equals(l.getText()))return true;if(c instanceof Container nested&&hasText(nested,text))return true;}return false;
    }
    private static class Memory implements Repository{State state=State.empty();public State load(){return state;}public void save(State s){state=s;}public void close(){}}
    public static void main(String[] args)throws Exception{
        span("2026-01-01T00:00:00Z","2026-01-01T00:00:59Z","0m");
        span("2026-01-01T00:00:00Z","2026-01-01T00:59:59Z","59m");
        span("2026-01-01T00:00:00Z","2026-01-01T01:00:00Z","1h 0m");
        span("2026-01-01T00:00:00Z","2026-01-03T04:05:00Z","2d 4h 5m");
        span("2026-01-31T12:00:00Z","2026-02-28T12:00:00Z","1mo 0d 0h 0m");
        span("2026-01-31T12:00:00Z","2026-02-28T11:59:00Z","27d 23h 59m");
        span("2024-02-29T12:00:00Z","2025-02-28T12:00:00Z","1y 0mo 0d 0h 0m");
        span("2024-02-29T12:00:00Z","2025-04-30T16:05:00Z","1y 2mo 2d 4h 5m");
        span("2024-01-01T00:00:00Z","2025-03-04T04:05:00Z","1y 2mo 3d 4h 5m");
        span("2026-01-01T00:00:00Z","2026-01-01T00:00:00Z","0m");
        span("2026-01-02T00:00:00Z","2026-01-01T00:00:00Z","0m");
        var zone=ZoneId.of("America/New_York");
        check(HabitsPanel.elapsed(ZonedDateTime.of(2026,3,7,12,0,0,0,zone).toInstant(),ZonedDateTime.of(2026,3,8,12,0,0,0,zone).toInstant(),zone).equals("1d 0h 0m"),"Spring's 23-hour calendar day");
        check(HabitsPanel.elapsed(ZonedDateTime.of(2026,10,31,12,0,0,0,zone).toInstant(),ZonedDateTime.of(2026,11,1,12,0,0,0,zone).toInstant(),zone).equals("1d 0h 0m"),"Autumn's 25-hour calendar day");
        check(HabitsPanel.elapsed(Instant.parse("2026-11-01T05:30:00Z"),Instant.parse("2026-11-01T06:30:00Z"),zone).equals("1h 0m"),"Repeated clock hour counts actual elapsed time");
        SwingUtilities.invokeAndWait(()->{try{ui(args.length==0?null:Path.of(args[0]));}catch(Exception e){throw new RuntimeException(e);}});
        System.out.println("PASS: "+checks+" time-since checks (calendar units, leap years, month ends, DST, live/history display, spoken units, large text, narrow columns)");
    }
    private static void ui(Path out)throws Exception{
        Theme.install();var now=Instant.parse("2025-03-04T04:05:00Z");
        var tracker=new Tracker(new Memory(),Clock.fixed(now,ZoneOffset.UTC));
        tracker.addHabit("Evening reset",HabitKind.TIME_SINCE,ZoneOffset.UTC,Instant.parse("2024-01-01T00:00:00Z"));
        var habit=tracker.state().habits().getFirst();var state=tracker.state();
        if(out!=null)Files.createDirectories(out);
        for(var theme:ThemeId.values())for(int size:new int[]{100,200}){
            TextSize.use(size);Theme.apply(theme);
            var view=HabitsPanel.view(tracker,()->{});
            var label=(JLabel)find(view,"habit.elapsed."+habit.id());
            check(label.getText().equals("1y 2mo 3d 4h 5m"),"Live display uses tracker clock and every unit");
            check(label.getAccessibleContext().getAccessibleName().equals("1 year, 2 months, 3 days, 4 hours, 5 minutes"),"Screen readers hear whole words, singular where one: "+label.getAccessibleContext().getAccessibleName());
            view.setSize(size==100?900:1200,size==100?650:1100);Preview.layout(view);
            check(label.getWidth()>=label.getFontMetrics(label.getFont()).stringWidth(label.getText()),"Counter text fits at "+size);
            var rootBounds=SwingUtilities.convertRectangle(label.getParent(),label.getBounds(),view);
            check(rootBounds.x>=0&&rootBounds.x+rootBounds.width<=view.getWidth(),"Counter fits page width at "+size);
            if(out!=null){var image=new BufferedImage(view.getWidth(),view.getHeight(),BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();g.setColor(Theme.BG);g.fillRect(0,0,image.getWidth(),image.getHeight());view.printAll(g);g.dispose();ImageIO.write(image,"png",out.resolve("since-"+theme.name().toLowerCase()+"-"+size+".png").toFile());}
        }
        TextSize.use(100);Theme.apply(ThemeId.MIDNIGHT);
        check(hasText(HabitsPanel.history(tracker,habit.id(),ZoneOffset.UTC,()->{}),"1y 2mo 3d 4h 5m"),"Current history uses the same calendar units");
        tracker.addHabitPeriod(habit.id(),Instant.parse("2024-03-01T00:00:00Z"));
        check(hasText(HabitsPanel.history(tracker,habit.id(),ZoneOffset.UTC,()->{}),"2mo 0d 0h 0m"),"Completed history ends at the next restart");
        check(state.habits().getFirst().starts().getFirst().equals(tracker.state().habits().getFirst().starts().getFirst()),"Formatting keeps original timestamps");
        narrow(out);
    }
    /**
     * The widest counter a tracker is likely to show, in half a page just wide
     * enough for two columns. At 150% the counter, Start again and ⋯ are then
     * too wide for even a line of their own, so the counter keeps a line and
     * the controls go under it. No window is that narrow at 200%, so that size
     * is checked on a wider page here; TextFitTest checks each size in the
     * whole window at its real minimum.
     */
    private static void narrow(Path out)throws Exception{
        var tracker=new Tracker(new Memory(),Clock.fixed(Instant.parse("2025-03-31T23:59:00Z"),ZoneOffset.UTC));
        tracker.addHabit("Evening reset",HabitKind.TIME_SINCE,ZoneOffset.UTC,Instant.parse("2014-04-01T00:00:00Z"));
        tracker.addHabit("Morning walk",HabitKind.DAILY,ZoneOffset.UTC,null);
        var habit=tracker.state().habits().getFirst();
        check(HabitsPanel.elapsed(habit.starts().getLast(),tracker.now(),ZoneOffset.UTC).equals("10y 11mo 30d 23h 59m"),"Widest likely counter");
        for(var at:new int[][]{{100,780},{150,780},{100,1280},{150,1280},{200,1280}}){
            int size=at[0],width=at[1];
            TextSize.use(size);Theme.apply(ThemeId.MIDNIGHT);
            var view=HabitsPanel.view(tracker,()->{});
            view.setSize(width,900);Preview.layout(view);
            var row=(Container)find(view,"habit.row."+habit.id());
            var label=(JLabel)find(view,"habit.elapsed."+habit.id());
            for(var part:new String[]{"habit.elapsed.","habit.restart.","habit.more."}){
                var c=find(row,part+habit.id());
                check(shown(c,row),part+" is cut off at "+size+"% in a "+width+" px window: "+cut(c,row));
            }
            check(label.getWidth()>=label.getFontMetrics(label.getFont()).stringWidth(label.getText()),"Counter is not shortened at "+size+"% in "+width+" px");
            if(out!=null){var image=new BufferedImage(view.getWidth(),view.getHeight(),BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();g.setColor(Theme.BG);g.fillRect(0,0,image.getWidth(),image.getHeight());view.printAll(g);g.dispose();ImageIO.write(image,"png",out.resolve("since-narrow-"+size+"-"+width+".png").toFile());}
        }
        TextSize.use(100);
    }
    /** The first container between the component and the row that does not hold all of what is in it. */
    private static String cut(Component c,Container row){
        for(;c!=row;c=c.getParent())if(!shown(c,c.getParent()))return c.getClass().getSimpleName()+" "+c.getBounds()+" in "+c.getParent().getSize();
        return "nothing";
    }
    /** Whether every container between the row and the component holds all of it. */
    private static boolean shown(Component c,Container row){
        for(;c!=row;c=c.getParent()){
            var in=c.getParent();
            if(c.getWidth()<=0||c.getHeight()<=0||c.getX()<0||c.getY()<0||c.getX()+c.getWidth()>in.getWidth()||c.getY()+c.getHeight()>in.getHeight())return false;
        }
        return true;
    }
}
