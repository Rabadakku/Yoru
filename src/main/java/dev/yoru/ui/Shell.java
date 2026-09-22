package dev.yoru.ui;

import dev.yoru.application.Tracker;
import java.awt.Component;
import javax.swing.JPanel;

/** What a page needs from the window around it. */
interface Shell {

    @FunctionalInterface
    interface Work {
        void run() throws Exception;
    }

    Tracker tracker();

    /** The component dialogs are raised over. */
    Component owner();

    boolean reducedMotion();

    /** Shows a page by name, rebuilding it. */
    void show(String page);

    /** Rebuilds the page on screen in place, after a change made outside {@link #perform}. */
    void refresh();

    /** Runs a change, reports a failure in a dialog, then rebuilds the current page. */
    void perform(Work work);

    void error(Exception e);

    /** The zone study time is recorded and shown in. */
    java.time.ZoneId zone();

    /** Opens the editor for a new logged session, or a planned block when {@code plan}. */
    void timeDialog(boolean plan);

    /** Opens the editor for a recorded or running session. */
    void editTime(dev.yoru.domain.Model.Session session);

    /** Asks for a new activity and adds it. */
    void addActivity();

    /** An activity's name, for a label. */
    String activityName(java.util.UUID id);

    /** Applies a settings change; a new palette rebuilds the window. */
    void applySettings(java.util.function.UnaryOperator<dev.yoru.domain.Model.Settings> change);

    /** Sets reduced motion for this session. */
    void reducedMotion(boolean on);

    /** Draws this computer's text at {@code percent} of its designed size, rebuilding the window. */
    void textSize(int percent);

    /** The open vault's own controls: switch, new, rename, password and delete. */
    JPanel vaultCard();

    /** Asks which data to reset, and resets it after a backup. */
    void chooseReset();

    /** Closes the vault for an update, then runs the installer's step. */
    void quitForUpdate(Runnable afterVaultClosed);
}
