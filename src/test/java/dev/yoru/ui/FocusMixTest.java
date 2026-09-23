package dev.yoru.ui;

import dev.yoru.application.Analytics;
import dev.yoru.domain.Model.*;
import java.awt.image.BufferedImage;
import java.time.*;
import java.util.*;

/**
 * Focus distribution — the "where did the week go" chart (#6).
 *
 * Two things can go wrong here and only one of them shows up in a diff. The
 * arithmetic is testable directly: clipping to the window, shares summing to
 * the window's own total, a stable order. The drawing is not — a bar chart
 * whose bars are not proportional to its numbers looks entirely reasonable in
 * source and is wrong on screen — so the component is painted and the bars are
 * measured in pixels.
 */
public final class FocusMixTest {
    private static int checks;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}

    private static final ZoneId ZONE=ZoneId.of("UTC");
    private static final Instant NOW=Instant.parse("2026-09-10T12:00:00Z");

    private static UUID MATHS=UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static UUID PROSE=UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    /** A state carrying just the sessions a case needs. */
    private static State stateOf(int minSessionSeconds,Session... sessions) {
        var base=State.empty();
        var was=base.settings();
        var settings=new Settings(was.theme(),was.dailyGoalHours(),
            minSessionSeconds,was.weekStartsOn());
        return new State(
            List.of(new Activity(MATHS,"Maths",0),new Activity(PROSE,"Prose",0)),
            List.of(sessions),base.blocks(),base.recurring(),base.tasks(),base.habits(),
            base.tags(),settings,base.notes(),base.anki(),base.lists());
    }

    private static Session session(UUID activity,String from,String to) {
        return new Session(UUID.randomUUID(),activity,Instant.parse(from),
            to==null?null:Instant.parse(to));
    }

    /** Sums the shares, which must account for the window and nothing more. */
    private static double totalShare(List<Analytics.Slice> slices) {
        double sum=0;
        for(var s:slices) sum+=s.share();
        return sum;
    }

    private static Analytics.Slice find(List<Analytics.Slice> slices,UUID activity) {
        for(var s:slices) if(s.activityId().equals(activity)) return s;
        return null;
    }

    /**
     * A session running across the window's edge counts for the part inside it.
     *
     * Counting it whole is the tempting shortcut and it inflates the window past
     * its own length: two hours of a session that started before the window
     * opened would otherwise be charged to a window that only saw one.
     */
    private static void clipsToWindow() {
        var from=Instant.parse("2026-09-10T06:00:00Z");
        // Runs 05:00-07:00; only the hour from 06:00 is inside.
        var state=stateOf(300,session(MATHS,"2026-09-10T05:00:00Z","2026-09-10T07:00:00Z"));
        var slices=Analytics.distribution(state,from,NOW,NOW);
        check(slices.size()==1,"one activity has time in the window");
        check(slices.get(0).seconds()==3600,"only the hour inside the window counts, got "+slices.get(0).seconds());
        check(Analytics.recorded(state,from,NOW,NOW)==3600,"the window total clips the same way");

        // The same session, seen from a window entirely after it, is absent
        // rather than present at zero.
        var after=Analytics.distribution(state,Instant.parse("2026-09-10T08:00:00Z"),NOW,NOW);
        check(after.isEmpty(),"a window with no overlap is empty, not a list of zeroes");
    }

    /** Shares are of the window's total, and they add up to it. */
    private static void sharesAreOfTheWindow() {
        var state=stateOf(300,
            session(MATHS,"2026-09-10T08:00:00Z","2026-09-10T09:30:00Z"),   // 90m
            session(PROSE,"2026-09-10T10:00:00Z","2026-09-10T10:30:00Z"));  // 30m
        var slices=Analytics.distribution(state,Instant.parse("2026-09-10T00:00:00Z"),NOW,NOW);
        check(slices.size()==2,"both activities appear");
        check(Math.abs(totalShare(slices)-1.0)<1e-9,"shares sum to one, got "+totalShare(slices));
        check(Math.abs(find(slices,MATHS).share()-0.75)<1e-9,"90 of 120 minutes is three quarters");
        check(Math.abs(find(slices,PROSE).share()-0.25)<1e-9,"30 of 120 minutes is one quarter");
    }

    /** Longest first, and the same order every run. */
    private static void ordersLargestFirstAndStably() {
        var state=stateOf(300,
            session(PROSE,"2026-09-10T08:00:00Z","2026-09-10T08:30:00Z"),
            session(MATHS,"2026-09-10T09:00:00Z","2026-09-10T11:00:00Z"));
        var slices=Analytics.distribution(state,Instant.parse("2026-09-10T00:00:00Z"),NOW,NOW);
        check(slices.get(0).activityId().equals(MATHS),"the longest activity leads");

        // Equal lengths must not swap between repaints. HashMap iteration order
        // is not stable across runs, so the sort has to be total; asking the
        // same question twice is the cheapest way to catch it not being.
        var tied=stateOf(300,
            session(MATHS,"2026-09-10T08:00:00Z","2026-09-10T09:00:00Z"),
            session(PROSE,"2026-09-10T10:00:00Z","2026-09-10T11:00:00Z"));
        var first=Analytics.distribution(tied,Instant.parse("2026-09-10T00:00:00Z"),NOW,NOW);
        boolean same=true;
        for(int i=0;i<200;i++) {
            var again=Analytics.distribution(tied,Instant.parse("2026-09-10T00:00:00Z"),NOW,NOW);
            for(int j=0;j<again.size();j++) same&=again.get(j).activityId().equals(first.get(j).activityId());
        }
        check(same,"equal-length activities keep a fixed order");
        check(first.get(0).activityId().equals(MATHS),"the tie-break is by id, and MATHS sorts first");
    }

    /**
     * The chart obeys the same floor as every other total.
     *
     * Yoru's rule is that time under the minimum is not recorded at all, so a
     * chart that quietly counted it would disagree with the numbers beside it.
     */
    private static void honoursTheMinimum() {
        var state=stateOf(600,
            session(MATHS,"2026-09-10T08:00:00Z","2026-09-10T09:00:00Z"),   // an hour, counts
            session(PROSE,"2026-09-10T10:00:00Z","2026-09-10T10:05:00Z"));  // five minutes, under
        var slices=Analytics.distribution(state,Instant.parse("2026-09-10T00:00:00Z"),NOW,NOW);
        check(slices.size()==1&&slices.get(0).activityId().equals(MATHS),"the short session is not charted");
        check(Math.abs(slices.get(0).share()-1.0)<1e-9,"and it is not in the denominator either");
    }

    /** A session still running counts up to now, not to nothing. */
    private static void countsTheRunningSession() {
        var state=stateOf(300,session(MATHS,"2026-09-10T11:00:00Z",null));
        var slices=Analytics.distribution(state,Instant.parse("2026-09-10T00:00:00Z"),NOW,NOW);
        check(slices.size()==1&&slices.get(0).seconds()==3600,
            "the hour so far is charted, got "+(slices.isEmpty()?"nothing":slices.get(0).seconds()));
    }

    /**
     * A percentage against nothing is not a percentage.
     *
     * The first week of use has not gone up by 100%; it has no baseline. The
     * UI needs to be able to tell those apart, which is why this is an
     * OptionalDouble rather than a 0 or a NaN.
     */
    private static void changeNeedsABaseline() {
        check(Analytics.change(3600,0).isEmpty(),"no baseline yields no percentage");
        check(Analytics.change(0,0).isEmpty(),"nothing against nothing is still no percentage");
        check(Math.abs(Analytics.change(7200,3600).getAsDouble()-1.0)<1e-9,"doubling is +100%");
        check(Math.abs(Analytics.change(1800,3600).getAsDouble()+0.5)<1e-9,"halving is -50%");
        check(Math.abs(Analytics.change(3600,3600).getAsDouble())<1e-9,"no change is zero");
    }

    /** Geometry: bar length is the share, and recorded time never disappears. */
    private static void barsAreProportional() {
        int track=FocusBars.track(720);
        check(FocusBars.barWidth(1.0,track)==track,"a whole share fills the track");
        check(FocusBars.barWidth(0.5,track)==Math.round(track*0.5f),"half a share is half the track");
        check(FocusBars.barWidth(0,track)==0,"no share draws nothing");
        // A tiny but real share must still leave a mark, or a short activity
        // vanishes from the chart meant to show it.
        check(FocusBars.barWidth(0.0001,track)>=1,"a small share is still drawn");
        check(FocusBars.percent(0.0001).equals("<1%"),"and is labelled honestly, not as 0%");
        check(FocusBars.percent(0.78).equals("78%"),"ordinary shares read as whole percentages");
        check(FocusBars.track(120)>=40,"a narrow window shortens the track rather than inverting it");
    }

    /**
     * The bars as actually painted.
     *
     * This is the assertion that would have caught a chart drawn with every bar
     * the same length: it renders the component and measures the coloured run
     * on each row, rather than trusting that the width function is the one the
     * paint method calls.
     */
    private static void paintsWhatItComputes() {
        Theme.apply(ThemeId.MIDNIGHT);
        var rows=List.of(new FocusBars.Row("Maths",5400,0.75),new FocusBars.Row("Prose",1800,0.25));
        var bars=new FocusBars(rows);
        int width=720,height=FocusBars.ROW_HEIGHT*rows.size();
        bars.setSize(width,height);
        var image=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);
        var g=image.createGraphics();
        g.setFont(new java.awt.Font("SansSerif",java.awt.Font.PLAIN,12));
        bars.setFont(g.getFont());
        bars.paint(g);
        g.dispose();

        int[] painted=new int[rows.size()];
        for(int i=0;i<rows.size();i++) {
            int y=i*FocusBars.ROW_HEIGHT+FocusBars.ROW_HEIGHT/2;
            int run=0;
            for(int x=FocusBars.NAME_WIDTH+FocusBars.GAP;x<width;x++) {
                int rgb=image.getRGB(x,y);
                if(rgb==FocusBars.colourFor(i).getRGB()) run++;
            }
            painted[i]=run;
        }
        check(painted[0]>0&&painted[1]>0,"both bars are actually drawn");
        // Three quarters against one quarter is a factor of three. Allowing a
        // couple of pixels for rounding and antialiasing, but nothing like
        // enough to let two equal-length bars pass.
        double ratio=(double)painted[0]/painted[1];
        check(Math.abs(ratio-3.0)<0.15,"the 75% bar is three times the 25% bar, measured "+String.format("%.2f",ratio));
        int track=FocusBars.track(width);
        check(Math.abs(painted[0]-track*0.75)<=2,"and the long bar matches its computed width");
        check(FocusBars.colourFor(0).getRGB()!=FocusBars.colourFor(1).getRGB(),"adjacent rows are told apart by colour");

        // The chart is pixels and nothing else, so the same rows have to reach
        // the accessible name and description or the whole thing is silent.
        var context=bars.getAccessibleContext();
        check("Time by activity".equals(context.getAccessibleName()),"the chart names itself");
        check("Maths 1h 30m 75% · Prose 30m 25%".equals(context.getAccessibleDescription()),
            "the description spells out the same rows the bars draw, got: "+context.getAccessibleDescription());
        check("No time recorded yet.".equals(new FocusBars(List.of()).getAccessibleContext().getAccessibleDescription()),
            "an empty chart says so rather than describing nothing");
    }

    public static void main(String[] args) {
        clipsToWindow();
        sharesAreOfTheWindow();
        ordersLargestFirstAndStably();
        honoursTheMinimum();
        countsTheRunningSession();
        changeNeedsABaseline();
        barsAreProportional();
        paintsWhatItComputes();
        System.out.println("FocusMixTest ok ("+checks+" checks)");
    }
}
