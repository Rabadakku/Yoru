package dev.yoru.ui;

import dev.yoru.application.Tracker;
import dev.yoru.domain.Model.Tag;
import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.util.UUID;
import static dev.yoru.ui.Theme.*;

/**
 * Tag create, rename, recolour and delete (#3).
 *
 * Unlike PartyEditor this is not a draft: Tracker commits each tag operation on
 * its own, so re-implementing a transaction here would only be able to get it
 * wrong. Deleting is confirmed instead, and says plainly that the tasks stay.
 */
final class TagEditor extends JPanel {
    private final Tracker tracker;
    private final Runnable changed;
    private final JTextField name=new JTextField(18);

    TagEditor(Tracker tracker,Runnable changed) {
        this.tracker=tracker;this.changed=changed;
        setLayout(new BoxLayout(this,BoxLayout.Y_AXIS));setOpaque(false);
        styleInput(name);name.setName("tag.name");
        rebuild();
    }

    /**
     * A fixed square of the tag's own colour: the point of a tag is that it is
     * seen. Rounded on the shared radius, like every other boxed shape.
     */
    static JComponent swatch(int colour,int size) {
        var dot=new JComponent() {
            @Override protected void paintComponent(Graphics graphics) {
                var g=(Graphics2D)graphics.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(new Color(colour));
                g.fillRoundRect(0,0,getWidth()-1,getHeight()-1,RADIUS,RADIUS);
                g.setColor(LINE);
                g.drawRoundRect(0,0,getWidth()-1,getHeight()-1,RADIUS,RADIUS);
                g.dispose();
            }
        };
        dot.setPreferredSize(new Dimension(size,size));
        dot.setMaximumSize(new Dimension(size,size));
        dot.setAlignmentX(0);
        dot.setToolTipText(String.format("Tag colour #%06X",colour));
        return dot;
    }

    private void rebuild() {
        removeAll();
        var tags=tracker.state().tags();
        add(sectionHeader("TAGS · "+tags.size()));gap(this,SPACE_SM);
        add(bodyLabel("Deleting a tag keeps its tasks and simply untags them."));gap(this,SPACE_MD);
        for(var tag:tags) {
            var line=new JPanel(new BorderLayout(12,0));line.setOpaque(false);line.setAlignmentX(0);
            var left=tightRow();
            // The hex code is a developer's string; it lives on the swatch's
            // tooltip now, where it is there for whoever needs it.
            left.add(swatch(tag.colour(),SPACE_LG));
            left.add(label(tag.name(),TYPE_LABEL,TEXT));
            line.add(left,BorderLayout.CENTER);
            var actions=row();
            var rename=button("Rename",()->rename(tag));rename.setName("tag.rename."+tag.id());
            var recolour=button("Colour",()->recolour(tag));recolour.setName("tag.colour."+tag.id());
            var delete=button("Delete",()->delete(tag));delete.setName("tag.delete."+tag.id());
            // Some owners used tags as places; a list is the place (#56).
            var toList=button("Make list",()->toList(tag));toList.setName("tag.toList."+tag.id());
            toList.setToolTipText("Make a list called \""+tag.name()+"\" holding every task with this tag");
            actions.add(rename);actions.add(recolour);actions.add(toList);actions.add(delete);
            line.add(actions,BorderLayout.EAST);
            add(line);gap(this,SPACE_SM);
        }
        if(tags.isEmpty()){add(emptyState("No tags yet.","Add one below; a tag is a colour and a name on a task.",null));gap(this,SPACE_SM);}
        gap(this,SPACE_XS);add(label("NEW TAG",TYPE_CAPTION,MUTED));gap(this,SPACE_SM);
        var creation=tightRow();
        creation.add(name);
        var add=button("Add tag",this::create);add.setName("tag.add");
        creation.add(add);
        add(creation);
        revalidate();repaint();
    }

    private void create() {
        try {
            tracker.addTag(name.getText(),nextColour());
            name.setText("");
            done();
        } catch(Exception e){Dialogs.error(this,e.getMessage());}
    }

    private void toList(Tag tag) {
        long tagged=tracker.state().tasks().stream().filter(t->t.tagIds().contains(tag.id())).count();
        if(!Dialogs.confirm(this,"Make a list called \""+tag.name()+"\" and file "+Theme.plural((int)tagged,"task")
            +" with this tag in it? The tag stays on them; delete it afterwards if you no longer want it.",
            "Make a list from a tag","Make list")) return;
        try{tracker.tagToList(tag.id());done();}
        catch(Exception e){Dialogs.error(this,e.getMessage());}
    }

    private void rename(Tag tag) {
        String next=Dialogs.input(this,"Tag name: up to 40 characters.","Rename tag",tag.name());
        if(next==null)return;
        try{tracker.editTag(tag.id(),next,tag.colour());done();}
        catch(Exception e){Dialogs.error(this,e.getMessage());}
    }

    /**
     * Recolouring a tag, from the app's own palette.
     *
     * The stock colour chooser opens with the platform look and a light ground
     * inside a riced dark window — the only unthemed surface in the app — so it
     * is replaced by the swatch grid the fixed palette already implied. That
     * also keeps every tag legible against every theme, which an arbitrary
     * picked colour does not.
     */
    private void recolour(Tag tag) {
        int[] chosen={tag.colour()};
        var panel=stack();
        panel.add(bodyLabel("Pick a colour for \""+tag.name()+"\"."));
        gap(panel,SPACE_MD);
        panel.add(palette(chosen));
        if(Dialogs.choose(this,panel,"Tag colour","Use this colour","Cancel")!=0)return;
        try{tracker.editTag(tag.id(),tag.name(),chosen[0]);done();}
        catch(Exception e){Dialogs.error(this,e.getMessage());}
    }

    /**
     * The recolour grid: one dot per palette colour, the chosen one ringed.
     *
     * A dot is filled with the colour it stands for. The segmented-choice
     * treatment cannot be used as it stands, because filling the chosen dot with
     * the accent and leaving the rest on LINE is what made every dot the same
     * neutral square — a colour the user cannot see before picking it is not a
     * choice. Selection is carried by the border instead: the shared ring every
     * other control in the app wears, drawn over the dot's own colour, with the
     * same ring on focus (see {@link Theme#ringFor}).
     *
     * Package-private so a test can read the dots' fills without opening the modal.
     */
    static JPanel palette(int[] chosen) {
        var swatches=row();
        var dots=new java.util.ArrayList<JButton>();
        Runnable mark=()->{for(int i=0;i<dots.size();i++)paintDot(dots.get(i),PALETTE[i],PALETTE[i].colour()==chosen[0]);};
        for(int i=0;i<PALETTE.length;i++) {
            var colour=PALETTE[i];
            var dot=button("",()->{chosen[0]=colour.colour();mark.run();});
            dot.setPreferredSize(new Dimension(SPACE_XXL,SPACE_XXL));
            dot.setToolTipText("Use "+colour.name().toLowerCase(java.util.Locale.ROOT)
                +" ("+String.format("#%06X",colour.colour())+")");
            dot.getAccessibleContext().setAccessibleName(colour.name());
            dots.add(dot);
            swatches.add(dot);
        }
        mark.run();
        return swatches;
    }

    /**
     * A palette dot: its own colour, ringed while it is the chosen one.
     *
     * The ring comes from {@link Theme#ringFor}, the same measured choice every
     * other ring in the app makes, because the accent is itself one of these
     * palette colours: a ring the colour of the dot it surrounds is not a cue,
     * so the ring is picked against the dot rather than assumed.
     */
    private static void paintDot(JButton dot,Choice choice,boolean chosen) {
        var colour=new Color(choice.colour());
        selected(dot,false);                                    // the shared resting hairline
        dot.setBackground(colour);                              // ...under the dot's own colour
        if(chosen) dot.setBorder(controlBorder(ringFor(colour))); // the chosen one, ringed
    }

    private void delete(Tag tag) {
        long tagged=tracker.state().tasks().stream().filter(t->t.tagIds().contains(tag.id())).count();
        var message=stack();
        message.add(label("Delete the tag \""+tag.name()+"\"?",TYPE_HEADING,TEXT));gap(message,SPACE_MD);
        message.add(bodyLabel(tagged==0?"No tasks use it."
            :Theme.plural((int)tagged,"task")+" will stay exactly as they are, without this tag."));
        if(!Dialogs.confirmDestructive(this,message,"Delete tag","Delete"))return;
        try{tracker.deleteTag(tag.id());done();}
        catch(Exception e){Dialogs.error(this,e.getMessage());}
    }

    /** Walks a fixed palette so a new tag is legible without asking for a colour first. */
    private int nextColour() { return paletteColour(tracker.state().tags().size()); }

    /** The palette's colours in turn, so each new tag or list starts on a different one. */
    static int paletteColour(int n) { return PALETTE[Math.floorMod(n,PALETTE.length)].colour(); }

    /** One palette entry: the colour, and the name the tooltip and a screen reader read out. */
    private record Choice(String name,int colour) { }

    /** The tag palette, shared by "new tag" and the recolour grid. */
    private static final Choice[] PALETTE={
        new Choice("Teal",0x90D8DA),new Choice("Amber",0xE8B24C),new Choice("Coral",0xD9736A),
        new Choice("Lavender",0xA98BD4),new Choice("Blue",0x6E8FD6),new Choice("Green",0x6FBF8B),
        new Choice("Pink",0xD98CB4),new Choice("Olive",0xC9C273)};

    private void done() {
        rebuild();
        changed.run();
        var window=SwingUtilities.getWindowAncestor(this);
        if(window!=null)window.pack();
    }

    static void open(Component parent,Tracker tracker,Runnable changed) {
        var editor=new TagEditor(tracker,changed);
        Dialogs.choose(parent,editor,"Tags","Done");
    }

    /** Only for tests: the ids currently rendered, in order. */
    java.util.List<UUID> shown() {
        return tracker.state().tags().stream().map(Tag::id).toList();
    }
}
