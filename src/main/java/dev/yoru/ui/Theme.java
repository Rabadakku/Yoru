package dev.yoru.ui;
import dev.yoru.domain.Model.ThemeId;
import javax.swing.*;
import javax.swing.border.*;
import javax.swing.plaf.BorderUIResource;
import javax.swing.text.JTextComponent;
import java.awt.*;

/**
 * Palette, type, spacing and the shared controls.
 *
 * The colour fields are deliberately mutable statics rather than constants:
 * {@link #apply} swaps the whole palette at runtime and every call site reads
 * these names directly, so switching themes needs no edits anywhere else.
 * Components built before a swap keep the old Color objects, which is why a
 * theme change rebuilds the window rather than repainting it.
 *
 * The type and spacing scales are the same idea applied to numbers. Three title
 * sizes (25/26/28) and two subtitle sizes (11/13) shipped because every page
 * chose for itself, and a reader sees that wobble even when they cannot name
 * it. A page names a role below, never a number, so the app agrees with itself
 * by construction instead of by review.
 */
final class Theme {
    // ------------------------------------------------------------------ type
    /** The running figure on the focus timer: the one number allowed to dominate a page. */
    static final int TYPE_TIMER = 56;
    /** Page title, set bold. Big enough to find the page at a glance, small enough to leave room for it. */
    static final int TYPE_TITLE = 28;
    /** A card's headline figure. A number earns this size; prose never does. */
    static final int TYPE_FIGURE = 22;
    /** A dialog or panel heading: the first line of anything that opens over a page. */
    static final int TYPE_HEADING = 18;
    /** Running prose, and the only sans role: a paragraph is read, not scanned. */
    static final int TYPE_PROSE = 14;
    /** A button's face and the label on a control: the size of everything you can act on. */
    static final int TYPE_LABEL = 13;
    /** A card's section header, uppercase: a signpost inside a card. */
    static final int TYPE_SECTION = 12;
    /** Body copy, metadata and table text: what a card is actually saying. */
    static final int TYPE_BODY = 14;
    /** Page subtitle, caption and unit. The floor: below this the mono face stops being legible. */
    static final int TYPE_CAPTION = 12;

    // --------------------------------------------------------------- spacing
    /** The half step: an inset inside a border, where a full 8 reads as a gap. */
    static final int SPACE_XS = 4;
    /** A label and the control it names: two things that belong together. */
    static final int SPACE_SM = 8;
    /** Two blocks in a card, or two controls in a row. The default gap. */
    static final int SPACE_MD = 12;
    /** Inside a card: its own padding, and the gap under a card's header. */
    static final int SPACE_LG = 16;
    /** A page header and the first card under it; one page section and the next. */
    static final int SPACE_XL = 24;
    /** A page's outer margin. Also the height every control in a row is sized to. */
    static final int SPACE_XXL = 32;

    // -------------------------------------------------------------- controls
    /** The corner every boxed control turns. Square control chrome is the loudest "not Apple" tell. */
    static final int RADIUS = 8;
    /** The resting hairline a boxed control wears. */
    static final int HAIRLINE = 1;
    /** Focus ring thickness: the thinnest line that reads on a dark and on a light ground. */
    static final int RING = 2;
    /**
     * Padding inside a boxed control. One recipe for buttons, fields and combos,
     * on the grid: the height then falls out of the label size rather than being
     * a number a page has to guess. (A button kept its exact old height, since
     * the 7 px it used to carry outside the hairline is the grid's 8 px inside
     * it.)
     */
    static final int PAD_V = SPACE_SM, PAD_H = SPACE_MD;

    // Daylight scenery stays natural across the four application themes.
    static final Color ROUTE_SKY=new Color(0x8DD6F0), ROUTE_CLOUD=new Color(0xEEF9FF),
        ROUTE_MEADOW=new Color(0x85BB67), ROUTE_LEAF=new Color(0x3C8255),
        ROUTE_PATH=new Color(0xDBC58C), ROUTE_PATH_LIGHT=new Color(0xF0DFA7),
        ROUTE_FLOWER=new Color(0xDC92B6), ROUTE_INK=new Color(0x244A58),
        ROUTE_HAZE=new Color(0xB3E3DD), ROUTE_HILL=new Color(0x79BBA7),
        ROUTE_FOREST=new Color(0x559578), ROUTE_GRASS_LIGHT=new Color(0xA4CC76),
        ROUTE_GRASS_DARK=new Color(0x649B54), ROUTE_PATH_SHADE=new Color(0xB6A477),
        ROUTE_SHADOW=new Color(0x78884D), ROUTE_TRUNK=new Color(0x77664B),
        ROUTE_NIGHT=new Color(0x182A53);
    private static final String MONO_FAMILY = findMono();
    private static String findMono() {
        var fonts = java.util.Set.of(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
        for (String family : new String[]{"JetBrains Mono", "Iosevka", "SF Mono", "Menlo", "Cascadia Code", "DejaVu Sans Mono"})
            if (fonts.contains(family)) return family;
        return Font.MONOSPACED;
    }

    /**
     * One theme's complete colour set. Heat holds tiers 0-5; over-goal is drawn as a rainbow.
     *
     * {@code accent} is a fill and an active-state colour, and {@code accentText}
     * is the same hue made readable as small type. A light theme's accent cannot
     * be both: Sakura's pink and Linen's gold are right behind a chip and wrong
     * behind a 12 px section header, where they measured 3.5:1 and 3.9:1 on the
     * panel. Two roles, so a page that wants letters picks the one that clears
     * 4.5:1 without having to know why. On the dark themes the accent already
     * clears it, so the two roles hold the same value there.
     *
     * {@code gold} and {@code goldText} are the same pair for the second accent:
     * Linen's gold measured 3.0:1 and Sakura's 2.9:1 as a "today" date or a
     * gold card header, so a page that wants gold letters asks for the readable
     * one and keeps the bright one for fills. The pair is what the two accents
     * have in common, so the roles are named the same way.
     */
    record Palette(boolean dark, Color bg, Color panel, Color line, Color text, Color muted,
                   Color accent, Color accentText, Color gold, Color goldText, Color purple, Color danger,
                   Color disabledText, Color disabledFill, Color[] heat) { }

    /** Terminal blue with the heated-titanium heat ramp. */
    private static final Palette MIDNIGHT = new Palette(true,
        new Color(0x12161E), new Color(0x191F29), new Color(0x303A4C), new Color(0xE0E7EF),
        new Color(0x98A6BC), new Color(0x90D8DA), new Color(0x90D8DA), new Color(0xD8B074),
        new Color(0xD8B074), new Color(0xB49BDD), new Color(0xE08A8A), new Color(0xA5B6D0),
        new Color(0x232A36),
        new Color[]{new Color(0x252D3C),new Color(0xB49A60),new Color(0xC67887),
                    new Color(0x986DC1),new Color(0x637FCC),new Color(0x8AD4DC)});

    /** Warm amber CRT. The second dark theme; picked because it contrasts with MIDNIGHT. */
    private static final Palette EMBER = new Palette(true,
        new Color(0x14100C), new Color(0x1D1811), new Color(0x3D3222), new Color(0xF0E6D2),
        new Color(0xAD9670), new Color(0xE8A33D), new Color(0xE8A33D), new Color(0xD9C179),
        new Color(0xD9C179), new Color(0xC98B5A), new Color(0xE0765A), new Color(0xC0A780),
        new Color(0x271F16),
        new Color[]{new Color(0x241C14),new Color(0x6B3A1E),new Color(0x9A5423),
                    new Color(0xC2762C),new Color(0xE0A33D),new Color(0xF4D06A)});

    /** Soft cherry blossom, light ground. */
    private static final Palette SAKURA = new Palette(false,
        new Color(0xFBF0F3), new Color(0xFFF8FA), new Color(0xEFC9D6), new Color(0x462A34),
        new Color(0x8E6677), new Color(0xD1608B), new Color(0xA8436B), new Color(0xC2894A),
        new Color(0x8A6230), new Color(0x9B72B0), new Color(0xC0455E), new Color(0x60525A),
        new Color(0xFFFAFC),
        new Color[]{new Color(0xF3E2E8),new Color(0xF3C6D5),new Color(0xEDA6C0),
                    new Color(0xE581A8),new Color(0xD65F92),new Color(0xB8407A)});

    /** Warm neutral paper. Light ground, low chroma. */
    private static final Palette LINEN = new Palette(false,
        new Color(0xF6F1E8), new Color(0xFFFCF6), new Color(0xDCD0BB), new Color(0x453D34),
        new Color(0x7E7163), new Color(0x9A7B4F), new Color(0x7E6136), new Color(0xB08E52),
        new Color(0x82653F), new Color(0x8E7C99), new Color(0xB05B48), new Color(0x5E564B),
        new Color(0xFFFDF9),
        new Color[]{new Color(0xEAE2D4),new Color(0xD9C9AE),new Color(0xC7AE86),
                    new Color(0xB39566),new Color(0x9C7C4C),new Color(0x836437)});

    /** Violet nights and rose accents, paired with the full-size anime companion. */
    private static final Palette MOONLIGHT = new Palette(true,
        new Color(0x131019), new Color(0x201A29), new Color(0x41344F), new Color(0xF4EDF9),
        new Color(0xB9A9C9), new Color(0xF2A6CB), new Color(0xF2A6CB), new Color(0xEBC391),
        new Color(0xEBC391), new Color(0xBCACF5), new Color(0xF09FAF), new Color(0xC2B7CF),
        new Color(0x302637),
        new Color[]{new Color(0x302637),new Color(0x644973),new Color(0x895B97),
                    new Color(0xB47AAA),new Color(0xD695C5),new Color(0xF2BDD9)});

    static Palette palette(ThemeId id) {
        return switch (id) { case MIDNIGHT -> MIDNIGHT; case EMBER -> EMBER; case SAKURA -> SAKURA; case LINEN -> LINEN; case MOONLIGHT -> MOONLIGHT; };
    }
    /**
     * What a theme is, in one short line.
     *
     * Each is named for the ricing scheme it takes after rather than for a
     * colour, because "cool blue dark" describes half of them. These are
     * display strings only: {@link ThemeId} is persisted by name, so the
     * constants stay exactly as they are.
     */
    static String describe(ThemeId id) {
        return switch (id) {
            case MIDNIGHT -> "Cool blue dark, after Catppuccin.";
            case EMBER -> "Warm amber dark, after Gruvbox.";
            case SAKURA -> "Blossom pink light, after Rosé Pine Dawn.";
            case LINEN -> "Warm paper light, low chroma, quiet.";
            case MOONLIGHT -> "Violet nights and soft rose accents.";
        };
    }

    private static ThemeId currentId = ThemeId.MIDNIGHT;
    static ThemeId current() { return currentId; }

    // Reassigned by apply(). Read directly across the UI, which is why they are
    // not final and not a record accessor.
    static Color BG, PANEL, LINE, TEXT, MUTED, CYAN, ACCENT_TEXT, GOLD, GOLD_TEXT, PURPLE, DANGER,
        DISABLED_TEXT, DISABLED_FILL;
    static Color[] HEAT;
    static boolean DARK;
    static { load(MIDNIGHT); }

    private static void load(Palette p) {
        BG=p.bg(); PANEL=p.panel(); LINE=p.line(); TEXT=p.text(); MUTED=p.muted();
        CYAN=p.accent(); ACCENT_TEXT=p.accentText(); GOLD=p.gold(); GOLD_TEXT=p.goldText();
        PURPLE=p.purple(); DANGER=p.danger();
        DISABLED_TEXT=p.disabledText(); DISABLED_FILL=p.disabledFill();
        HEAT=p.heat(); DARK=p.dark();
    }

    /** Swaps the palette and reinstalls the defaults. Callers must rebuild the window. */
    static void apply(ThemeId id) {
        currentId=id;
        load(palette(id));
        install();
    }

    /**
     * How much larger than designed every role is drawn: this computer's
     * {@link TextSize}. Set it before {@link #apply} and before the window is
     * built; a component keeps the face it was made with.
     */
    static float textScale = 1f;

    static Font mono(int size) { return new Font(MONO_FAMILY, Font.PLAIN, scaled(size)); }
    static Font sans(int size) { return new Font(Font.SANS_SERIF, Font.PLAIN, scaled(size)); }
    private static int scaled(int size) { return Math.round(size * textScale); }

    /**
     * A size set by hand around text, grown with the text size (#31): a column,
     * a slot or a box that holds a value. Spacing between things stays as it is.
     */
    static int grow(int px) { return Math.round(px * textScale); }

    /** The height a control in a row is set to: {@link #SPACE_XXL}, grown with the text. */
    static int controlHeight() { return grow(SPACE_XXL); }

    // Role fonts. A page asks for the role; the size lives in one place so the
    // whole app moves together if a role ever has to change.
    static Font timerFont() { return mono(TYPE_TIMER); }
    static Font titleFont() { return sans(TYPE_TITLE); }
    static Font figureFont() { return mono(TYPE_FIGURE); }
    static Font headingFont() { return sans(TYPE_HEADING); }
    static Font proseFont() { return sans(TYPE_PROSE); }
    static Font labelFont() { return sans(TYPE_LABEL); }
    static Font sectionFont() { return sans(TYPE_SECTION); }
    static Font bodyFont() { return sans(TYPE_BODY); }
    static Font captionFont() { return sans(TYPE_CAPTION); }

    static void install() {
        // Aqua ignores nearly every UIManager colour key and all setBackground on
        // buttons, so the palette silently did nothing and controls rendered
        // light-on-light. The cross-platform LAF honours these and looks the same
        // on macOS, Windows and Linux.
        try { UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName()); }
        catch (Exception ignored) { }

        put(BG, "Panel.background", "OptionPane.background", "Viewport.background",
            "ScrollPane.background", "ScrollBar.track", "TabbedPane.background");
        put(PANEL, "TextField.background", "PasswordField.background", "FormattedTextField.background",
            "ComboBox.background", "List.background", "Spinner.background", "TextArea.background",
            "Table.background", "CheckBox.background");
        put(TEXT, "Label.foreground", "OptionPane.messageForeground", "TextField.foreground",
            "PasswordField.foreground", "FormattedTextField.foreground", "ComboBox.foreground",
            "List.foreground", "Spinner.foreground", "TextArea.foreground", "Table.foreground",
            "CheckBox.foreground", "Button.foreground", "TitledBorder.titleColor");
        put(CYAN, "TextField.caretForeground", "PasswordField.caretForeground",
            "FormattedTextField.caretForeground", "TextArea.caretForeground");
        put(DISABLED_TEXT, "Button.disabledText", "Label.disabledForeground",
            "ComboBox.disabledForeground", "TextField.inactiveForeground", "CheckBox.disabledText");
        // A disabled combo (the activity picker while clocked in) paints from its own
        // key, not ComboBox.background, and defaults to a near-white fill.
        put(DISABLED_FILL, "ComboBox.disabledBackground", "TextField.disabledBackground",
            "FormattedTextField.disabledBackground", "Spinner.disabledBackground");
        put(LINE, "ComboBox.selectionBackground", "List.selectionBackground",
            "Table.selectionBackground", "ScrollBar.thumb", "Button.select");
        put(TEXT, "ComboBox.selectionForeground", "List.selectionForeground", "Table.selectionForeground");
        // The one focus colour, for the cues the look-and-feel still draws itself.
        // Everything boxed draws the shared ring instead (see ControlBorder).
        put(CYAN, "Button.focus", "CheckBox.focus", "RadioButton.focus", "List.focus",
            "Table.focus", "ComboBox.focus", "Slider.focus", "Spinner.focus", "TextField.focus");

        // One face per role, so a checkbox and the label beside it are the same
        // size, and a dialog button matches the page button it mirrors.
        UIManager.put("Button.font", labelFont());
        UIManager.put("Label.font", proseFont());
        UIManager.put("TextField.font", proseFont());
        UIManager.put("FormattedTextField.font", labelFont());
        UIManager.put("ComboBox.font", proseFont());
        UIManager.put("CheckBox.font", bodyFont());
        UIManager.put("OptionPane.messageFont", proseFont());
        UIManager.put("OptionPane.buttonFont", labelFont());
        UIManager.put("ScrollBar.width", 10);
        UIManager.put("Table.gridColor", LINE);
        // The slider's ticks are still the look-and-feel's to draw (they sit in
        // its own tick area); only their colour is ours.
        put(MUTED, "Slider.tickColor");
        // Suppresses the stock Java mascot artwork on message dialogs.
        UIManager.put("OptionPane.errorIcon", null);
        UIManager.put("OptionPane.informationIcon", null);
        UIManager.put("OptionPane.warningIcon", null);
        UIManager.put("OptionPane.questionIcon", null);
        put(BG, "ScrollBar.background", "ScrollBar.trackHighlight", "ScrollBar.shadow",
            "ScrollBar.highlight", "ScrollBar.darkShadow");
        put(LINE, "ScrollBar.foreground", "ScrollBar.thumbShadow", "ScrollBar.thumbDarkShadow",
            "ScrollBar.thumbHighlight", "ComboBox.buttonBackground", "ComboBox.buttonShadow",
            "ComboBox.buttonDarkShadow", "ComboBox.buttonHighlight");
        put(LINE, "TableHeader.background", "TableHeader.cellBorder");
        put(TEXT, "TableHeader.foreground");
        UIManager.put("TableHeader.font", bodyFont());
        // Metal's combo, scrollbar and button delegates paint their own chrome and
        // ignore the colours above. The Basic delegates are flat and honour them.
        UIManager.put("ComboBoxUI", FlatComboBoxUI.class.getName());
        UIManager.put("ScrollBarUI", javax.swing.plaf.basic.BasicScrollBarUI.class.getName());
        UIManager.put("ButtonUI", javax.swing.plaf.basic.BasicButtonUI.class.getName());
        UIManager.put("SliderUI", FlatSliderUI.class.getName());
        UIManager.put("Button.gradient", null);
        put(LINE, "Button.background", "Button.select");
        // One border recipe, installed through the look-and-feel so that the
        // controls pages build for themselves — a bare JTextField, a checkbox,
        // the buttons JOptionPane creates — wear it too, without being touched.
        UIManager.put("Button.border", resource(controlBorder(LINE)));
        UIManager.put("TextField.border", resource(controlBorder(LINE)));
        UIManager.put("PasswordField.border", resource(controlBorder(LINE)));
        UIManager.put("FormattedTextField.border", resource(controlBorder(LINE)));
        UIManager.put("ComboBox.border", resource(controlBorder(LINE)));
        // A check box draws its own body, so it takes the ring without a box.
        UIManager.put("CheckBox.border", resource(new ControlBorder(null, SPACE_XS, SPACE_XS)));
        UIManager.put("CheckBoxUI", FocusCheckBoxUI.class.getName());
        installFocus();
    }

    private static void put(Color value, String... keys) { for (String key : keys) UIManager.put(key, value); }
    private static BorderUIResource resource(Border border) { return new BorderUIResource(border); }

    // ------------------------------------------------------------------ focus

    /** Marks a control as holding the keyboard, for the border that paints the ring. */
    private static final String FOCUS_KEY = "yoru.focus";
    private static boolean focusInstalled;

    /**
     * The one focus treatment, in one place.
     *
     * The focus manager announces every change of keyboard owner; the control
     * that gains it is marked, and its border paints the ring (see
     * {@link ControlBorder}). Nothing is wired per control, so a page that
     * builds its own JTextField or JCheckBox still gets the cue. Before this,
     * every button had {@code setFocusPainted(false)} and every border was a
     * plain LineBorder, so the keyboard was invisible on every control in the
     * app — the single defect that most made the interface feel unfinished.
     */
    private static void installFocus() {
        if (focusInstalled) return;
        focusInstalled = true;
        try {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().addPropertyChangeListener("focusOwner",
                e -> focusMoved((Component)e.getOldValue(), (Component)e.getNewValue()));
        } catch (Exception | Error ignored) {
            // No keyboard to follow; the ring simply never appears.
        }
    }

    /** Marks the control the keyboard just left and the one it just landed on. */
    static void focusMoved(Component from, Component to) {
        if (from instanceof JComponent left) { left.putClientProperty(FOCUS_KEY, null); left.repaint(); }
        if (to instanceof JComponent arrived) { arrived.putClientProperty(FOCUS_KEY, Boolean.TRUE); arrived.repaint(); }
    }

    /** True when the control holds the keyboard, or a test has marked it as holding it. */
    static boolean focused(Component c) {
        if (!(c instanceof JComponent j)) return false;
        return j.isFocusOwner() || Boolean.TRUE.equals(j.getClientProperty(FOCUS_KEY));
    }

    /**
     * The ring colour for a control with this fill.
     *
     * The accent, whenever the accent reads against that fill. Where it does
     * not — the accent on the accent-filled button, or on a light theme's warm
     * button — the ring becomes whichever of the text colour and the ground
     * reads best instead. A ring the same colour as the control it surrounds is
     * not a cue, so the choice is measured rather than assumed.
     */
    static Color ringFor(Component c) { return ringFor(c instanceof JComponent j ? j.getBackground() : PANEL); }

    static Color ringFor(Color fill) {
        if (contrast(CYAN, fill) >= 3.0) return CYAN;
        Color ground = DARK ? BG : PANEL;
        return contrast(TEXT, fill) >= contrast(ground, fill) ? TEXT : ground;
    }

    /** WCAG relative-contrast ratio, so a ring can be checked rather than argued about. */
    private static double contrast(Color a, Color b) {
        double la = luminance(a), lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }
    private static double luminance(Color c) {
        double[] channel = new double[3];
        int[] raw = {c.getRed(), c.getGreen(), c.getBlue()};
        for (int i = 0; i < 3; i++) {
            double v = raw[i] / 255.0;
            channel[i] = v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * channel[0] + 0.7152 * channel[1] + 0.0722 * channel[2];
    }

    /** The ring's corner, held back on a control too short to carry a full radius. */
    private static int corner(int width, int height) { return Math.min(RADIUS, Math.min(width, height) / 3); }

    /** The shared ring: one colour, one thickness, one corner, wherever it is drawn. */
    private static void paintRing(Graphics graphics, Component c, int x, int y, int w, int h) {
        var g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(ringFor(c));
        g.setStroke(new BasicStroke(RING));
        g.drawRoundRect(x + RING / 2, y + RING / 2, w - 1 - RING, h - 1 - RING, corner(w, h) * 2, corner(w, h) * 2);
        g.dispose();
    }

    /**
     * The one control border: a hairline, a band the focus ring paints into, and
     * the padding.
     *
     * The insets are the same whether or not the control has focus, so gaining
     * the keyboard can never shift the text by a pixel — the jump that made the
     * old per-call-site borders look unstable. A control that draws its own body
     * (a check box) passes no hairline and takes only the ring.
     */
    static final class ControlBorder extends AbstractBorder {
        private final Color line;
        private final int padV, padH;
        /** Rings while anything inside holds the keyboard, for inputs that read as one control. */
        private final boolean group;

        ControlBorder(Color line, int padV, int padH) { this(line, padV, padH, false); }
        ControlBorder(Color line, int padV, int padH, boolean group) {
            this.line = line; this.padV = padV; this.padH = padH; this.group = group;
        }

        @Override public Insets getBorderInsets(Component c) { return new Insets(padV, padH, padV, padH); }
        @Override public boolean isBorderOpaque() { return false; }

        @Override public void paintBorder(Component c, Graphics graphics, int x, int y, int w, int h) {
            var g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (line != null) {
                g.setColor(line);
                g.setStroke(new BasicStroke(HAIRLINE));
                g.drawRoundRect(x, y, w - 1, h - 1, corner(w, h) * 2, corner(w, h) * 2);
            }
            g.dispose();
            if (group ? focusedWithin(c) : focused(c)) paintRing(graphics, c, x, y, w, h);
        }
    }

    /** The shared border, for code that builds a control by hand. */
    static Border controlBorder(Color line) { return new ControlBorder(line, PAD_V, PAD_H); }

    /** True when the keyboard is on this component or on anything inside it. */
    static boolean focusedWithin(Component c) {
        if (focused(c)) return true;
        if (!(c instanceof Container group)) return false;
        for (var child : group.getComponents()) if (focusedWithin(child)) return true;
        return false;
    }

    /**
     * The shared border for inputs that read as one control, such as a date and
     * a time in one box (#24): the ring shows while any member holds the keyboard.
     * The focus tracker repaints only the member that gained or lost it, so each
     * member repaints the group too, once that tracker has marked it.
     */
    static Border groupBorder(JComponent group, JComponent... members) {
        var repaint = new java.awt.event.FocusAdapter() {
            @Override public void focusGained(java.awt.event.FocusEvent e) { SwingUtilities.invokeLater(group::repaint); }
            @Override public void focusLost(java.awt.event.FocusEvent e) { SwingUtilities.invokeLater(group::repaint); }
        };
        for (var member : members) member.addFocusListener(repaint);
        return new ControlBorder(LINE, PAD_V, PAD_H, true);
    }

    /**
     * A combo box's drop-down button: the shared fill with a chevron on it.
     *
     * The look-and-feel's own arrow button cannot be made to show one. Its
     * {@code paint} fills the button area, draws a four-line bevel with a drop
     * shadow from {@code ComboBox.buttonShadow} and {@code …buttonDarkShadow},
     * and then draws the triangle from that same dark-shadow colour: there is no
     * palette that gives a readable chevron without a hard two-pixel edge along
     * the bottom and right of the square. The palette hid the edge by pointing
     * both keys at the button's own fill, so every combo in the app rendered as a
     * blank square — the picker had no sign that it was one. Drawing the chevron
     * here keeps the square, and the button is still an ordinary {@link JButton}
     * in the look-and-feel's own layout, so the hit area and the open/select
     * behaviour are exactly what they were.
     */
    static final class ComboArrow extends JButton {
        ComboArrow() {
            // The look-and-feel's own arrow button is not a tab stop either.
            setRequestFocusEnabled(false);
            setName("ComboBox.arrowButton");
        }
        // The look-and-feel sizes the button to the combo's height (its own
        // squareButton layout); these only have to match what it asks for.
        @Override public Dimension getPreferredSize() { return new Dimension(SPACE_LG,SPACE_LG); }
        @Override public Dimension getMinimumSize() { return new Dimension(SPACE_LG,SPACE_LG); }

        @Override protected void paintComponent(Graphics graphics) {
            var g=(Graphics2D)graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            // A disabled combo is flat like every other disabled control, rather
            // than keeping the one bright square on the whole field.
            g.setColor(isEnabled()?LINE:DISABLED_FILL);
            g.fillRect(0,0,getWidth(),getHeight());
            // A chevron rather than a filled block: two strokes meeting at the
            // bottom, sized off the grid and stroked with the one weight the
            // focus ring defines as the thinnest line that reads on either ground.
            g.setColor(isEnabled()?MUTED:DISABLED_TEXT);
            int cx=getWidth()/2, cy=getHeight()/2, half=SPACE_XS, drop=SPACE_XS/2;
            g.setStroke(new BasicStroke(RING,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
            g.drawPolyline(new int[]{cx-half,cx,cx+half},new int[]{cy-drop,cy+drop,cy-drop},3);
            g.dispose();
        }
    }

    /**
     * The combo's own layout and behaviour with a drop-down button that shows
     * what it is. Nothing else about the look-and-feel's combo changes.
     */
    public static final class FlatComboBoxUI extends javax.swing.plaf.basic.BasicComboBoxUI {
        public static javax.swing.plaf.ComponentUI createUI(JComponent c) { return new FlatComboBoxUI(); }
        @Override protected JButton createArrowButton() { return new ComboArrow(); }
    }

    /**
     * A check box that can show the shared ring, and draws its own tick box.
     *
     * Metal turns border painting off for a check box, so its border is never
     * painted and it could not carry a ring at all. Switching it back on
     * reaches every check box a page builds, without the page knowing anything
     * about it, and changes nothing else about Metal's painting.
     *
     * The same delegate replaces the icon, because Metal's is drawn from its own
     * factory: a grey-blue bevel that ignores the palette and stayed a stock
     * square on all four themes. A check box is the one control that carries two
     * images rather than one, so the pair is installed here instead of through
     * a UIManager key.
     */
    public static final class FocusCheckBoxUI extends javax.swing.plaf.metal.MetalCheckBoxUI {
        public static javax.swing.plaf.ComponentUI createUI(JComponent c) { return new FocusCheckBoxUI(); }
        @Override public void installDefaults(AbstractButton button) {
            super.installDefaults(button);
            button.setBorderPainted(true);
            button.setIcon(new CheckBoxIcon(false));
            button.setSelectedIcon(new CheckBoxIcon(true));
        }
    }

    /**
     * The check box's own tick box: a rounded square in the palette, ticked when
     * it is on.
     *
     * The box is drawn the way every other control is — the shared hairline, the
     * grid's corner, the panel for a fill — and the tick is stroked at the ring's
     * weight, the weight the combo's chevron and the spinner's stepper already
     * use. A checked box ticks in the readable accent role, which clears 4.5:1 as
     * ink on the panel; a disabled one goes flat with the rest of the disabled
     * controls rather than keeping the one bright mark on the page.
     */
    static final class CheckBoxIcon implements Icon {
        /** The box is a grid square, so it sits on the same ladder as the label beside it. */
        static final int SIZE = SPACE_LG;
        private final boolean checked;

        CheckBoxIcon(boolean checked) { this.checked = checked; }

        @Override public int getIconWidth() { return SIZE; }
        @Override public int getIconHeight() { return SIZE; }

        @Override public void paintIcon(Component c,Graphics graphics,int x,int y) {
            boolean live = c == null || c.isEnabled();
            int round = corner(SIZE,SIZE);
            var g = (Graphics2D)graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(live ? PANEL : DISABLED_FILL);
            g.fillRoundRect(x,y,SIZE-1,SIZE-1,round*2,round*2);
            g.setColor(live ? LINE : DISABLED_TEXT);
            g.setStroke(new BasicStroke(HAIRLINE));
            g.drawRoundRect(x,y,SIZE-1,SIZE-1,round*2,round*2);
            if(checked) {
                // Two strokes meeting at the elbow, not a filled square: the same
                // mark the chevron and the stepper are drawn with, so the three
                // read as one hand.
                g.setColor(live ? ACCENT_TEXT : DISABLED_TEXT);
                g.setStroke(new BasicStroke(RING,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
                g.drawPolyline(new int[]{x+SPACE_XS,x+SIZE/2,x+SIZE-SPACE_XS-1},
                    new int[]{y+SIZE/2-1,y+SIZE-SPACE_XS-1,y+SPACE_XS+1},3);
            }
            g.dispose();
        }
    }

    /**
     * Window and dock icon, drawn in the active palette (#57). The 1024 is
     * there for a Retina Dock, which asks for 512 at twice the pixels.
     */
    static java.util.List<Image> appIcons() {
        var sizes = new java.util.ArrayList<Image>();
        for (int size : new int[]{16, 32, 64, 128, 256, 512, 1024}) sizes.add(Logo.appIcon(size, DARK));
        return sizes;
    }

    static <T> JComboBox<T> plainCombo(JComboBox<T> combo) {
        var renderer=new DefaultListCellRenderer();
        renderer.putClientProperty("html.disable",true);
        combo.setRenderer(renderer);
        combo.setBackground(PANEL);
        combo.setForeground(TEXT);
        combo.setBorder(controlBorder(LINE));
        return combo;
    }

    /** The one table row height, so every table in the app lines up. */
    static final int TABLE_ROW = 30;

    static JTable plainTable(JTable table) {
        var renderer=new javax.swing.table.DefaultTableCellRenderer();
        renderer.putClientProperty("html.disable",true);
        table.setDefaultRenderer(Object.class,renderer);
        table.setRowHeight(TABLE_ROW);
        // A table has nowhere to show the keyboard but its own edge, and its
        // cells are selected rather than focused. No resting hairline: the
        // scroll pane around it already draws one, and two frames read as a
        // mistake.
        table.setBorder(new ControlBorder(null, SPACE_XS, SPACE_XS));
        return table;
    }

    /**
     * A spinner with the app's own stepper instead of the look-and-feel's arrows.
     *
     * Every other control was moved to a flat delegate that honours the
     * palette; the spinners kept stock arrows that matched nothing else on the
     * page — a 16 px-wide pair of {@code BasicArrowButton}s stacked in the
     * trailing inset, which were also the smallest hit targets in the app. The
     * delegate below draws the field and two step buttons itself, so the arrows
     * take the palette, the border and the grid like everything beside them.
     * The editor also takes the shared border, so a spinner lines up with the
     * fields and combos in the same column.
     */
    static JSpinner plainSpinner(JSpinner spinner) {
        spinner.setUI(new FlatSpinnerUI());
        if(spinner.getEditor() instanceof JSpinner.DefaultEditor editor) {
            var field=editor.getTextField();
            field.setBackground(PANEL);
            field.setForeground(TEXT);
            field.setCaretColor(CYAN);
            // No padding of its own: the spinner's border supplies it, so the
            // editor is neither a second frame nor a second inset.
            field.setBorder(new EmptyBorder(0,0,0,0));
            // Left, so the value sits away from the arrows rather than under them.
            field.setHorizontalAlignment(SwingConstants.LEFT);
        }
        spinner.setBackground(PANEL);
        spinner.setBorder(controlBorder(LINE));
        return spinner;
    }

    /**
     * The spinner's own delegate: the field, then two step buttons beside it.
     *
     * The look-and-feel's arrows could not be kept. They paint from its own
     * control colours and ignore the palette — two stock light-grey boxes in
     * the middle of a dark window — and they are a 16 px-wide column split into
     * two 16 px-tall halves, the smallest thing in the app to aim at. Two
     * full-height buttons laid out here are drawn in the palette, and their hit
     * target is at least {@link #STEP} square whatever the spinner is given.
     */
    public static final class FlatSpinnerUI extends javax.swing.plaf.basic.BasicSpinnerUI {
        /** A step button's target, and the width it is drawn at when there is room. */
        static final int STEP = SPACE_XL;
        /** The narrowest a step button is drawn: any less and the chevron has no room. */
        static final int STEP_FLOOR = SPACE_MD;
        /** How long a held button waits before it starts running, then how fast it runs. */
        static final int HOLD_DELAY = 450, HOLD_REPEAT = 70;

        public static javax.swing.plaf.ComponentUI createUI(JComponent c) { return new FlatSpinnerUI(); }

        @Override protected LayoutManager createLayout() { return new StepLayout(); }

        // The look-and-feel's own button is not built, and with it goes its
        // ArrowButtonHandler: one repeat timer shared by every spinner in the
        // app, which would step a second time on every press. The step and its
        // repeat belong to the button (see StepButton), and the spinner's own
        // Up/Down keys keep working through the action map either way.
        @Override protected Component createNextButton() {
            var button = new StepButton(true, spinner, false);
            button.setName("Spinner.nextButton");
            return button;
        }
        @Override protected Component createPreviousButton() {
            var button = new StepButton(false, spinner, true);
            button.setName("Spinner.previousButton");
            return button;
        }
    }

    /**
     * The field, then the two step buttons side by side at the trailing edge.
     *
     * The field is what the spinner is for, so it keeps the width it was built
     * for whenever the spinner can spare that and a full-size button beside it;
     * a stepper that pushed the value out of sight to make room for itself
     * would be trading the thing being edited for the thing editing it. A
     * spinner too narrow for both — the settings spinners are 90 px, the date
     * and time fields 132 — draws the buttons down to their floor, and the
     * target they still have to answer to is grown invisibly in
     * {@link StepButton#contains}.
     */
    private static final class StepLayout implements LayoutManager {
        private Component next, previous, editor;

        public void addLayoutComponent(String name, Component c) {
            if("Next".equals(name)) next=c;
            else if("Previous".equals(name)) previous=c;
            else if("Editor".equals(name)) editor=c;
        }
        public void removeLayoutComponent(Component c) {
            if(c==next) next=null;
            else if(c==previous) previous=null;
            else if(c==editor) editor=null;
        }

        /** How wide each step button is drawn, from the room the field leaves. */
        private int stepWidth(Container parent) {
            Insets insets=parent.getInsets();
            int inner=Math.max(0,parent.getWidth()-insets.left-insets.right);
            int room=inner-(editor==null?0:editor.getPreferredSize().width);
            int each=Math.max(FlatSpinnerUI.STEP_FLOOR,Math.min(FlatSpinnerUI.STEP,room/2));
            return Math.min(each,inner/2);
        }

        public Dimension preferredLayoutSize(Container parent) {
            Insets insets=parent.getInsets();
            Dimension field=editor==null?new Dimension(0,0):editor.getPreferredSize();
            return new Dimension(field.width+2*FlatSpinnerUI.STEP+insets.left+insets.right,
                Math.max(field.height,FlatSpinnerUI.STEP)+insets.top+insets.bottom);
        }
        public Dimension minimumLayoutSize(Container parent) { return preferredLayoutSize(parent); }

        public void layoutContainer(Container parent) {
            Insets insets=parent.getInsets();
            int height=Math.max(0,parent.getHeight()-insets.top-insets.bottom);
            int each=stepWidth(parent);
            int field=Math.max(0,parent.getWidth()-insets.left-insets.right-2*each);
            bounds(editor,insets.left,insets.top,field,height);
            bounds(next,insets.left+field,insets.top,each,height);
            bounds(previous,insets.left+field+each,insets.top,each,height);
        }
        private static void bounds(Component c,int x,int y,int w,int h) {
            if(c!=null) c.setBounds(x,y,w,h);
        }
    }

    /**
     * One of the stepper's two buttons: a chevron on the field's own fill.
     *
     * Drawn here rather than by the look-and-feel, which is the whole point of
     * replacing {@code BasicArrowButton}: the fill is the panel, the chevron is
     * the text colour, and both chevrons are the same weight and are separated
     * by the one hairline the rest of the app draws its edges with.
     *
     * The drawn button can be narrow — 12 px beside a date field — so its
     * invisible bounds are grown to a {@link FlatSpinnerUI#STEP} square, away
     * from the other button: the left one into the slack the field's own text
     * never reaches, the right one into the spinner's padding. A press is never
     * a pixel hunt, and no pixel of chrome grows to say so. The button is not a
     * tab stop: the keyboard stays in the field, where the spinner's Up/Down
     * keys already step the value.
     */
    static final class StepButton extends JButton {
        private final boolean up;
        private final JSpinner spinner;
        private final boolean divider;
        private final javax.swing.Timer hold;

        StepButton(boolean up,JSpinner spinner,boolean divider) {
            this.up=up; this.spinner=spinner; this.divider=divider;
            setFocusable(false);
            setRequestFocusEnabled(false);
            setContentAreaFilled(false);
            setOpaque(false);
            setBorder(new EmptyBorder(0,0,0,0));
            hold=new javax.swing.Timer(FlatSpinnerUI.HOLD_DELAY,e->step());
            hold.setInitialDelay(FlatSpinnerUI.HOLD_DELAY);
            hold.setDelay(FlatSpinnerUI.HOLD_REPEAT);
            addMouseListener(new java.awt.event.MouseAdapter() {
                @Override public void mousePressed(java.awt.event.MouseEvent e) {
                    if(!isEnabled()||!SwingUtilities.isLeftMouseButton(e)) return;
                    step();
                    // The first step is the press, so a click answers at once;
                    // holding the button runs it after the pause above. A Swing
                    // timer fires on the event thread and its thread is a
                    // daemon, so a repeat can never outlive the window.
                    hold.start();
                }
                @Override public void mouseReleased(java.awt.event.MouseEvent e) { hold.stop(); }
                @Override public void mouseExited(java.awt.event.MouseEvent e) { hold.stop(); }
            });
        }

        /** One step: the same move the spinner's own arrow keys make. */
        private void step() {
            if(!isEnabled()) { hold.stop(); return; }
            try {
                spinner.commitEdit();
                Object value=up?spinner.getNextValue():spinner.getPreviousValue();
                if(value!=null) spinner.setValue(value);
            } catch(IllegalArgumentException|java.text.ParseException e) {
                UIManager.getLookAndFeel().provideErrorFeedback(spinner);
            }
        }

        /** A held button cannot outlive the control it belongs to. */
        @Override public void removeNotify() { hold.stop(); super.removeNotify(); }

        @Override public Dimension getPreferredSize() {
            return new Dimension(FlatSpinnerUI.STEP,FlatSpinnerUI.STEP);
        }

        /** The drawn box, grown to the smallest target a press may have. */
        Rectangle hitBounds() {
            int target=FlatSpinnerUI.STEP;
            int width=Math.max(getWidth(),target), height=Math.max(getHeight(),target);
            return new Rectangle(up?getWidth()-width:0,(getHeight()-height)/2,width,height);
        }

        @Override public boolean contains(int x,int y) { return hitBounds().contains(x,y); }

        @Override protected void paintComponent(Graphics graphics) {
            var g=(Graphics2D)graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            Color fill=!isEnabled()?DISABLED_FILL
                :getModel().isPressed()?shade(PANEL,DARK?-24:-30)
                :getModel().isRollover()?shade(PANEL,DARK?18:-12)
                :PANEL;
            g.setColor(fill);
            g.fillRect(0,0,getWidth(),getHeight());
            // The two buttons are one control split in half, so the split is
            // drawn as the hairline every other edge in the app is drawn with.
            if(divider) {
                g.setColor(LINE);
                g.setStroke(new BasicStroke(HAIRLINE));
                g.drawLine(0,0,0,getHeight()-1);
            }
            g.setColor(isEnabled()?TEXT:DISABLED_TEXT);
            g.setStroke(new BasicStroke(RING,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
            int cx=getWidth()/2, cy=getHeight()/2;
            int half=SPACE_XS+1, drop=SPACE_XS/2+1;
            g.drawPolyline(new int[]{cx-half,cx,cx+half},
                up?new int[]{cy+drop,cy-drop,cy+drop}:new int[]{cy-drop,cy+drop,cy-drop},3);
            g.dispose();
        }
    }

    /**
     * A slider that shows the keyboard like every other control.
     *
     * The look-and-feel's slider delegate paints no focus of its own — {@code
     * Slider.focus} is set by the look-and-feel and read by nothing — so there
     * was no cue at all on the volume control. The shared border is what every
     * other control wears its ring on, and a slider paints its border like
     * anything else, so taking it is half the fix; the other half is
     * {@link FlatSliderUI}, which is what draws the control itself in the
     * palette. The background is named as well because the ring is chosen
     * against the fill the control sits on.
     */
    static JSlider plainSlider(JSlider slider) {
        slider.setBackground(PANEL);
        slider.setBorder(controlBorder(LINE));
        return slider;
    }

    /**
     * The slider's own delegate: the palette's track, a thumb you can see, and
     * the filled part in the accent.
     *
     * The look-and-feel's slider was the last control drawn from its own palette
     * — a grey-blue track, a paler thumb and grey ticks on all four themes — so
     * the volume control was the brightest thing on a dark settings page and
     * matched nothing beside it. Here the track is the hairline colour and
     * everything up to the thumb is the accent, which is what the filled part of
     * an amount means everywhere else in the app. The thumb is a rounded square
     * in the text colour, so the one thing you grab is the one thing that stands
     * off the track, and the ticks take their colour from {@code Slider.tickColor}
     * (see {@link Theme#install}), which the look-and-feel reads for either
     * orientation. A disabled slider goes flat with every other disabled control:
     * the fill for its chrome, the ink for the part you would have grabbed.
     */
    public static final class FlatSliderUI extends javax.swing.plaf.basic.BasicSliderUI {
        /** The track's thickness: a rule the thumb rides, not a bar. */
        static final int TRACK = SPACE_XS;
        /** The thumb: a grid square, wide enough to grab and short enough to sit inside the control. */
        static final int THUMB_W = SPACE_MD, THUMB_H = SPACE_LG;

        public static javax.swing.plaf.ComponentUI createUI(JComponent c) { return new FlatSliderUI((JSlider)c); }
        FlatSliderUI(JSlider slider) { super(slider); }

        @Override protected Dimension getThumbSize() { return new Dimension(THUMB_W,THUMB_H); }

        // The keyboard is shown by the shared ring, which the border paints on
        // every control; a second cue around the thumb for the same fact would
        // be the look-and-feel's dashed box rather than this app's language.
        @Override public void paintFocus(Graphics g) { }

        @Override public void paintTrack(Graphics graphics) {
            boolean across=slider.getOrientation()==SwingConstants.HORIZONTAL;
            boolean live=slider.isEnabled();
            int centre=across?trackRect.y+trackRect.height/2:trackRect.x+trackRect.width/2;
            var g=(Graphics2D)graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            // Stroked with round caps rather than filled: the track is a line the
            // thumb rides, and a line reads as one at any length or value.
            g.setStroke(new BasicStroke(TRACK,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
            g.setColor(live?LINE:DISABLED_FILL);
            if(across) g.drawLine(trackRect.x,centre,trackRect.x+trackRect.width-1,centre);
            else g.drawLine(centre,trackRect.y,centre,trackRect.y+trackRect.height-1);
            g.setColor(live?CYAN:DISABLED_TEXT);
            if(across) g.drawLine(trackRect.x,centre,xPositionForValue(slider.getValue()),centre);
            else g.drawLine(centre,trackRect.y+trackRect.height-1,centre,yPositionForValue(slider.getValue()));
            g.dispose();
        }

        @Override public void paintThumb(Graphics graphics) {
            var g=(Graphics2D)graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            int round=corner(thumbRect.width,thumbRect.height);
            g.setColor(slider.isEnabled()?TEXT:DISABLED_TEXT);
            g.fillRoundRect(thumbRect.x,thumbRect.y,thumbRect.width-1,thumbRect.height-1,round*2,round*2);
            g.dispose();
        }
    }

    static <T extends JTextComponent> T styleInput(T field) {
        field.setBackground(PANEL);
        field.setForeground(TEXT);
        field.setCaretColor(CYAN);
        field.setBorder(controlBorder(LINE));
        return field;
    }

    static JLabel label(String s,int size,Color color) {
        var l=new JLabel(s);
        l.putClientProperty("html.disable",true);
        l.setAlignmentX(0);
        l.setFont(size == TYPE_TIMER || size == TYPE_FIGURE ? mono(size) : sans(size));
        l.setForeground(color);
        return l;
    }

    /** The narrowest a shortened name becomes: still enough of it to recognise. */
    static final int NAME_FLOOR = 96;

    /**
     * A name that gives way when its row runs short (#30). It shortens with "…"
     * so the figures and controls beside it keep their room, and while it is
     * shortened its tooltip says the whole of it. A name at the model's longest
     * otherwise pushed a row's figures out of sight and was clipped mid-word.
     */
    static JLabel shortenable(String text, int size, Color color) {
        var name = new JLabel(text) {
            @Override public Dimension getMinimumSize() {
                if (isMinimumSizeSet()) return super.getMinimumSize();
                var preferred = getPreferredSize();
                return new Dimension(Math.min(preferred.width, NAME_FLOOR), preferred.height);
            }
            @Override public String getToolTipText() {
                return getWidth() > 0 && getWidth() < getPreferredSize().width ? getText() : null;
            }
        };
        // Registered by hand: the tooltip comes from the override, never from setToolTipText.
        ToolTipManager.sharedInstance().registerComponent(name);
        name.putClientProperty("html.disable", true);
        name.setAlignmentX(0);
        name.setFont(sans(size));
        name.setForeground(color);
        return name;
    }

    // The four roles a page names, so no page has to choose a size or a colour
    // for the same three things again.
    /** The page's own name. */
    static JLabel title(String text) {
        var title = label(text, TYPE_TITLE, TEXT);
        // Bold, the nearest the platform faces come to the semibold the visual
        // system asks for (#9): a title set like body copy did not lead its page.
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        return title;
    }
    /** One line under the title saying what the page is for. */
    static JLabel subtitle(String text) { return label(text, TYPE_CAPTION, MUTED); }
    /** A card's signpost, uppercase at the call site. Small type, so the readable accent. */
    static JLabel sectionHeader(String text) { return label(text, TYPE_SECTION, ACCENT_TEXT); }
    /**
     * A card's signpost where the card is not the ordinary case (a caution, a promise).
     *
     * A card says which colour it wants, and a card that asks for gold is
     * saying "this one is different", never "make this unreadable". Both gold
     * roles are therefore accepted and the readable one is what gets drawn, so
     * the header that names an exception stays as legible as every other
     * header — {@link #GOLD} stays what it is: a fill for chips and sprites.
     */
    static JLabel sectionHeader(String text, Color colour) {
        return label(text, TYPE_SECTION, GOLD.equals(colour) ? GOLD_TEXT : colour);
    }
    /** Copy inside a card. */
    static JLabel bodyLabel(String text) { return wrapping(text, TYPE_BODY, MUTED); }

    /** Text in a role's size and colour that wraps to its width instead of being cut. */
    static JLabel wrapping(String text, int size, Color color) {
        var label = new WrappingLabel(text);
        label.setFont(sans(size));
        label.setForeground(color);
        label.setAlignmentX(0);
        return label;
    }

    /**
     * The one page header.
     *
     * Nine pages each chose their own title size, their own subtitle size and
     * their own gap between the two, so the same three lines drifted on every
     * page — one page had no gap at all. A page now calls this and gets the
     * same header as every other page by construction; the trailing
     * {@code SPACE_XL} is the distance from the header to the first card.
     */
    static JPanel pageHeader(String title,String subtitle) {
        return pageHeader(title,subtitle,new JComponent[0]);
    }

    /**
     * The same header, carrying the page's own actions on the title's line.
     *
     * A page that put its actions in a row of their own spent a fourth line
     * before its first card, and the row read as content rather than as the
     * page's toolbar. On the title's line they are unmistakably the page's, and
     * the first card starts a row earlier. They wrap below at larger text sizes.
     */
    static JPanel pageHeader(String title,String subtitle,JComponent... actions) {
        var p=stack();
        if(actions.length==0) {
            p.add(title(title));
            gap(p,SPACE_SM);
            p.add(subtitle(subtitle));
            gap(p,SPACE_XL);
            return p;
        }
        var words=stack();
        words.add(title(title));
        gap(words,SPACE_SM);
        words.add(subtitle(subtitle));
        var right=wrappingRow();
        for(var action:actions) right.add(action);
        p.add(splitRow(words,right));
        gap(p,SPACE_XL);
        return p;
    }

    /**
     * Two groups on one line while both fit, the second folding onto its own
     * line below when they do not.
     *
     * A toolbar built from a BorderLayout keeps its groups on one line at any
     * width, so at the window's minimum the right-hand group simply ran off the
     * page: the Tasks summary and the search box were both cut in half.
     */
    static JPanel splitRow(JComponent left,JComponent right) {
        var row=new JPanel(new HeadRow()) {
            // Its own height, and the height depends on whether the second
            // group folds, so it is asked for rather than frozen at construction.
            @Override public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE,getPreferredSize().height);
            }
        };
        row.setOpaque(false);
        row.setAlignmentX(0);
        row.add(left);
        row.add(right);
        return row;
    }

    /**
     * A page header's two blocks: the words, and the actions that belong to
     * them.
     *
     * Side by side while both fit, with the actions on the subtitle's line
     * rather than floating against the title's midline. When they do not fit
     * the actions fold onto a line of their own: at 200% text four buttons want
     * more width than the title's line has to spare, and squeezing the words
     * into what was left cut the subtitle instead of moving the buttons.
     */
    private static final class HeadRow implements LayoutManager {
        @Override public void addLayoutComponent(String name,Component c) { }
        @Override public void removeLayoutComponent(Component c) { }

        private boolean sideBySide(Container parent,int width) {
            return parent.getComponent(0).getPreferredSize().width + SPACE_LG
                + parent.getComponent(1).getPreferredSize().width <= width;
        }

        /**
         * The width to lay out at: this header's own once it has one, else the
         * room inside what holds it.
         *
         * Inside, not across: a card is its padding wider than anything it can
         * give a child, and a header measured against the card's outer width
         * decided two blocks fitted side by side in room they did not have.
         */
        private int room(Container parent) {
            int width=parent.getWidth();
            for(Container holder=parent.getParent();width==0&&holder!=null;holder=holder.getParent()) {
                var padding=holder.getInsets();
                width=Math.max(0,holder.getWidth()-padding.left-padding.right);
            }
            var insets=parent.getInsets();
            return Math.max(0,width-insets.left-insets.right);
        }

        @Override public Dimension preferredLayoutSize(Container parent) {
            var insets=parent.getInsets();
            var words=parent.getComponent(0).getPreferredSize();
            var actions=parent.getComponent(1).getPreferredSize();
            boolean side=sideBySide(parent,room(parent));
            int width=side?words.width+SPACE_LG+actions.width:Math.max(words.width,actions.width);
            int height=side?Math.max(words.height,actions.height):words.height+SPACE_MD+actions.height;
            return new Dimension(width+insets.left+insets.right,height+insets.top+insets.bottom);
        }

        @Override public Dimension minimumLayoutSize(Container parent) { return preferredLayoutSize(parent); }

        @Override public void layoutContainer(Container parent) {
            var insets=parent.getInsets();
            var words=parent.getComponent(0);
            var actions=parent.getComponent(1);
            int width=room(parent);
            var wordsSize=words.getPreferredSize();
            var actionsSize=actions.getPreferredSize();
            if(sideBySide(parent,width)) {
                words.setBounds(insets.left,insets.top,width-SPACE_LG-actionsSize.width,wordsSize.height);
                actions.setBounds(insets.left+width-actionsSize.width,
                    insets.top+Math.max(0,wordsSize.height-actionsSize.height),actionsSize.width,actionsSize.height);
            } else {
                words.setBounds(insets.left,insets.top,width,wordsSize.height);
                actions.setBounds(insets.left,insets.top+wordsSize.height+SPACE_MD,width,actionsSize.height);
            }
        }
    }

    /**
     * The one empty state: a headline, a sentence saying what fills it, and
     * optionally the one action that does.
     *
     * An empty chart or an empty table reads as a bug, and a bare muted line
     * reads as a missing one. Every surface that can be empty uses this, so
     * "nothing here yet" always looks the same and always says what to do.
     */
    static JPanel emptyState(String headline,String detail,JButton action) {
        var p=stack();
        p.add(label(headline,TYPE_HEADING,TEXT));
        gap(p,SPACE_SM);
        // The gap belongs to the action, not to the detail: without one it was
        // a band of nothing under the last line, which reads as a missing control.
        if(detail!=null&&!detail.isEmpty()) { p.add(bodyLabel(detail)); if(action!=null) gap(p,SPACE_MD); }
        if(action!=null) p.add(action);
        return p;
    }

    /**
     * One row of a list: the shared insets, with a hairline under it.
     *
     * The vertical insets here are the resting row's whole height, and anything
     * that draws over a row must total the same: the drag insertion line takes
     * its two pixels out of this row's own padding rather than adding them on
     * top, or the rows below it jump as the line comes and goes (see
     * {@code TasksPanel.insertionBorder}).
     */
    static Border listRow() {
        return new CompoundBorder(new MatteBorder(0,0,HAIRLINE,0,LINE),
            new EmptyBorder(SPACE_SM,SPACE_MD,SPACE_SM,SPACE_MD));
    }

    /**
     * The last row of a list that ends inside a card: the same room, no rule.
     *
     * A rule under the final row divides it from the card's own edge a few
     * pixels below, which reads as a list whose last entry failed to draw. The
     * pixel the rule gives up goes back into the padding, so the row stands
     * exactly as tall as the ones above it.
     */
    static Border listEnd() {
        return new EmptyBorder(SPACE_SM,SPACE_MD,SPACE_SM+HAIRLINE,SPACE_MD);
    }

    /** "1 day" / "6 days" — the one place number agreement is decided. */
    static String plural(int count,String noun) { return count+" "+noun+(count==1?"":"s"); }

    /** Fills the space above whatever follows it, so a column can sit on the floor. */
    static void glue(JPanel p) {
        var filler=Box.createVerticalGlue();
        ((JComponent)filler).setAlignmentX(0);
        p.add(filler);
    }

    /** A row tighter than {@link #row()}, for chips that belong to one control. */
    static JPanel tightRow() {
        var p=new JPanel(new FlowLayout(FlowLayout.LEFT,SPACE_SM,SPACE_XS));
        p.setAlignmentX(0);
        p.setOpaque(false);
        return p;
    }

    /**
     * A row of controls whose first one starts on the column's left edge.
     *
     * {@link #row()} and {@link #tightRow()} are FlowLayouts that leave their
     * gap before the first control as well as between them, so a row under a
     * heading began inside the text above it and the page's left margin
     * zigzagged. This row keeps the {@code SPACE_MD} gap between neighbours and
     * none before the first, so every left edge on a page is one vertical line.
     */
    static JPanel flushRow(JComponent... controls) {
        var p = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, SPACE_XS));
        p.setAlignmentX(0);
        p.setOpaque(false);
        for (var control : controls) {
            if (p.getComponentCount() > 0) p.add(Box.createHorizontalStrut(SPACE_MD));
            p.add(control);
        }
        return p;
    }

    static class VerticalPanel extends JPanel implements Scrollable {
        protected void addImpl(Component c,Object constraints,int index) {
            if(c instanceof JComponent j)j.setAlignmentX(0);
            super.addImpl(c,constraints,index);
        }
        public Dimension getMinimumSize() { return new Dimension(0,super.getMinimumSize().height); }
        public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        public int getScrollableUnitIncrement(Rectangle r,int o,int d) { return SPACE_XL; }
        public int getScrollableBlockIncrement(Rectangle r,int o,int d) { return Math.max(SPACE_XL,r.height-SPACE_XL); }
        public boolean getScrollableTracksViewportWidth() { return true; }
        public boolean getScrollableTracksViewportHeight() { return false; }
    }

    static JPanel stack() {
        var p=new VerticalPanel();
        p.setAlignmentX(0);
        p.setOpaque(false);
        p.setLayout(new BoxLayout(p,BoxLayout.Y_AXIS));
        return p;
    }

    static JPanel row() {
        var p=new JPanel(new FlowLayout(FlowLayout.LEFT,SPACE_MD,SPACE_XS));
        p.setAlignmentX(0);
        p.setOpaque(false);
        return p;
    }

    /**
     * A row of controls that may run onto further lines, and makes room for each of them.
     *
     * A row is told how wide it is only after it has been asked how tall it is:
     * a column measures every child before it gives any of them a width. A row
     * that wraps therefore reported one line's height, was laid out in the
     * narrower room it actually had, and drew its last control below the height
     * the column had set aside for it — at the window's minimum that control
     * was the focus card's Edit timer, and only its top edge showed. Asking
     * again the moment the width changes settles it in the next pass, which is
     * the pass the eye sees; the width it settles at does not change again, so
     * the asking stops there.
     */
    static JPanel wrappingRow() {
        var p=new JPanel() {
            @Override public void setBounds(int x,int y,int width,int height) {
                boolean rewidened=width!=getWidth();
                super.setBounds(x,y,width,height);
                if(!rewidened) return;
                // The column that holds it, by name: what a column measured is
                // cached against the child it measured, and Swing only clears
                // that cache on the way up from a child of a column it already
                // considers settled. This one has just been handed a width that
                // makes its old answer wrong, settled or not.
                var holder=getParent();
                if(holder!=null) holder.invalidate();
                revalidate();
            }
        };
        p.setAlignmentX(0);
        p.setOpaque(false);
        p.setLayout(new WrapFlowLayout(FlowLayout.LEFT,SPACE_MD,SPACE_XS));
        return p;
    }

    /**
     * A card's top line: what the card is on the left, the controls that act on
     * it on the right, both on one line.
     *
     * A card that stacked its controls under its title spent three lines saying
     * what one line says, and pushed the card's own content past the fold. The
     * controls wrap onto further lines at larger text sizes rather than
     * crowding the name, which shortens with a tooltip.
     */
    static JPanel cardHead(JComponent title, JComponent... actions) {
        var head = new JPanel(new BorderLayout(SPACE_MD, 0));
        head.setOpaque(false);
        head.setAlignmentX(0);
        head.add(title, BorderLayout.CENTER);
        if (actions.length > 0) {
            var right = wrappingRow();
            for (var action : actions) right.add(action);
            head.add(right, BorderLayout.EAST);
        }
        // Its own height: inside a card's vertical box an unbounded row would
        // take the slack meant for the card's content.
        head.setMaximumSize(new Dimension(Integer.MAX_VALUE, head.getPreferredSize().height));
        return head;
    }

    /**
     * One column of a chart: a rounded cap on a square foot, standing on the
     * baseline the whole row shares.
     *
     * A plain panel gave every bar four square corners and no floor, so a
     * fourteen-day row read as fourteen unrelated blocks. The cap is the
     * control radius, so a bar and a button agree about how round this app is.
     */
    static final class Bar extends JPanel {
        private final Color fill;

        Bar(Color fill) {
            this.fill = fill;
            setOpaque(false);
            setAlignmentX(0);
        }

        @Override protected void paintComponent(Graphics graphics) {
            var g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int width = getWidth(), height = getHeight();
            g.setColor(fill);
            // Rounded at the top only: the foot meets the baseline, and a bar
            // one pixel tall still draws as that pixel rather than vanishing
            // into a corner radius.
            int radius = Math.min(RADIUS, height);
            g.fillRoundRect(0, 0, width, height + radius, radius, radius);
            g.dispose();
        }
    }

    /** The corner a card turns: rounder than a control's, so a card reads as the surface controls sit on (#9). */
    static final int CARD_RADIUS = 12;

    static JPanel card() {
        var p=new CardPanel();
        p.setAlignmentX(0);
        p.setLayout(new BoxLayout(p,BoxLayout.Y_AXIS));
        p.setBackground(PANEL);
        // The fill and hairline are painted round by the panel. The border is
        // padding only, plus the hairline's pixel, so content sits exactly
        // where it sat inside the square LineBorder this replaced.
        p.setBorder(new EmptyBorder(SPACE_LG+HAIRLINE,SPACE_XL+HAIRLINE,SPACE_LG+HAIRLINE,SPACE_XL+HAIRLINE));
        return p;
    }

    /** A card's surface: its own rounded fill and hairline, since a LineBorder can only draw square corners. */
    static final class CardPanel extends VerticalPanel {
        CardPanel() { setOpaque(false); }

        @Override protected void paintComponent(Graphics graphics) {
            var g=(Graphics2D)graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(getBackground());
            g.fillRoundRect(0,0,getWidth()-1,getHeight()-1,CARD_RADIUS*2,CARD_RADIUS*2);
            g.setColor(LINE);
            g.drawRoundRect(0,0,getWidth()-1,getHeight()-1,CARD_RADIUS*2,CARD_RADIUS*2);
            g.dispose();
            super.paintComponent(graphics);
        }
    }

    /**
     * Paints its own fill so the platform look-and-feel cannot override the
     * palette. Honours setBackground/setForeground, and setContentAreaFilled(false)
     * for the flat navigation tabs.
     *
     * Hover and press are read from the button model rather than a mouse
     * listener, so keyboard activation lights the button the same way a click
     * does. Focus is drawn as the shared ring, never by the look-and-feel: the
     * border is ours, so its focus painting had nothing to draw into.
     */
    static final class FlatButton extends JButton {
        private boolean filled=true;
        FlatButton(String text) {
            super(text);
            // Stops the UI delegate painting a background; text is still drawn by super.
            super.setContentAreaFilled(false);
            setFocusPainted(false);
            setOpaque(false);
            getModel().addChangeListener(e -> repaint());
        }
        @Override public void setContentAreaFilled(boolean value) { filled=value; repaint(); }
        @Override protected void paintComponent(Graphics graphics) {
            if(filled) {
                var g=(Graphics2D)graphics.create();
                Color fill=!isEnabled() ? DISABLED_FILL
                    : getModel().isPressed() ? shade(getBackground(),DARK?-24:-30)
                    : getModel().isRollover() ? shade(getBackground(),DARK?18:-12)
                    : getBackground();
                g.setColor(fill);
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
                int round=corner(getWidth(),getHeight());
                g.fillRoundRect(0,0,getWidth()-1,getHeight()-1,round*2,round*2);
                g.dispose();
            }
            if(isEnabled()||getText()==null||getText().isEmpty()) { super.paintComponent(graphics); return; }
            // BasicButtonUI paints disabled text as getBackground().darker(),
            // ignoring Button.disabledText entirely. On a dark theme that lands
            // *below* the fill it sits on: "Current buddy" measured 1.9:1 on
            // screen while the palette claimed 4.6. Painting it here is the only
            // way the palette actually reaches the pixels.
            var g=(Graphics2D)graphics.create();
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setFont(getFont());
            g.setColor(DISABLED_TEXT);
            var metrics=g.getFontMetrics();
            g.drawString(getText(),(getWidth()-metrics.stringWidth(getText()))/2,
                (getHeight()-metrics.getHeight())/2+metrics.getAscent());
            g.dispose();
        }
        /**
         * The look-and-feel paints the border, and the navigation tabs replace
         * theirs with an underline; those carry no ring of their own, so the
         * shared one is drawn here. Without it the keyboard is invisible on the
         * one control every page is reached through.
         *
         * The test used to be {@code filled}, which asked the wrong question: a
         * filled button whose border still carries a {@link ControlBorder} draws
         * the ring there, but a filled *chip* — the selected "All"/"Calendar"
         * view, the status pills, the ↑/↓ reorder arrows — replaces its border
         * with its own hairline, so nothing drew one and the keyboard vanished
         * on exactly the controls that say which view you are in. A ring the
         * border already painted would only be drawn twice, so the border's
         * identity is what is tested, not the fill.
         */
        @Override protected void paintBorder(Graphics graphics) {
            super.paintBorder(graphics);
            if(!focused(this)||getBorder() instanceof ControlBorder) return;
            paintRing(graphics,this,0,0,getWidth(),getHeight());
        }
    }

    static Color shade(Color base,int delta) {
        return new Color(clamp(base.getRed()+delta),clamp(base.getGreen()+delta),clamp(base.getBlue()+delta));
    }
    private static int clamp(int channel) { return Math.max(0,Math.min(255,channel)); }

    static JButton button(String text,Runnable fn) {
        var b=new FlatButton(text);
        b.setFont(labelFont());
        b.setBackground(LINE);
        b.setForeground(TEXT);
        b.setBorder(controlBorder(LINE));
        b.addActionListener(e->fn.run());
        return b;
    }

    /**
     * The quiet form of a button, for the controls that belong to a row rather
     * than to the page.
     *
     * Same shape, same size, same focus ring — drawn in the card's own surface
     * with a hairline, so four rows of three controls read as a table with
     * actions rather than as a wall of twelve buttons. Hover and press still
     * shade, because the fill they shade is a real colour.
     */
    static JButton ghost(JButton b) {
        b.setBackground(PANEL);
        b.setBorder(controlBorder(LINE));
        return b;
    }

    /**
     * The one selected treatment for a segmented choice: filled in the accent,
     * with the ground colour on top.
     *
     * The app had four ways of saying "selected" — a filled accent, a coloured
     * label, an underline, and a *disabled* control — and the last is the worst,
     * because a disabled accent button loses its accent entirely and the active
     * choice ends up the least prominent thing on the page. Every segmented
     * choice marks itself here instead, and stays enabled.
     */
    static JButton selected(JButton b,boolean chosen) {
        b.setBackground(chosen?ACCENT_TEXT:LINE);
        b.setForeground(chosen?(DARK?BG:PANEL):TEXT);
        b.setBorder(controlBorder(chosen?ACCENT_TEXT:LINE));
        return b;
    }

    /** Primary action: filled in the accent colour, with the ground colour on top. */
    static JButton accentButton(String text,Runnable fn) {
        var b=button(text,fn);
        b.setBackground(ACCENT_TEXT);
        b.setForeground(DARK?BG:PANEL);
        b.setBorder(controlBorder(ACCENT_TEXT));
        return b;
    }

    static void gap(JPanel p,int h) { p.add(Box.createVerticalStrut(h)); }
}
