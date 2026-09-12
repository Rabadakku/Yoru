package dev.yoru.ui;

import dev.yoru.application.Tracker;
import java.awt.Component;

/** What a page needs from the window around it. */
interface Shell {

    @FunctionalInterface
    interface Work {
        void run() throws Exception;
    }

    Tracker tracker();

    /** The game running inside Yoru, and its save. */
    GameController game();

    /** The component dialogs are raised over. */
    Component owner();

    boolean reducedMotion();

    /** Shows a page by name, rebuilding it. */
    void show(String page);

    /** Runs a change, reports a failure in a dialog, then rebuilds the current page. */
    void perform(Work work);

    void error(Exception e);
}
