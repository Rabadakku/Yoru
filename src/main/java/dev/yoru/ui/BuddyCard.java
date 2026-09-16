package dev.yoru.ui;

import dev.yoru.application.Analytics;
import dev.yoru.application.Tracker;
import dev.yoru.domain.Model.State;
import dev.yoru.game.Gen3Pokemon;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;

/**
 * The study buddy — the lead of the game's party — and the time you have spent
 * together.
 *
 * The companion is the same Pokémon the Collection page and the field box show:
 * {@link GameView#lead} takes the first hatched member of the user's own party,
 * its picture comes from the user's own artwork through
 * {@link GameView#sprite}, and its National Dex number stands in when no
 * artwork has been supplied for it. A vault with no save, or a save whose party
 * is still empty, says so and says how to change it rather than inventing a
 * character.
 *
 * The arrangement is fastfetch-shaped on purpose: the companion and a title, a
 * rule, four key/value rows drawn from the vault, a rule, and one sentence with
 * the actions that follow from it. It draws no card chrome of its own — the
 * surface is the hero card YoruApp puts it in, which is what keeps the Today
 * row's two columns the same height. The sentence is keyed to the study history, not
 * to the Pokémon, so it reads the same whoever leads the party — "Together" is
 * the workspace's whole recorded history, and there is nothing extra to store
 * and no migration to make (#47, §4).
 */
final class BuddyCard extends JPanel {
    /** The key column's width, so four rows align under each other. */
    private static final int KEY_WIDTH=96;

    /** How the companion is posed, and what it says, right now. */
    private enum Mood {
        FIRST_RUN("We haven't studied yet. Clock in and this fills up."),
        FIRST_SESSION("First session together. Nice."),
        GROWING(null),
        STREAK(null),
        RESTING("Ready when you are."),
        STUDYING("Studying together.");

        private final String line;
        Mood(String line) { this.line=line; }
    }

    private final Companion companion;
    private final JLabel stateLine;
    private final Mood mood;
    private boolean active;

    BuddyCard(Tracker tracker,ZoneId zone,Runnable openCollection,Runnable openGame) {
        // Contents only: the card this sits in is the surface (see
        // TodayPage.companionColumn). Drawing a second fill and hairline here put a
        // frame inside the frame and pushed this content 24 px off the card
        // title's left edge, where the caption under it lives.
        setLayout(new BoxLayout(this,BoxLayout.Y_AXIS));
        setOpaque(false);
        setAlignmentX(0);

        State state=tracker.state();
        // The buddy is whoever leads the game's party, exactly as the field box
        // and the Collection page have it; 0 means there is no save to read.
        Gen3Pokemon lead=GameView.lead(state);
        int national=lead==null?0:lead.nationalDex();
        boolean shiny=lead!=null&&lead.shiny();
        String name=lead==null?null:GameView.name(lead);
        companion=new Companion(national,shiny,name);

        // "Now" comes from the tracker's clock, not the wall: the same instant
        // the tracker used to record the sessions is the one that sizes them, so
        // a session that spans local midnight is split the same way in the card
        // as it was in the vault, and a test can pin the clock for determinism.
        Instant now=tracker.now();
        LocalDate today=now.atZone(zone).toLocalDate();
        var daily=Analytics.daily(state,null,zone,now);
        long todaySeconds=daily.getOrDefault(today,0L);
        long together=Analytics.recorded(state,Instant.EPOCH,now,now);
        int streak=Analytics.streak(daily,today);
        int sessions=(int)state.sessions().stream().filter(s->Analytics.counts(s,state,now)).count();
        boolean recording=tracker.active()!=null;
        mood=moodOf(recording,sessions,state.activities().size(),todaySeconds,streak);

        // Block one: who this is. The old partner card never said.
        add(who(companion,lead!=null,name,lead==null?null:GameView.detail(lead),lead==null?null:saved(state,now,zone)));
        add(Box.createVerticalStrut(Theme.SPACE_SM));
        add(rule());
        add(Box.createVerticalStrut(Theme.SPACE_SM));
        // Block two: what you have done together.
        add(fact("Together",together==0?"Nothing yet":Analytics.report(together)));
        add(fact("Streak",Theme.plural(streak,"day")));
        add(fact("Today",todaySeconds==0?"Nothing yet":Analytics.report(todaySeconds)));
        add(fact("Sessions",Integer.toString(sessions)));
        add(Box.createVerticalStrut(Theme.SPACE_SM));
        add(rule());
        add(Box.createVerticalStrut(Theme.SPACE_SM));
        // Block three: one sentence, and the things to do about it.
        stateLine=Theme.bodyLabel(stateText(mood,sessions,streak));
        stateLine.setName("buddy.state");
        add(stateLine);
        add(Box.createVerticalStrut(Theme.SPACE_MD));
        // The clock lives on the focus card beside this one (#9): a second
        // Clock in here competed with it, two primaries for one action. The
        // partner keeps the way back to the game and the party it comes from.
        var actions=Theme.row();
        var navigate=Theme.button(lead==null?"Open the game":"Open collection",
            lead==null?openGame:openCollection);
        navigate.getAccessibleContext().setAccessibleName(lead==null
            ?"Open the game":"Open the collection, where your party lives");
        actions.add(navigate);
        stretch(actions);
        add(actions);

        getAccessibleContext().setAccessibleName(lead==null?"No companion yet"
            :(shiny?"Shiny ":"")+name+", your companion");
        getAccessibleContext().setAccessibleDescription(stateLine.getText());
    }

    /** Which of the six states the vault is in. Order matters: the first match wins. */
    private static Mood moodOf(boolean recording,int sessions,int activities,long todaySeconds,int streak) {
        if(recording) return Mood.STUDYING;
        if(sessions==0) return activities==0?Mood.FIRST_RUN:Mood.RESTING;
        if(streak>=2) return Mood.STREAK;
        if(todaySeconds==0) return Mood.RESTING;
        if(sessions<2) return Mood.FIRST_SESSION;
        return Mood.GROWING;
    }

    /** The state's sentence; the two counted states are built here because they carry a number. */
    private static String stateText(Mood mood,int sessions,int streak) {
        if(mood==Mood.GROWING) return Theme.plural(sessions,"session")+" together. Keep going.";
        if(mood==Mood.STREAK) return Theme.plural(streak,"day")+" in a row. Don't break it now.";
        return mood.line;
    }

    /** The companion and its two-line title, side by side. */
    private static JPanel who(JComponent companion,boolean hasLead,String name,String species,String saved) {
        // The labels sit beside the portrait rather than under it, so the card
        // reads as a picture with a caption and not as a header with a logo.
        var text=Theme.stack();
        text.add(Theme.label(hasLead?"TOGETHER":"PARTNER",Theme.TYPE_CAPTION,Theme.MUTED));
        var heading=Theme.label(hasLead?"WITH "+name.toUpperCase(Locale.ROOT):"NOT CHOSEN YET",
            Theme.TYPE_HEADING,Theme.TEXT);
        heading.setName("buddy.who");
        text.add(heading);
        // The species and whether the vault holds the save: what the partner is
        // supportive with (#9), under its name rather than in another card.
        if(species!=null) {
            Theme.gap(text,Theme.SPACE_XS);
            var detail=Theme.shortenable(species,Theme.TYPE_CAPTION,Theme.MUTED);
            detail.setName("buddy.species");
            text.add(detail);
        }
        if(saved!=null) {
            var line=Theme.shortenable(saved,Theme.TYPE_CAPTION,Theme.MUTED);
            line.setName("buddy.saved");
            text.add(line);
        }
        if(!hasLead) {
            Theme.gap(text,Theme.SPACE_XS);
            var detail=Theme.bodyLabel("Choose your starter in the game.");
            detail.setName("buddy.detail");
            text.add(detail);
        }
        // The caption never shrinks and the portrait gives way first, which is
        // what keeps the name whole at the window's minimum width: a fixed
        // frame beside it would truncate "WITH <NAME>" to "WITH…". A box on the
        // x axis shares a short card out this way by itself, using the two
        // minimum sizes, so no resize listener or second layout is needed.
        var caption=new JPanel(new BorderLayout()) {
            // Whole while the portrait can still give way. Past that, with a
            // larger text size, only the name stays whole and the species and
            // save lines beneath it shorten with a tooltip (#31).
            @Override public Dimension getMinimumSize() {
                int whole=text.getPreferredSize().width;
                var pair=getParent();
                if(pair!=null&&pair.getWidth()>0) {
                    var insets=pair.getInsets();
                    int room=pair.getWidth()-insets.left-insets.right-companion.getMinimumSize().width-Theme.SPACE_LG;
                    whole=Math.min(whole,Math.max(heading.getPreferredSize().width,room));
                }
                return new Dimension(whole,0);
            }
        };
        caption.setOpaque(false);
        caption.setAlignmentX(0);
        caption.setAlignmentY(0);
        caption.add(text,BorderLayout.CENTER);

        companion.setAlignmentY(0);
        var pair=new JPanel();
        pair.setLayout(new BoxLayout(pair,BoxLayout.X_AXIS));
        pair.setOpaque(false);
        pair.setAlignmentX(0);
        pair.add(companion);
        pair.add(Box.createHorizontalStrut(Theme.SPACE_LG));
        pair.add(caption);
        stretch(pair);
        return pair;
    }

    /** One key/value row: the key in a fixed column, so the values line up. */
    private static JPanel fact(String key,String value) {
        var line=new JPanel(new BorderLayout(Theme.SPACE_MD,0));
        line.setOpaque(false);
        line.setAlignmentX(0);
        var label=Theme.label(key,Theme.TYPE_CAPTION,Theme.MUTED);
        label.setPreferredSize(new Dimension(Theme.grow(KEY_WIDTH),label.getPreferredSize().height));
        line.add(label,BorderLayout.WEST);
        // Named so a test reads the same value the card shows rather than
        // re-deriving it and checking its own arithmetic.
        var shown=Theme.label(value,Theme.TYPE_BODY,Theme.TEXT);
        shown.setName("buddy."+key.toLowerCase(java.util.Locale.ROOT));
        line.add(shown,BorderLayout.CENTER);
        stretch(line);
        return line;
    }

    /** The hairline between blocks: one pixel, the full width of the card. */
    private static JComponent rule() {
        var line=new JPanel();
        line.setBackground(Theme.LINE);
        line.setOpaque(true);
        line.setAlignmentX(0);
        line.setMaximumSize(new Dimension(Integer.MAX_VALUE,Theme.HAIRLINE));
        line.setPreferredSize(new Dimension(0,Theme.HAIRLINE));
        return line;
    }

    /** When the vault last kept the game's save, or null when it holds none. */
    private static String saved(State state,Instant now,ZoneId zone) {
        var at=GameView.read(state).savedAt();
        return at==null?null:"Saved in your vault · "+Ago.describe(at,now,zone);
    }

    /** Lets a block fill the card's width inside a vertical box layout. */
    private static void stretch(JComponent c) {
        c.setMaximumSize(new Dimension(Integer.MAX_VALUE,c.getPreferredSize().height));
    }

    /**
     * One step of motion. Called from the window's ticker exactly as the scene
     * it replaces was, with the same reduced-motion gate already applied.
     */
    void tick(boolean recording) {
        if(!recording) {
            if(!active) return;
            active=false;
            companion.advance(false);
            return;
        }
        // Nothing on screen to animate: stepping the frame would burn the EDT
        // for a card no one is looking at.
        if(!isShowing()) return;
        active=true;
        companion.advance(true);
    }

    /**
     * The companion itself: the lead of the party, drawn the way the field box
     * draws it.
     *
     * The user's own artwork when there is any for the species, preserving its
     * aspect ratio; a plain shape when there is not; and a question
     * mark when the vault holds no save yet. The breathing, the hop and the
     * sway are the field box's own motion, and the shiny sparkles are the
     * field box's own ticks — the frame advances only while the timer runs, so
     * a card built while the clock is stopped sits still.
     */
    private static final class Companion extends JPanel {
        private final int national;
        private final boolean shiny;
        private final String name;
        private final BufferedImage sprite;
        private int frame;
        private boolean active;

        Companion(int national,boolean shiny,String name) {
            this.national=Math.max(0,national);
            this.shiny=this.national>0&&shiny;
            this.name=this.national==0?null:name==null||name.isBlank()?"No. "+this.national:name;
            sprite=GameView.sprite(this.national,this.shiny);
            setOpaque(false);
            // The portrait's own size — the field box's, and a contract
            // BuddyCardTest pins — never the height of the card around it. The
            // card takes the height its column gives it (TodayPage.companionColumn
            // caps it), so this number no longer sets the hero row's height.
            setPreferredSize(new Dimension(260,168));
            setMinimumSize(new Dimension(150,140));
            // The caption beside it takes the extra room, never the picture.
            setMaximumSize(new Dimension(260,168));
            setAlignmentX(0);
            setName("buddy.companion");
            getAccessibleContext().setAccessibleName(this.national==0?"No companion yet"
                :(this.shiny?"Shiny ":"")+this.name+", your companion");
            getAccessibleContext().setAccessibleDescription(this.national==0
                ?"Choose your starter in the game."
                :sprite==null?"Artwork missing; showing a plain shape."
                :"The lead of your party.");
        }

        /** Exactly {@link BuddyScene#advance}: the frame moves only while recording, and only with a lead. */
        void advance(boolean recording) {
            recording=recording&&national>0;
            boolean changed=active!=recording;
            active=recording;
            if(recording)frame=(frame+1)%100_000;
            if(recording||changed)repaint();
        }

        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            var g=(Graphics2D)graphics.create();
            try {
                int w=getWidth(),h=getHeight();
                if(w<1||h<1)return;
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

                // A framed portrait rather than a bare sprite on the card: the
                // same ground the field box draws, so the companion stands on
                // something instead of floating in the layout.
                g.setColor(Theme.BG);
                g.fillRect(0,0,w,h);
                g.setColor(Theme.LINE);
                g.drawRect(0,0,w-1,h-1);
                int cx=w/2,base=h-14;
                int arenaWidth=Math.min(168,Math.max(40,w-24));
                g.setColor(Theme.LINE);
                g.fillOval(cx-arenaWidth/2,base-12,arenaWidth,20);
                g.setColor(Theme.DISABLED_FILL);
                g.fillOval(cx-arenaWidth/2+4,base-12,arenaWidth-8,14);

                // Breathing, a small hop and a sway while the timer runs; still
                // while it is stopped.
                int hopPhase=frame%70;
                int bob=active?(int)Math.round(Math.sin(frame*.30)*2):0;
                int hop=active&&hopPhase<12?-(int)Math.round(Math.sin(hopPhase/12.0*Math.PI)*8):0;
                int sway=active?(int)Math.round(Math.sin(frame*.14)*2):0;
                g.setColor(Theme.LINE);
                int shadow=Math.max(24,50-Math.abs(hop)*2);
                g.fillOval(cx-shadow/2+sway,base-4,shadow,7);

                int size=Math.max(1,Math.min(128,Math.min(w-32,h-40)));
                if(sprite!=null) {
                    // Preserve the aspect ratio of user artwork, including non-square imports.
                    double scale=Math.min((double)size/sprite.getWidth(),(double)size/sprite.getHeight());
                    int sw=Math.max(1,(int)Math.round(sprite.getWidth()*scale));
                    int sh=Math.max(1,(int)Math.round(sprite.getHeight()*scale));
                    g.drawImage(sprite,cx-sw/2+sway,base-sh+bob+hop,sw,sh,null);
                } else if(national==0) {
                    g.setColor(Theme.TEXT);
                    g.setFont(Theme.mono(Theme.TYPE_FIGURE));
                    g.drawString("?",cx-g.getFontMetrics().stringWidth("?")/2+sway,base-14+bob+hop);
                } else {
                    // A plain shape where the picture would be, never the Dex
                    // number: "#252" read as the partner's name (#9), and the
                    // name is already written beside the portrait.
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
                    g.setColor(Theme.MUTED);
                    int top=base-size+bob+hop;
                    g.fillOval(cx-size/6+sway,top+size/8,size/3,size/3);
                    g.fillRoundRect(cx-size/4+sway,top+size/2,size/2,size/2-8,size/4,size/4);
                }

                if(shiny) {
                    g.setColor(Theme.GOLD);
                    int offset=active?(frame/3)%3:0;
                    for(int i=0;i<3;i++) {
                        int x=cx-size/2+i*size/2,y=base-size+12+((i+offset)%3)*12;
                        g.fillRect(x-3,y,7,1);
                        g.fillRect(x,y-3,1,7);
                    }
                }
            } finally {
                g.dispose();
            }
        }
    }
}
