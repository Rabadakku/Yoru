package dev.yoru.ui;

import dev.yoru.domain.Model.ThemeId;
import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Legibility, on all four palettes — and the keyboard, which has no other guard.
 *
 * Split into two halves on purpose, because the first half is not enough. The
 * palette can say 4.6:1 while the screen shows 1.9:1, and it did: BasicButtonUI
 * paints disabled text as {@code getBackground().darker()} and ignores
 * {@code Button.disabledText} altogether, so on a dark theme the label landed
 * darker than the fill it sat on. That shipped, and was reported from use.
 *
 * So the second half renders an actual control and measures the pixels. It also
 * covers the three foundation defects of the #47 audit that a palette check
 * cannot see: no focus indication on any control, a destructive dialog that
 * opens with Delete under the Return key, and a type scale whose three title
 * sizes (25/26/28) were all meant to be the same thing.
 */
public final class ContrastTest {
    private static int checks;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}

    /**
     * Every file the type and spacing scales are enforced on: the foundation
     * itself, plus every page and dialog panel that puts type on screen.
     *
     * The list is the ratchet. Adding a page here is what stops the scale
     * drifting again one call site at a time, and a new page is not finished
     * until it is on it.
     */
    private static final List<String> SOURCE_FILES=List.of(
        "src/main/java/dev/yoru/ui/Theme.java",
        "src/main/java/dev/yoru/ui/Dialogs.java",
        "src/main/java/dev/yoru/ui/YoruApp.java",
        "src/main/java/dev/yoru/ui/TasksPanel.java",
        "src/main/java/dev/yoru/ui/HabitsPanel.java",
        "src/main/java/dev/yoru/ui/CollectionPage.java",
        "src/main/java/dev/yoru/ui/GamePage.java",
        "src/main/java/dev/yoru/ui/ActivityManager.java",
        "src/main/java/dev/yoru/ui/TagEditor.java",
        "src/main/java/dev/yoru/ui/WeeklyTemplate.java",
        "src/main/java/dev/yoru/ui/TaskPastePanel.java",
        "src/main/java/dev/yoru/ui/NotionImportPanel.java",
        "src/main/java/dev/yoru/ui/DateTimeField.java",
        "src/main/java/dev/yoru/ui/DateField.java",
        "src/main/java/dev/yoru/ui/CalendarPanel.java",
        "src/main/java/dev/yoru/ui/WaifuPanel.java",
        "src/main/java/dev/yoru/ui/BuddyCard.java");

    private static double luminance(int rgb) {
        double[] channel=new double[3];
        int[] raw={(rgb>>16)&0xFF,(rgb>>8)&0xFF,rgb&0xFF};
        for(int i=0;i<3;i++){
            double v=raw[i]/255.0;
            channel[i]=v<=0.03928?v/12.92:Math.pow((v+0.055)/1.055,2.4);
        }
        return 0.2126*channel[0]+0.7152*channel[1]+0.0722*channel[2];
    }
    private static double ratio(double a,double b){ return (Math.max(a,b)+0.05)/(Math.min(a,b)+0.05); }
    private static double ratio(Color a,Color b){ return ratio(luminance(a.getRGB()),luminance(b.getRGB())); }

    /** Paints a control and reports the strongest ink it actually put on screen. */
    private static double renderedContrast(JButton button,Color ground,boolean darkTheme) {
        button.setSize(button.getPreferredSize());
        button.doLayout();
        var image=new BufferedImage(Math.max(1,button.getWidth()),Math.max(1,button.getHeight()),
            BufferedImage.TYPE_INT_RGB);
        var g=image.createGraphics();
        g.setColor(ground);
        g.fillRect(0,0,image.getWidth(),image.getHeight());
        button.paint(g);
        g.dispose();

        // Ink is the extreme away from the fill: brightest on a dark theme,
        // darkest on a light one. Sampled inside the border, which is not text.
        double fill=luminance(Theme.DISABLED_FILL.getRGB());
        double best=fill;
        for(int y=3;y<image.getHeight()-3;y++)
            for(int x=3;x<image.getWidth()-3;x++) {
                double l=luminance(image.getRGB(x,y));
                if(darkTheme?l>best:l<best) best=l;
            }
        return ratio(best,fill);
    }

    public static void main(String[] args)throws Exception{
        for(var id:ThemeId.values()) {
            Theme.apply(id);
            var palette=Theme.palette(id);

            // What the palette promises.
            check(ratio(palette.text(),palette.panel())>=7.0,
                id+" body text on a panel, "+String.format("%.2f",ratio(palette.text(),palette.panel())));
            check(ratio(palette.muted(),palette.panel())>=4.5,
                id+" muted text on a panel, "+String.format("%.2f",ratio(palette.muted(),palette.panel())));
            check(ratio(palette.accent(),palette.bg())>=3.0,
                id+" accent on the ground, "+String.format("%.2f",ratio(palette.accent(),palette.bg())));
            check(ratio(palette.danger(),palette.panel())>=4.0,
                id+" danger text on a panel, "+String.format("%.2f",ratio(palette.danger(),palette.panel())));
            // A selected table row is text on a fill, so it is held to the same
            // floor. The task and import tables select with TEXT on LINE, the
            // theme's own roles: the accent they used to select with measured
            // 2.4:1 on the light themes, which is what this catches.
            check(ratio(palette.text(),palette.line())>=4.5,
                id+" selected-row text on its selection fill, "
                +String.format("%.2f",ratio(palette.text(),palette.line())));
            check(ratio(palette.disabledText(),palette.disabledFill())>=7.0,
                id+" disabled text against its fill, "
                +String.format("%.2f",ratio(palette.disabledText(),palette.disabledFill())));

            // The accent is a fill and an active state; small type needs its own
            // role. Sakura's pink measured 3.5:1 on the panel and Linen's gold
            // 3.0:1, so a section header, a "Done" pill or a habit numeral was
            // below AA while every fill stayed bright. Both roles exist so a page
            // never has to trade one against the other.
            check(ratio(palette.accentText(),palette.panel())>=4.5,
                id+" readable accent text on a panel, "
                +String.format("%.2f",ratio(palette.accentText(),palette.panel())));
            check(Theme.sectionHeader("TASKS").getForeground().equals(palette.accentText()),
                id+" a section header is not drawn in the readable accent role");
            // The second accent needs the same pair of roles, for the same
            // reason: Linen's gold measured 3.0:1 on the panel and Sakura's
            // 2.9:1, so a "today" date, a gold line of prose or a gold card
            // header was below AA while every gold fill stayed bright.
            check(ratio(palette.goldText(),palette.panel())>=4.5,
                id+" readable gold text on a panel, "
                +String.format("%.2f",ratio(palette.goldText(),palette.panel())));
            check(Theme.sectionHeader("TASKS",Theme.GOLD).getForeground().equals(palette.goldText()),
                id+" a section header passed gold is not drawn in the readable gold role");

            // Disabled has to look disabled. Linen's disabled text sat 1.21:1
            // from its body text — the same colour to the eye — so the ↑/↓ arrows
            // on the first and last rows of a task list looked fully enabled; the
            // legibility floor above caps how light that text can go, so the
            // other half of the cue is the fill, which on a light ground is the
            // surface the control sits on rather than a second panel colour.
            check(ratio(palette.disabledText(),palette.text())>=1.4,
                id+" disabled text is "
                +String.format("%.2f",ratio(palette.disabledText(),palette.text()))
                +":1 from body text — a disabled control reads as an enabled one");
            if(!palette.dark()) check(ratio(palette.disabledFill(),palette.panel())<=1.12,
                id+" a disabled fill is "
                +String.format("%.2f",ratio(palette.disabledFill(),palette.panel()))
                +":1 from the panel it sits on — a second raised surface, not a flat one");

            // What the screen actually shows. A disabled control still says what
            // it would do, so it is text and has to be readable.
            var disabled=Theme.button("Current buddy",()->{});
            disabled.setEnabled(false);
            double rendered=renderedContrast(disabled,Theme.PANEL,palette.dark());
            check(rendered>=4.0,id+" renders disabled text at "+String.format("%.2f",rendered)
                +":1 — the palette can promise more than the look-and-feel delivers");

            // An enabled control must not somehow be worse.
            var enabled=Theme.button("Nickname",()->{});
            double live=renderedContrast(enabled,Theme.PANEL,palette.dark());
            check(live>=4.0,id+" renders enabled text at "+String.format("%.2f",live)+":1");

            focusIsVisibleOnEveryControl(id);
            filledButtonsShowTheKeyboard(id);
            comboBoxesShowTheirChevron(id);
            spinnerStepsAreBigEnough(id);
            hoverAndPressAreQuiet(id);
        }
        typeScaleIsOneLadder();
        spacingIsOneGrid();
        destructiveDialogsOpenOnTheSafeOption();
        System.out.println("PASS: "+checks+" foundation checks (contrast, focus, scale, dialog safety; four themes)");
    }

    // ------------------------------------------------------------------ focus

    /** The band of pixels a focus ring occupies: the outside of a control, inside its hairline. */
    private static final int RING_BAND = 3;

    /** Paints a control over a known ground and returns the pixels. */
    private static BufferedImage render(JComponent control,Color ground) {
        control.setSize(control.getPreferredSize());
        control.doLayout();
        var image=new BufferedImage(Math.max(1,control.getWidth()),Math.max(1,control.getHeight()),
            BufferedImage.TYPE_INT_RGB);
        var g=image.createGraphics();
        g.setColor(ground);
        g.fillRect(0,0,image.getWidth(),image.getHeight());
        control.paint(g);
        g.dispose();
        return image;
    }

    private static boolean inBand(int x,int y,int w,int h) {
        return x<RING_BAND||y<RING_BAND||x>=w-RING_BAND||y>=h-RING_BAND;
    }

    /** Pixels of the ring band that differ between a resting and a focused render. */
    private static int changedInBand(BufferedImage resting,BufferedImage focused) {
        if(resting.getWidth()!=focused.getWidth()||resting.getHeight()!=focused.getHeight()) return Integer.MAX_VALUE;
        int changed=0;
        for(int y=0;y<resting.getHeight();y++) for(int x=0;x<resting.getWidth();x++)
            if(inBand(x,y,resting.getWidth(),resting.getHeight())
                &&resting.getRGB(x,y)!=focused.getRGB(x,y)) changed++;
        return changed;
    }

    /** True when a pixel close to the wanted colour is in the band; antialiasing blurs the ring's edge. */
    private static boolean ringColourInBand(BufferedImage image,Color want) {
        for(int y=0;y<image.getHeight();y++) for(int x=0;x<image.getWidth();x++) {
            if(!inBand(x,y,image.getWidth(),image.getHeight())) continue;
            var got=new Color(image.getRGB(x,y));
            if(Math.abs(got.getRed()-want.getRed())<=32&&Math.abs(got.getGreen()-want.getGreen())<=32
                &&Math.abs(got.getBlue()-want.getBlue())<=32) return true;
        }
        return false;
    }

    /**
     * One of every interactive class the app puts on screen, built the way the
     * app builds it — including the button JOptionPane creates for itself, which
     * depends on UIManager defaults alone.
     */
    private static Map<String,JComponent> controls() {
        var controls=new LinkedHashMap<String,JComponent>();
        controls.put("button",Theme.button("Save",()->{}));
        controls.put("accent button",Theme.accentButton("Play",()->{}));
        controls.put("dialog button",new JButton("OK"));
        controls.put("text field",new JTextField("Read chapter 4",12));
        controls.put("styled field",Theme.styleInput(new JTextField("Read chapter 4",12)));
        controls.put("password field",new JPasswordField("invented-passphrase",12));
        controls.put("formatted field",new JFormattedTextField("2026-09-11"));
        controls.put("combo box",Theme.plainCombo(new JComboBox<>(new String[]{"Study","Coding"})));
        controls.put("plain combo box",new JComboBox<>(new String[]{"Study","Coding"}));
        controls.put("check box",new JCheckBox("Done today"));
        // Metal's slider delegate paints no focus of its own, so unless its
        // border carries the ring the volume control is invisible to the keyboard.
        controls.put("slider",Theme.plainSlider(new JSlider(0,100,50)));
        var table=Theme.plainTable(new JTable(new Object[][]{{"Read chapter 4","CS 240"}},new Object[]{"Task","Tag"}));
        table.setPreferredSize(new Dimension(240,72));
        controls.put("table",table);
        return controls;
    }

    /**
     * Every control must show the keyboard.
     *
     * A control whose resting and focused renders are identical cannot be used
     * from the keyboard at all: the user has no way to know where Return will
     * land. Focus is moved through the same method the focus manager calls, so
     * what is measured is the production path rather than a test-only door.
     */
    private static void focusIsVisibleOnEveryControl(ThemeId id) {
        // The ring is only ever drawn because the focus manager says who holds
        // the keyboard. Headless rendering cannot move real focus, so the wiring
        // itself is what gets checked here, alongside the ring it produces.
        check(KeyboardFocusManager.getCurrentKeyboardFocusManager().getPropertyChangeListeners("focusOwner").length>0,
            id+" no one is listening for the keyboard, so no control can ever show focus");
        for(var entry:controls().entrySet()) {
            var control=entry.getValue();
            var resting=render(control,Theme.PANEL);
            check(!Theme.focused(control),id+" "+entry.getKey()+" claims focus before it has any");
            Theme.focusMoved(null,control);
            check(Theme.focused(control),id+" "+entry.getKey()+" does not take the keyboard");
            var focused=render(control,Theme.PANEL);
            check(changedInBand(resting,focused)>=16,
                id+" "+entry.getKey()+" renders identically focused and resting — no focus indication");
            check(ringColourInBand(focused,Theme.ringFor(control)),
                id+" "+entry.getKey()+" does not draw the shared focus ring colour");
            Theme.focusMoved(control,null);
            check(!Theme.focused(control),id+" "+entry.getKey()+" keeps the keyboard after losing it");
        }
        // The ring has to be visible on the control it surrounds, not merely a
        // colour the palette contains.
        for(var fill:List.of(Theme.LINE,Theme.CYAN,Theme.PANEL))
            check(ratio(Theme.ringFor(fill),fill)>=3.0,
                id+" focus ring on "+String.format("#%06X",fill.getRGB()&0xFFFFFF)+" is "
                +String.format("%.2f",ratio(Theme.ringFor(fill),fill))+":1");
    }

    // --------------------------------------------------------------- controls

    /**
     * A filled button has to show the keyboard as well as an outline one.
     *
     * The shared ring is painted by the shared border, so a primary button that
     * keeps that border is already covered — but a filled *chip* replaces its
     * border with a hairline of its own (the status pills, "Track", "Edit" and
     * the ↑/↓ reorder arrows all do it) and the ring was skipped for every filled
     * button on the grounds that the border would draw it. On those the keyboard
     * was invisible, which is the one cue that cannot be worked around without a
     * mouse. Built the way the panel builds them.
     */
    private static void filledButtonsShowTheKeyboard(ThemeId id) {
        var chip=Theme.selected(Theme.button("All",()->{}),true);
        chip.setFont(Theme.captionFont());
        chip.setBorder(new CompoundBorder(new LineBorder(Theme.LINE),
            new EmptyBorder(Theme.SPACE_XS,Theme.SPACE_SM,Theme.SPACE_XS,Theme.SPACE_SM)));
        var resting=render(chip,Theme.PANEL);
        Theme.focusMoved(null,chip);
        var focused=render(chip,Theme.PANEL);
        check(changedInBand(resting,focused)>=16,
            id+" a filled chip with its own border renders identically focused and resting");
        check(ringColourInBand(focused,Theme.ringFor(chip)),
            id+" a filled chip does not draw the shared focus ring colour");
        Theme.focusMoved(chip,null);
    }

    /**
     * A combo box has to say it is a picker.
     *
     * Its drop-down button is a filled square whether or not anything is drawn
     * in it, so the chevron is the whole affordance. Every combo in the app lost
     * it: the look-and-feel paints the triangle in the same LINE it fills the
     * button with, and the only way to separate the two in its palette is to
     * give the button a hard two-pixel edge as well. The button is still the
     * look-and-feel's own, so its hit area and its open/select behaviour are not
     * this test's business — what is checked is that the square is not blank.
     */
    private static void comboBoxesShowTheirChevron(ThemeId id) {
        var combo=Theme.plainCombo(new JComboBox<>(new String[]{"Study","Coding"}));
        combo.setSize(combo.getPreferredSize());
        combo.doLayout();
        var image=render(combo,Theme.PANEL);
        var r=arrowButton(combo).getBounds();
        int ink=0;
        for(int y=r.y+2;y<r.y+r.height-2;y++)
            for(int x=r.x+2;x<r.x+r.width-2;x++)
                if(!near(new Color(image.getRGB(x,y)),Theme.LINE,24)) ink++;
        check(ink>=8,id+" the combo's drop-down button paints "+ink
            +" pixels that are not its own fill: it is a blank square, not a picker");
    }

    /** The drop-down button the combo's UI adds: the one child that is a button. */
    private static JButton arrowButton(JComboBox<?> combo) {
        for(var child:combo.getComponents()) if(child instanceof JButton button) return button;
        throw new AssertionError("a combo box no longer has a drop-down button");
    }

    /**
     * A spinner's two step buttons are big enough to press, and answer where
     * they say they do.
     *
     * The look-and-feel's arrows were a 16 px-wide column split into two 16
     * px-tall halves: the smallest hit targets in the app, and the only chrome
     * the palette could not reach. Two full-height step buttons replace them.
     * The drawn button may still be narrower than its target — a 90 px settings
     * spinner cannot spare more — so what is checked is the target the button
     * answers to, which is what a press has to land on, and then that a press
     * really steps, that a disabled one does not, and that both draw a chevron
     * the palette can see.
     */
    private static void spinnerStepsAreBigEnough(ThemeId id) {
        var spinner=Theme.plainSpinner(new JSpinner(new SpinnerNumberModel(30,0,600,5)));
        spinner.setSize(spinner.getPreferredSize());
        spinner.doLayout();
        for(var name:List.of("Spinner.nextButton","Spinner.previousButton")) {
            var button=stepButton(spinner,name);
            var hit=button.hitBounds();
            check(hit.width>=Theme.SPACE_XL&&hit.height>=Theme.SPACE_XL,
                id+" "+name+" answers to a "+hit.width+"x"+hit.height+" target, under the 24 px floor");
            check(button.contains(hit.x+1,hit.y+1)&&button.contains(hit.x+hit.width-1,hit.y+hit.height-1),
                id+" "+name+" does not answer where its target says it does");
            check(!button.isFocusable(),
                id+" "+name+" takes the keyboard from the field");
            check(ink(button,Theme.TEXT)>=8,
                id+" "+name+" paints "+ink(button,Theme.TEXT)+" pixels of chevron: it is a blank square");
        }

        // Pressing it steps, the way the mouse would deliver the press.
        var up=stepButton(spinner,"Spinner.nextButton");
        int start=(Integer)spinner.getValue();
        press(up);
        check((Integer)spinner.getValue()==start+5,
            id+" a step button press moved the value from "+start+" to "+spinner.getValue());

        // And a disabled spinner steps nothing, with buttons that say so.
        spinner.setEnabled(false);
        int before=(Integer)spinner.getValue();
        for(var name:List.of("Spinner.nextButton","Spinner.previousButton")) {
            var button=stepButton(spinner,name);
            check(!button.isEnabled(),id+" "+name+" stays enabled when the spinner does not");
            check(ink(button,Theme.DISABLED_TEXT)>=8,
                id+" "+name+" draws no disabled chevron: a disabled control must look disabled");
        }
        press(up);
        check((Integer)spinner.getValue()==before,id+" a disabled spinner stepped its value");
    }

    /** The step button the spinner's UI added, by the name it gives it. */
    private static Theme.StepButton stepButton(JSpinner spinner,String name) {
        for(var child:spinner.getComponents())
            if(child instanceof Theme.StepButton button&&name.equals(button.getName())) return button;
        throw new AssertionError("a spinner no longer has a "+name);
    }

    /** Pixels of the control's own drawing that come within reach of a colour. */
    private static int ink(JComponent control,Color want) {
        var image=render(control,Theme.PANEL);
        int count=0;
        for(int y=0;y<image.getHeight();y++) for(int x=0;x<image.getWidth();x++)
            if(near(new Color(image.getRGB(x,y)),want,40)) count++;
        return count;
    }

    /** A press and release, as the mouse would deliver them. */
    private static void press(JComponent control) {
        long when=System.currentTimeMillis();
        control.dispatchEvent(new java.awt.event.MouseEvent(control,java.awt.event.MouseEvent.MOUSE_PRESSED,
            when,0,1,1,1,false,java.awt.event.MouseEvent.BUTTON1));
        control.dispatchEvent(new java.awt.event.MouseEvent(control,java.awt.event.MouseEvent.MOUSE_RELEASED,
            when,0,1,1,1,false,java.awt.event.MouseEvent.BUTTON1));
    }

    private static boolean near(Color got,Color want,int tolerance) {
        return Math.abs(got.getRed()-want.getRed())<=tolerance
            &&Math.abs(got.getGreen()-want.getGreen())<=tolerance
            &&Math.abs(got.getBlue()-want.getBlue())<=tolerance;
    }

    // ------------------------------------------------------------------ scale

    /**
     * The type scale is one ladder, not a bag of sizes.
     *
     * Three title sizes and two subtitle sizes shipped because each page chose
     * for itself; a reader sees that wobble even when they cannot name it. This
     * is the contract: a page names a role, never a number.
     */
    private static void typeScaleIsOneLadder() {
        check(Theme.TYPE_TIMER>Theme.TYPE_TITLE,"the timer is the largest thing on a page");
        check(Theme.TYPE_TITLE>Theme.TYPE_FIGURE,"a page title outranks a card's figure");
        check(Theme.TYPE_FIGURE>Theme.TYPE_HEADING,"a card's figure outranks a dialog heading");
        check(Theme.TYPE_HEADING>Theme.TYPE_PROSE,"a heading outranks prose");
        check(Theme.TYPE_PROSE>Theme.TYPE_LABEL,"prose outranks a button face");
        check(Theme.TYPE_LABEL>Theme.TYPE_SECTION,"a button face outranks a section header");
        check(Theme.TYPE_SECTION>=Theme.TYPE_CAPTION,"section signposts meet the caption legibility floor");
        check(Theme.TYPE_BODY>Theme.TYPE_CAPTION,"body copy outranks a caption");
        check(Theme.TYPE_CAPTION>=11,""+Theme.TYPE_CAPTION+" px is below the mono face's legibility floor");
        check(Theme.TYPE_TIMER<=60,"the timer stops growing at "+Theme.TYPE_TIMER+" px");

        // And the foundation passes roles, not numbers, so the scale below it
        // cannot drift one call site at a time.
        for(var file:SOURCE_FILES) {
            var source=read(file);
            var font=Pattern.compile("\\b(?:mono|sans)\\((\\d+)").matcher(source);
            while(font.find()) check(false,file+" passes the raw font size "+font.group(1)+" instead of a role");
            var label=Pattern.compile("\\blabel\\([^;]*?,\\s*(\\d+)\\s*,").matcher(source);
            while(label.find()) check(false,file+" passes the raw label size "+label.group(1)+" instead of a role");
        }
    }

    /**
     * The spacing scale is one 8-pt grid.
     *
     * Every ad-hoc padding was a number nobody could check; the same card had
     * 20 px of padding and 22 px of it, and six row layouts said "gap" six ways.
     * The grid is the whole rule, and the foundation may not step off it.
     */
    private static void spacingIsOneGrid() {
        var grid=List.of(Theme.SPACE_XS,Theme.SPACE_SM,Theme.SPACE_MD,Theme.SPACE_LG,Theme.SPACE_XL,Theme.SPACE_XXL);
        check(grid.equals(List.of(4,8,12,16,24,32)),"the spacing scale is the 8-pt grid, got "+grid);
        for(int i=1;i<grid.size();i++) check(grid.get(i)>grid.get(i-1),"the spacing scale ascends");

        var allowed=new HashSet<Integer>(grid);
        allowed.add(0); // no inset at all is a legitimate choice
        allowed.add(Theme.HAIRLINE);
        allowed.add(Theme.RING);
        for(var file:SOURCE_FILES) {
            var source=read(file);
            var call=Pattern.compile("(?:EmptyBorder|FlowLayout|gap)\\(([^()]*)\\)").matcher(source);
            while(call.find()) {
                var number=Pattern.compile("\\d+").matcher(call.group(1));
                while(number.find()) {
                    int value=Integer.parseInt(number.group());
                    check(allowed.contains(value),file+": "+value+" px is off the spacing scale ("
                        +call.group().replaceAll("\\s+"," ")+")");
                }
            }
        }
        check(Theme.PAD_V==Theme.SPACE_SM&&Theme.PAD_H==Theme.SPACE_MD,
            "a control's padding is the spacing scale, not a second one");
        check(Theme.RADIUS==Theme.SPACE_SM,"a control's corner comes from the grid too");
    }

    // ---------------------------------------------------------------- hover

    /**
     * Hover and press change the button, but quietly.
     *
     * A control that does not react reads as dead; one that floods reads as a
     * bug. The fill moves a shade in the state's direction, the label stays
     * legible against it either way, and neither state is ever a different
     * colour family — which is what "subtle" has to mean to be checkable.
     */
    private static void hoverAndPressAreQuiet(ThemeId id) {
        var button=Theme.button("Save",()->{});
        var resting=render(button,Theme.PANEL);
        var fill=button.getBackground();
        button.getModel().setRollover(true);
        var hovered=render(button,Theme.PANEL);
        check(!same(hovered,resting),id+" a button does not answer the mouse at all");
        var hoverFill=new Color(hovered.getRGB(hovered.getWidth()/2,3));
        check(fill.getRGB()!=hoverFill.getRGB(),id+" a hovered button keeps its resting fill");
        check(ratio(Theme.TEXT,hoverFill)>=4.5,id+" a hovered button's label drops below 4.5:1");
        check(quiet(fill,hoverFill),id+" a hover is louder than a shade: "+hex(fill)+" to "+hex(hoverFill));

        button.getModel().setPressed(true);
        var pressed=render(button,Theme.PANEL);
        var pressedFill=new Color(pressed.getRGB(pressed.getWidth()/2,3));
        check(fill.getRGB()!=pressedFill.getRGB(),id+" a pressed button keeps its resting fill");
        check(ratio(Theme.TEXT,pressedFill)>=4.5,id+" a pressed button's label drops below 4.5:1");
        check(quiet(fill,pressedFill),id+" a press is louder than a shade: "+hex(fill)+" to "+hex(pressedFill));
        button.getModel().setPressed(false);
        button.getModel().setRollover(false);
    }

    /** Two renders of the same control are the same image. */
    private static boolean same(BufferedImage a,BufferedImage b) {
        if(a.getWidth()!=b.getWidth()||a.getHeight()!=b.getHeight()) return false;
        for(int y=0;y<a.getHeight();y++) for(int x=0;x<a.getWidth();x++) if(a.getRGB(x,y)!=b.getRGB(x,y)) return false;
        return true;
    }

    /** Every channel moved, but no channel moved far. */
    private static boolean quiet(Color from,Color to) {
        int red=Math.abs(from.getRed()-to.getRed()), green=Math.abs(from.getGreen()-to.getGreen()),
            blue=Math.abs(from.getBlue()-to.getBlue());
        return (red>0||green>0||blue>0)&&red<=48&&green<=48&&blue<=48;
    }

    private static String hex(Color c) { return String.format("#%06X",c.getRGB()&0xFFFFFF); }

    // ---------------------------------------------------------------- dialogs

    /**
     * A destructive dialog cannot be confirmed by reflex.
     *
     * Return fires whatever option holds focus, so the option that holds focus
     * is the entire safety property. {@code Dialogs.show} hands JOptionPane the
     * option this chooses as its initial value, and {@code confirmedBy} is true
     * for the confirming option alone — so a dialog that opens on Cancel cannot
     * be answered with a stray Return.
     */
    private static void destructiveDialogsOpenOnTheSafeOption() {
        String[] destructive={"Delete","Cancel"};
        int focused=Dialogs.defaultOption(destructive,true);
        check(focused!=0,"a destructive dialog opens with Delete under the Return key");
        check("Cancel".equals(destructive[focused]),
            "a destructive dialog must open on Cancel, not "+destructive[focused]);
        check(!Dialogs.confirmedBy(focused),"Return on a destructive dialog must not confirm it");
        check(destructive[focused].equals(Dialogs.focusedOption(destructive,true)),
            "the dialog focuses something other than the option this test checked");

        String[] keep={"Remove","Keep"};
        check("Keep".equals(keep[Dialogs.defaultOption(keep,true)]),"Keep is the safe option when there is no Cancel");

        String[] plain={"Save","Cancel"};
        check("Save".equals(plain[Dialogs.defaultOption(plain,false)]),
            "a plain confirmation still opens on its confirming action");
        check(Dialogs.confirmedBy(Dialogs.defaultOption(plain,false)),
            "a plain confirmation must still be answerable with Enter");
    }

    // ----------------------------------------------------------------- source

    private static String read(String file) {
        try { return Files.readString(Path.of(file)); }
        catch(Exception e) { throw new AssertionError("cannot read "+file+": "+e.getMessage()); }
    }
}
