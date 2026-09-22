package dev.yoru.ui;

import dev.yoru.domain.Model.*;
import java.awt.*;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.Locale;
import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import static dev.yoru.ui.Theme.*;

/**
 * The Settings page: appearance, tracking and study music, the integrations,
 * the vault, and updates.
 *
 * Moved out of YoruApp (#12), as the Today page was. It builds the page, and
 * reaches the window through {@link Shell} for what belongs to the window:
 * applying a settings change, since a new palette rebuilds the window; the
 * vault's own controls and its reset; and quitting for an update.
 */
final class SettingsPage {
    private final Shell shell;
    /** Kept for the window's life, so a check or a download survives the page rebuilding around it. */
    private UpdatesCard updates;

    SettingsPage(Shell shell) { this.shell = shell; }

    JPanel view() {
        var settings=shell.tracker().state().settings();
        var p=stack();
        p.add(YoruApp.pageHeaderFor("Settings","APPEARANCE · TRACKING · VAULT · UPDATES"));
        if(updates==null)updates=new UpdatesCard(dev.yoru.update.Version.running(),dev.yoru.update.Updates.current(),
            ()->new dev.yoru.update.ReleaseFeed().latest(),new UpdatesCard.Host() {
                public void quitThen(Runnable afterVaultClosed) { shell.quitForUpdate(afterVaultClosed); }
                public java.awt.Component owner() { return shell.owner(); }
            });

        var appearance=card();
        appearance.add(sectionHeader("APPEARANCE"));
        gap(appearance,SPACE_SM);
        appearance.add(bodyLabel("Themes are stored in your vault, so they travel with the workspace."));
        gap(appearance,SPACE_LG);
        // Three across: the five themes take two short rows rather than three tall ones (#9).
        var themes=new JPanel(new GridLayout(0,3,SPACE_MD,SPACE_MD));
        themes.setName("settings.themes");
        themes.setOpaque(false);
        themes.setAlignmentX(0);
        for(var id:ThemeId.values()) themes.add(themeCard(id));
        appearance.add(themes);
        gap(appearance,SPACE_LG);
        // This computer's, not the vault's: the caption says so, since the
        // themes above travel with the workspace (#31).
        appearance.add(label("TEXT SIZE · THIS COMPUTER",TYPE_CAPTION,MUTED));
        var sizeRow=wrappingRow();
        sizeRow.setName("settings.textSize");
        for(int step:TextSize.STEPS) {
            var pick=button(step+"%",()->shell.textSize(step));
            pick.setName("textSize."+step);
            sizeRow.add(selected(pick,TextSize.current()==step));
        }
        appearance.add(sizeRow);
        gap(appearance,SPACE_MD);
        var motion=new JCheckBox("Reduce animation for this session",shell.reducedMotion());
        motion.setOpaque(false);
        motion.setForeground(TEXT);
        motion.addActionListener(e->shell.reducedMotion(motion.isSelected()));
        appearance.add(motion);

        var tracking=card();
        tracking.add(sectionHeader("TRACKING"));
        gap(tracking,SPACE_LG);
        var goal=new JSpinner(new SpinnerNumberModel(settings.dailyGoalHours(),1,16,1));
        // A preferred size as well as a maximum: a BasicSpinnerUI sizes its
        // editor from the field's own columns, which leaves the value clipped.
        goal.setPreferredSize(new Dimension(grow(90),controlHeight()));
        goal.setMaximumSize(new Dimension(grow(90),controlHeight()));
        goal.setAlignmentX(0);
        Theme.plainSpinner(goal);
        var goalCaption=label("DAILY GOAL (HOURS)",TYPE_CAPTION,MUTED);
        goalCaption.setLabelFor(goal);
        goal.getAccessibleContext().setAccessibleName("Daily goal in hours");
        tracking.add(goalCaption);
        tracking.add(goal);
        gap(tracking,SPACE_SM);
        tracking.add(bodyLabel("Heat map colours scale to this. Beating it shows the rainbow tier."));
        gap(tracking,SPACE_LG);
        var floor=new JSpinner(new SpinnerNumberModel(settings.minSessionSeconds()/60,0,60,1));
        // A preferred size as well as a maximum: a BasicSpinnerUI sizes its
        // editor from the field's own columns, which leaves the value clipped.
        floor.setPreferredSize(new Dimension(grow(90),controlHeight()));
        floor.setMaximumSize(new Dimension(grow(90),controlHeight()));
        floor.setAlignmentX(0);
        Theme.plainSpinner(floor);
        var floorCaption=label("MINIMUM SESSION (MINUTES)",TYPE_CAPTION,MUTED);
        floorCaption.setLabelFor(floor);
        floor.getAccessibleContext().setAccessibleName("Minimum session in minutes");
        tracking.add(floorCaption);
        tracking.add(floor);
        gap(tracking,SPACE_SM);
        tracking.add(bodyLabel("Clocking out under this records nothing at all."));
        gap(tracking,SPACE_LG);
        var weekStart=plainCombo(new JComboBox<>(DayOfWeek.values()));
        weekStart.setName("settings.weekStart");
        weekStart.setSelectedItem(settings.weekStartsOn());
        weekStart.setMaximumSize(new Dimension(grow(160),controlHeight()));
        weekStart.setAlignmentX(0);
        weekStart.setRenderer(new DefaultListCellRenderer(){
            @Override public Component getListCellRendererComponent(JList<?> list,Object value,int index,boolean sel,boolean focus){
                var c=(JLabel)super.getListCellRendererComponent(list,value,index,sel,focus);
                if(value instanceof DayOfWeek d)c.setText(d.getDisplayName(java.time.format.TextStyle.FULL,Locale.ENGLISH));
                c.setFont(bodyFont());c.setBackground(sel?LINE:PANEL);c.setForeground(TEXT);
                return c;
            }
        });
        var weekCaption=label("WEEK STARTS ON",TYPE_CAPTION,MUTED);
        weekCaption.setLabelFor(weekStart);
        weekStart.getAccessibleContext().setAccessibleName("Week starts on");
        tracking.add(weekCaption);
        tracking.add(weekStart);
        gap(tracking,SPACE_SM);
        tracking.add(bodyLabel("Used by the week calendar and the task calendar."));
        gap(tracking,SPACE_LG);
        tracking.add(button("Save tracking settings",()->shell.applySettings(s->new Settings(s.theme(),
            (Integer)goal.getValue(),(Integer)floor.getValue()*60,(DayOfWeek)weekStart.getSelectedItem()))));

        var audio=card();
        audio.add(sectionHeader("TRACKING · STUDY MUSIC"));
        gap(audio,SPACE_SM);
        audio.add(bodyLabel("Your own files, played while the timer runs. Yoru ships no audio."));
        gap(audio,SPACE_MD);
        var library=dev.yoru.assets.MusicLibrary.survey();
        audio.add(label(library.summary(),TYPE_BODY,library.empty()?MUTED:TEXT));
        gap(audio,SPACE_SM);
        // Said before anyone picks a folder, not after they find nothing imported.
        audio.add(bodyLabel("Format: "+dev.yoru.assets.MusicLibrary.supportedFormats()
            +". MP3 needs a decoder Yoru does not ship."));
        gap(audio,SPACE_MD);
        var playing=new JCheckBox("Play music while the timer runs",MusicPlayer.enabled());
        // No font override: every check box takes the one the foundation sets.
        playing.setOpaque(false);playing.setForeground(TEXT);
        playing.setAlignmentX(0);playing.setName("music.enabled");
        playing.addActionListener(e->MusicPlayer.enabled(playing.isSelected()));
        audio.add(playing);
        gap(audio,SPACE_MD);
        var volume=label("VOLUME · "+MusicPlayer.volume()+"%",TYPE_CAPTION,MUTED);
        audio.add(volume);
        var level=new JSlider(0,100,MusicPlayer.volume());
        level.setOpaque(false);level.setAlignmentX(0);
        // The border's padding is part of the height, so the slider keeps the
        // shared control height rather than growing by it.
        level.setMaximumSize(new Dimension(240,SPACE_XXL));
        level.setName("music.volume");
        // A slider with no ticks, no readout, no name and no focus ring is a
        // control the keyboard cannot describe or find; all four arrive together.
        Theme.plainSlider(level);
        level.setPaintTicks(true);
        level.setMajorTickSpacing(25);
        level.setMinorTickSpacing(5);
        level.getAccessibleContext().setAccessibleName("Volume");
        level.addChangeListener(e->{
            MusicPlayer.volume(level.getValue());
            volume.setText("VOLUME · "+level.getValue()+"%");
        });
        audio.add(level);
        gap(audio,SPACE_MD);
        var audioActions=row();
        audioActions.add(button("Add music…",this::importMusic));
        audioActions.add(button("Delete all music",()->{
            var warning=stack();
            warning.add(label("Delete every track from Yoru's music library?",TYPE_HEADING,TEXT));gap(warning,SPACE_MD);
            warning.add(label("Your original files are untouched; only Yoru's copies are deleted.",TYPE_BODY,MUTED));
            if(!Dialogs.confirmDestructive(shell.owner(),warning,"Delete music","Delete"))return;
            shell.perform(()->{int gone=dev.yoru.assets.MusicLibrary.clear();
                Dialogs.info(shell.owner(),plural(gone,"track")+" deleted.");shell.show("Settings");});
        }));
        audio.add(audioActions);

        var reset=card();
        reset.add(sectionHeader("VAULT · RESET DATA",GOLD));
        gap(reset,SPACE_MD);
        reset.add(label("Reset everything or choose individual sections.",TYPE_LABEL,TEXT));
        gap(reset,SPACE_SM);
        reset.add(bodyLabel("An encrypted backup is saved beside your vault before the reset."));
        gap(reset,SPACE_LG);
        reset.add(button("Choose data to reset…",shell::chooseReset));
        // In the order a reader looks for them (#9): how Yoru looks, how it
        // tracks, what it connects to, then the vault, whose controls used to
        // sit on the Data page. Updates are about the app rather than the
        // workspace, so they come last.
        var anki=AnkiSettings.card(shell,new dev.yoru.anki.AnkiConnect()::read);
        for(var section:new JComponent[]{appearance,tracking,audio,anki,shell.vaultCard(),reset,updates}) {
            p.add(section);
            gap(p,SPACE_XL);
        }
        return p;
    }

    private JPanel themeCard(ThemeId id) {
        var palette=Theme.palette(id);
        boolean chosen=shell.tracker().state().settings().theme()==id;
        var box=stack();
        box.setOpaque(true);
        box.setBackground(palette.panel());
        // One border thickness for both states: a 2 px border on the chosen tile
        // shifted every control inside it by a pixel.
        box.setBorder(new CompoundBorder(new LineBorder(chosen?CYAN:LINE),new EmptyBorder(SPACE_LG,SPACE_LG,SPACE_LG,SPACE_LG)));
        var name=label(id.name().charAt(0)+id.name().substring(1).toLowerCase(),TYPE_HEADING,palette.text());
        box.add(name);
        gap(box,SPACE_SM);
        // Two rows reserved rather than measured: a wrapping text area inside a
        // box layout has no width to wrap against until it is laid out, and the
        // second line was being clipped away.
        var blurb=new JTextArea(Theme.describe(id),2,0);
        blurb.setEditable(false); blurb.setOpaque(false); blurb.setFocusable(false);
        blurb.setLineWrap(true); blurb.setWrapStyleWord(true);
        blurb.setFont(captionFont()); blurb.setForeground(palette.muted());
        blurb.setAlignmentX(0);
        box.add(blurb);
        gap(box,SPACE_MD);
        var swatches=new JPanel(new GridLayout(1,0,SPACE_XS,0));
        swatches.setOpaque(false);
        swatches.setAlignmentX(0);
        swatches.setMaximumSize(new Dimension(Integer.MAX_VALUE,SPACE_MD));
        // Each chip is outlined, so the lightest tier is still a shape on a light
        // card: Sakura's first step is #F3E2E8 on a #FFF8FA panel, which read as
        // a missing swatch rather than the pale end of the ramp.
        for(Color c:palette.heat()) {
            var swatch=new JPanel();
            swatch.setBackground(c);
            swatch.setPreferredSize(new Dimension(SPACE_MD,SPACE_MD));
            swatch.setBorder(new LineBorder(LINE,HAIRLINE));
            swatches.add(swatch);
        }
        box.add(swatches);
        gap(box,SPACE_MD);
        // The active tile keeps a filled accent "Active" button rather than a
        // disabled one, so the chosen theme is the most prominent thing here.
        var pick=chosen
            ?accentButton("Active",()->{})
            :button("Use this",()->shell.applySettings(s->new Settings(id,s.dailyGoalHours(),s.minSessionSeconds(),s.weekStartsOn())));
        pick.setName("settings.theme."+id.name());
        if(chosen)pick.setToolTipText("This theme is already in use");
        box.add(pick);
        return box;
    }

    private void importMusic() {
        var chooser=new JFileChooser();
        chooser.setDialogTitle("Choose a folder, a .zip, or one audio file");
        chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        if(chooser.showOpenDialog(shell.owner())!=JFileChooser.APPROVE_OPTION)return;
        shell.perform(()->{
            var report=dev.yoru.assets.MusicLibrary.install(chooser.getSelectedFile().toPath());
            Dialogs.info(shell.owner(),report.summary());
            shell.show("Settings");
        });
    }

}
