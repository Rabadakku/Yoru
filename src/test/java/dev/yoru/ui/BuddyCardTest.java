package dev.yoru.ui;

import dev.yoru.application.Analytics;
import dev.yoru.application.Repository;
import dev.yoru.application.Tracker;
import dev.yoru.domain.Model.State;
import dev.yoru.domain.Model.ThemeId;
import dev.yoru.game.Gen3Fixture;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * The study companion: six states, one card, and the numbers in it.
 *
 * The buddy is the lead of the game's own party, so the identity checks are
 * that the card names whoever leads the party, that an empty vault says so
 * instead of inventing a character, and that the way back to the game and the
 * collection follows from which of the two it is. The point of the rest of the
 * card is that the sentence and the button say the same thing about the vault —
 * so the checks are that the six states are distinct, that the values come from
 * the vault rather than from the card's own arithmetic, and that the state is
 * never carried by the picture alone. It paints in every theme, and a card that
 * is not on screen does not animate.
 */
public final class BuddyCardTest {
    private static int checks;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}

    /** A vault in memory, so the card can be built around one state at a time. */
    private static final class Memory implements Repository {
        State state=State.empty();
        public State load(){return state;}
        public void save(State next){state=next;}
        public void close(){}
    }

    private static Tracker tracker(Instant now) throws Exception { return new Tracker(new Memory(),Clock.fixed(now,ZoneOffset.UTC)); }

    /**
     * A vault with one activity and half an hour recorded on each of the given
     * days, counted back from a fixed instant. Anchored to that instant rather
     * than to the wall clock, so the fixture is deterministic: the same "now"
     * always yields the same six states, and a 30-minute session never spans a
     * calendar boundary just because the suite happened to run after midnight.
     */
    private static Tracker withTime(ZoneId zone,Instant now,int... daysAgo) throws Exception {
        var tracker=tracker(now);
        tracker.addActivity("Study",0);
        var id=tracker.state().activities().getFirst().id();
        for(int day:daysAgo) {
            var end=now.minus(Duration.ofDays(day));
            tracker.log(id,end.minus(Duration.ofMinutes(30)),end);
        }
        return tracker;
    }

    /**
     * A vault whose save has a party, so {@link GameView#lead} has somebody to
     * find. The save is invented from scratch by {@link Gen3Fixture}: no real
     * trainer, no real party, no real game.
     */
    private static Tracker withLead(ZoneId zone,Instant now) throws Exception {
        var tracker=withTime(zone,now,0);
        var raw=Gen3Fixture.withTrainer(Gen3Fixture.save(2,4),"TESTER",0,12345,54321);
        raw=Gen3Fixture.withParty(raw,List.of(Gen3Fixture.member(raw,252,12,1)));
        tracker.gameSaved(raw);
        return tracker;
    }

    /** The card, wired to no-ops: what its buttons do is the window's business. */
    private static BuddyCard card(Tracker tracker,ZoneId zone) throws Exception {
        return card(tracker,zone,()->{},()->{});
    }

    private static BuddyCard card(Tracker tracker,ZoneId zone,Runnable openCollection,Runnable openGame) throws Exception {
        return new BuddyCard(tracker,zone,openCollection,openGame);
    }

    private static JComponent find(Container root,String name) {
        for(var child:root.getComponents()) {
            if(child instanceof JComponent own&&name.equals(own.getName())) return own;
            if(child instanceof Container nested) { var found=find(nested,name); if(found!=null) return found; }
        }
        return null;
    }

    private static String text(Container root,String name) {
        return find(root,name) instanceof JLabel label?label.getText():null;
    }

    private static JButton button(Container root,String label) {
        for(var child:root.getComponents()) {
            if(child instanceof JButton b&&label.equals(b.getText())) return b;
            if(child instanceof Container nested) { var found=button(nested,label); if(found!=null) return found; }
        }
        return null;
    }

    /** Lays out a container and everything inside it, so nested boxes get real bounds. */
    private static void layout(Container c) {
        c.doLayout();
        for(Component child:c.getComponents()) if(child instanceof Container nested) layout(nested);
    }

    private static BufferedImage render(BuddyCard card,int width,int height) {
        card.setSize(width,height);
        // The card is a box of boxes, and doLayout lays out only one level —
        // the companion and every label would stay 0x0 and un-painted. Laying
        // out the tree recursively is what makes the nested boxes real.
        layout(card);
        var image=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);
        var g=image.createGraphics();
        card.paint(g);
        g.dispose();
        return image;
    }

    /** One component painted on its own, at the size the card gives it. */
    private static BufferedImage render(JComponent part,int width,int height) {
        part.setSize(width,height);
        var image=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);
        var g=image.createGraphics();
        part.paint(g);
        g.dispose();
        return image;
    }

    private static int colours(BufferedImage image) {
        var seen=new LinkedHashSet<Integer>();
        for(int y=0;y<image.getHeight();y++)
            for(int x=0;x<image.getWidth();x++) seen.add(image.getRGB(x,y));
        return seen.size();
    }

    private static boolean same(BufferedImage a,BufferedImage b) {
        if(a.getWidth()!=b.getWidth()||a.getHeight()!=b.getHeight()) return false;
        for(int y=0;y<a.getHeight();y++) for(int x=0;x<a.getWidth();x++) if(a.getRGB(x,y)!=b.getRGB(x,y)) return false;
        return true;
    }

    public static void main(String[] args)throws Exception {
        var zone=ZoneId.systemDefault();
        // Noon today in the local zone: a wall-clock anchor far from midnight,
        // so a 30-minute fixture session never spans two calendar days no matter
        // when the suite runs. The clock is fixed here and threaded through the
        // tracker, so the card and the vault agree on "now" (see BuddyCard).
        var now=LocalDate.now(zone).atTime(12,0).atZone(zone).toInstant();

        // Undecodable artwork for the fixture's species, in front of the
        // library's own search path, so the portrait exercises the National Dex
        // number rather than whatever art happens to sit in a personal folder.
        var art=Files.createTempDirectory("yoru-buddy-card-test");
        Files.writeString(art.resolve("252.png"),"missing artwork fixture");
        Files.createDirectories(art.resolve("shiny"));
        Files.writeString(art.resolve("shiny/252.png"),"missing artwork fixture");
        String old=System.getProperty("yoru.art.dir");
        System.setProperty("yoru.art.dir",art.toString());
        SpriteAssets.refresh();
        try {

        // Built off the EDT: a tracker is not a Swing object.
        var resting=card(withTime(zone,now),zone);                 // activities, nothing recorded
        var firstSession=card(withTime(zone,now,0),zone);
        var growing=card(withTime(zone,now,0,3),zone);
        var streak=card(withTime(zone,now,0,1),zone);
        var studyingTracker=withTime(zone,now,0);
        studyingTracker.start(studyingTracker.state().activities().getFirst().id());
        var studying=card(studyingTracker,zone);
        var firstRun=card(tracker(now),zone);
        long together=Analytics.recorded(withTime(zone,now,0).state(),Instant.EPOCH,now,now);

        // The buddy identity: the lead of the game's party, or nothing yet.
        var leadTracker=withLead(zone,now);
        var lead=GameView.lead(leadTracker.state());
        check(lead!=null,"the fixture's save has a party lead");
        check(lead.nationalDex()==252,"the fixture's lead is the invented party's first member");
        check(GameView.sprite(lead.nationalDex(),lead.shiny())==null,
            "with no artwork the portrait has no sprite to draw");
        String leadName=GameView.name(lead);
        var withLead=card(leadTracker,zone);
        var noLead=card(tracker(now),zone);
        var opened=new String[1];
        var navCard=card(leadTracker,zone,()->opened[0]="collection",()->opened[0]="game");

        SwingUtilities.invokeAndWait(()->{
            // --- the six states, and that they are six distinct sentences ----
            var lines=Arrays.asList(
                text(firstRun,"buddy.state"), text(resting,"buddy.state"), text(firstSession,"buddy.state"),
                text(growing,"buddy.state"), text(streak,"buddy.state"), text(studying,"buddy.state"));
            check(new LinkedHashSet<>(lines).size()==6,"the six states read as six sentences, got "+lines);
            check("We haven't studied yet. Clock in and this fills up.".equals(lines.get(0)),
                "a vault with nothing in it says so, got "+lines.get(0));
            check("Ready when you are.".equals(lines.get(1)),"activities and no recorded time is resting");
            check("First session together. Nice.".equals(lines.get(2)),"one session is the first session");
            check("2 sessions together. Keep going.".equals(lines.get(3)),"two sessions is growing, got "+lines.get(3));
            check("2 days in a row. Don't break it now.".equals(lines.get(4)),"a two day streak says so, got "+lines.get(4));
            check("Studying together.".equals(lines.get(5)),"a running timer is studying");

            // --- the study sentence is the same whoever leads the party ------
            // The six states are keyed to the vault, not to the buddy, so the
            // same history reads identically with a party lead and without one.
            check(stateText(firstSession).equals(stateText(withLead)),
                "the study sentence does not depend on the party");

            // --- the state is not carried by the picture alone ----------------
            for(var card:List.of(firstRun,resting,firstSession,growing,streak,studying)) {
                check(text(card,"buddy.state").equals(card.getAccessibleContext().getAccessibleDescription()),
                    "the state sentence is the accessible description too");
            }
            for(var card:List.of(firstRun,resting,firstSession,growing,streak,studying,noLead)) {
                check("No companion yet".equals(card.getAccessibleContext().getAccessibleName()),
                    "a card with no party lead does not claim a companion");
            }

            // --- the buddy is the lead of the party ---------------------------
            check(withLead.getAccessibleContext().getAccessibleName().equals(leadName+", your companion"),
                "the companion is named for the party lead, got "
                    +withLead.getAccessibleContext().getAccessibleName());
            check(("WITH "+leadName.toUpperCase(Locale.ROOT)).equals(text(withLead,"buddy.who")),
                "the heading names the party lead, got "+text(withLead,"buddy.who"));
            check("TOGETHER".equals(caption(withLead)),"and still reads as the fastfetch caption, got "+caption(withLead));
            check("PARTNER".equals(caption(noLead)),"an empty vault heads the block with PARTNER");
            check("NOT CHOSEN YET".equals(text(noLead,"buddy.who")),
                "and says there is no companion yet, got "+text(noLead,"buddy.who"));
            check("Choose your starter in the game.".equals(text(noLead,"buddy.detail")),
                "and says how to fill it, got "+text(noLead,"buddy.detail"));
            check(text(withLead,"buddy.detail")==null,"a party lead needs no instruction");

            // --- the shortcut back to the game and the collection -------------
            check(button(noLead,"Open the game")!=null,"a vault with no save offers the game");
            check(button(noLead,"Open collection")==null,"and not the collection");
            check(button(withLead,"Open collection")!=null,"a save with a party offers the collection");
            check(button(withLead,"Open the game")==null,"and not the game");
            check(String.valueOf(text(withLead,"buddy.species")).startsWith("Lv ")
                &&String.valueOf(text(withLead,"buddy.saved")).startsWith("Saved in your vault · "),
                "the partner gives its level and species, and says the vault holds the save (#9), got "
                    +text(withLead,"buddy.species")+" / "+text(withLead,"buddy.saved"));
            check(text(noLead,"buddy.saved")==null&&text(noLead,"buddy.species")==null,"with no save there is nothing to vouch for");
            var shortcut=button(navCard,"Open collection");
            check(shortcut!=null,"the collection shortcut is on the card");
            check(shortcut.getAccessibleContext().getAccessibleName()!=null
                &&!shortcut.getAccessibleContext().getAccessibleName().isBlank(),
                "the shortcut has an accessible name");
            shortcut.doClick();
            check("collection".equals(opened[0]),"the collection shortcut navigates, got "+opened[0]);

            // --- the clock lives on the focus card, not here (#9) ------------
            // Two Clock in buttons side by side competed for one action.
            for(var card:List.of(firstRun,resting,studying,withLead))
                check(button(card,"Clock in")==null&&button(card,"■  Clock out")==null&&button(card,"+ Activity")==null,
                    "the partner card repeats none of the focus card's actions");

            // --- the numbers come from the vault, not from the card ----------
            check(together==1800,"the fixture recorded half an hour, got "+together);
            check("30m".equals(text(firstSession,"buddy.together")),
                "Together is the whole recorded history in the report form, got "+text(firstSession,"buddy.together"));
            check("1".equals(text(firstSession,"buddy.sessions")),
                "Sessions counts the records, got "+text(firstSession,"buddy.sessions"));
            // Not an exact figure: a session that spans local midnight is split
            // across the two days, which is the rule everywhere else too.
            check(!"Nothing yet".equals(text(firstSession,"buddy.today")),
                "Today reports the share that falls on today, got "+text(firstSession,"buddy.today"));

            // --- number agreement --------------------------------------------
            check("1 day".equals(text(firstSession,"buddy.streak")),
                "a one day streak is singular, got "+text(firstSession,"buddy.streak"));
            check("2 days".equals(text(streak,"buddy.streak")),
                "a two day streak is plural, got "+text(streak,"buddy.streak"));
        });

        // --- every theme paints a real picture -----------------------------
        for(var theme:ThemeId.values()) {
            Theme.apply(theme);
            var image=render(card(withTime(zone,now,0,1),zone),320,260);
            check(colours(image)>=4,theme+" paints the companion card ("+colours(image)+" colours)");

            // The portrait on its own, with a party lead and no artwork for it:
            // the fallback path the National Dex number exists for.
            var companion=find(card(leadTracker,zone),"buddy.companion");
            check(companion!=null,"the card holds the companion");
            check(new Dimension(260,168).equals(companion.getPreferredSize()),
                "the portrait is the field box's size, got "+companion.getPreferredSize());
            check(new Dimension(150,140).equals(companion.getMinimumSize()),
                "and never shrinks below the field box's minimum, got "+companion.getMinimumSize());
            var portrait=render(companion,260,168);
            check(colours(portrait)>=4,theme+" paints the companion without artwork ("+colours(portrait)+" colours)");
            // "?" for no save, a plain shape for a lead without artwork: two different pictures.
            var empty=find(card(tracker(now),zone),"buddy.companion");
            check(!same(portrait,render(empty,260,168)),
                theme+" draws a plain shape where an empty card draws a question mark");
        }
        Theme.apply(ThemeId.MIDNIGHT);

        // --- a card that is not on screen does not animate ----------------
        // The window's ticker keeps calling in; stepping the frame for a card
        // nobody is looking at would burn the EDT for nothing.
        var offscreen=card(leadTracker,zone);
        var before=render(offscreen,320,260);
        for(int i=0;i<10;i++) offscreen.tick(true);
        check(same(before,render(offscreen,320,260)),"an unshown card stays still while the timer runs");
        offscreen.tick(false);
        check(same(before,render(offscreen,320,260)),"and stays still when it stops");

        } finally {
            if(old==null) System.clearProperty("yoru.art.dir"); else System.setProperty("yoru.art.dir",old);
            SpriteAssets.refresh();
        }

        System.out.println("PASS: "+checks+" companion-card checks (party lead, six states, values, themes, accessibility)");
        System.exit(0);
    }

    /** The fastfetch caption above the heading. */
    private static String caption(Container card) {
        var heading=find(card,"buddy.who");
        if(heading==null||heading.getParent()==null) return null;
        for(var sibling:heading.getParent().getComponents())
            if(sibling instanceof JLabel label&&label!=heading) return label.getText();
        return null;
    }

    /** The card's own state sentence, read back the way a screen reader would. */
    private static String stateText(BuddyCard card) {
        return card.getAccessibleContext().getAccessibleDescription();
    }
}
