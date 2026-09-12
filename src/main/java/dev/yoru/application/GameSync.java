package dev.yoru.application;

import dev.yoru.game.GameDelivery;
import java.io.IOException;
import java.util.List;

/**
 * Sends study rewards into the vault's own game save (#43).
 *
 * The save and the reward ledger live in the same vault, so delivery is one
 * write: the Pokémon arrive in the save and the rewards are recorded as spent
 * together, or neither happens. Rewards whose Pokémon are already in the save
 * — from an earlier sync that was interrupted, or a save imported from
 * elsewhere — are recognised by their markers and only recorded.
 *
 * Callers must not sync while the game is running: the running game holds its
 * own copy of the save and would write over the change with its next save.
 */
public final class GameSync {
    private GameSync() { }

    /** Delivers every pending reward it can, and says what happened to each. */
    public static List<GameDelivery.Outcome> deliver(Tracker tracker) throws IOException {
        var state = tracker.state();
        var pending = state.pendingRewards();
        if (pending.isEmpty()) return List.of();
        byte[] before = state.game() == null ? null : state.game().bytes();
        var plan = GameDelivery.plan(before, pending);
        var spent = plan.spent();
        if (before != null && (!spent.isEmpty() || plan.changes(before)))
            tracker.recordDelivery(before, plan.save(), spent);
        return plan.outcomes();
    }
}
