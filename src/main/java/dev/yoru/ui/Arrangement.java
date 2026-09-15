package dev.yoru.ui;

import dev.yoru.game.Gen3Pokemon;
import dev.yoru.game.StorageEdit;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Arranging the party and the boxes as one mode (#8): pick a Pokémon up in
 * either and set it down in either.
 *
 * The party is a row of buttons and the boxes a painted grid, so a move can
 * start in one component and end in the other. What they share lives here,
 * and each redraws when it changes.
 */
final class Arrangement {
    private final BiConsumer<StorageEdit.Place, StorageEdit.Place> onMove;
    private final List<Runnable> listeners = new ArrayList<>();
    private boolean on;
    private Gen3Pokemon picked;
    private StorageEdit.Place from;

    /** @param onMove commits a move, or null while the game runs and holds the save */
    Arrangement(BiConsumer<StorageEdit.Place, StorageEdit.Place> onMove) { this.onMove = onMove; }

    boolean editable() { return onMove != null; }
    boolean on() { return on; }
    Gen3Pokemon picked() { return picked; }
    StorageEdit.Place from() { return from; }

    void listen(Runnable listener) { listeners.add(listener); }

    /** Turns the mode on or off; either way, nothing stays picked up. */
    void setOn(boolean next) {
        on = next && editable();
        picked = null;
        from = null;
        changed();
    }

    /** Puts back whatever was picked up without moving anything. */
    void cancel() {
        if (picked == null) return;
        picked = null;
        from = null;
        changed();
    }

    /**
     * One choice while arranging: pick up what is at {@code place}, or set the
     * held Pokémon down there. Choosing where it came from is a change of mind.
     *
     * @return false when nothing happened: the mode is off, or an empty spot
     *         was chosen with nothing held
     */
    boolean choose(StorageEdit.Place place, Gen3Pokemon occupant) {
        if (!on) return false;
        if (picked == null) {
            if (occupant == null) return false;
            picked = occupant;
            from = place;
            changed();
            return true;
        }
        var source = from;
        picked = null;
        from = null;
        changed();
        if (!source.equals(place)) onMove.accept(source, place);
        return true;
    }

    /** The next step, written out, for the line above the boxes. */
    String instruction() {
        if (!on) return "";
        if (picked == null) return "Choose a Pokémon, then choose its destination.";
        return "Moving " + (picked.isEgg() ? "an Egg" : GameView.name(picked)) + " from " + where(from)
            + ". Choose its destination.";
    }

    /** A place the way the instruction and a screen reader say it. */
    static String where(StorageEdit.Place place) {
        return place.party() ? "party slot " + (place.slot() + 1)
            : "box " + (place.box() + 1) + ", slot " + (place.slot() + 1);
    }

    /** The same Pokémon, across the page rebuild that gives it a new object. */
    static boolean same(Gen3Pokemon a, Gen3Pokemon b) {
        return a != null && b != null && a.personality == b.personality && a.otId == b.otId;
    }

    private void changed() {
        for (var listener : List.copyOf(listeners)) listener.run();
    }
}
