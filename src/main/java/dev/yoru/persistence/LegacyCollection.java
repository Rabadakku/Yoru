package dev.yoru.persistence;

import dev.yoru.domain.Model.Campaign;
import dev.yoru.domain.Model.Reward;
import java.io.DataInputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/**
 * The collection vaults kept for themselves before schema 11, and what it
 * becomes now that the game's own save is the collection.
 *
 * Every Pokémon such a vault had caught becomes a reward waiting for the game,
 * so nothing studied for is lost: it arrives in the game the next time the
 * player presses Play. What was only presentation — the buddy, the party
 * order, box names and practice-gym records — has no place left to live and
 * is not carried over; the encrypted pre-upgrade copy of the vault still
 * holds it.
 */
final class LegacyCollection {

    /** A caught Pokémon as old vaults stored it: its species as an index into the old roster. */
    record Caught(UUID id, int species, Instant caughtAt) { }

    final List<Caught> caught;
    final long encountersUsed, rewardedSeconds;

    LegacyCollection(List<Caught> caught, long encountersUsed, long rewardedSeconds) {
        this.caught = List.copyOf(caught);
        this.encountersUsed = encountersUsed;
        this.rewardedSeconds = rewardedSeconds;
    }

    /**
     * The old roster's order: the three Hoenn starters first, so a starter
     * picker could show them as 0 to 2, then every other species in dex order.
     */
    private static final int[] NATIONAL = new int[386];
    static {
        int at = 0;
        for (int starter : new int[]{252, 255, 258}) NATIONAL[at++] = starter;
        for (int n = 1; n <= 386; n++) if (n != 252 && n != 255 && n != 258) NATIONAL[at++] = n;
    }

    /** The National Dex number an old roster index stood for. */
    static int national(int legacyIndex) {
        if (legacyIndex < 0 || legacyIndex >= NATIONAL.length) throw new IllegalArgumentException("Unknown species.");
        return NATIONAL[legacyIndex];
    }

    /** Reads the collection block of an encrypted vault, schema 2 to 10, in its own field order. */
    static LegacyCollection read(DataInputStream in, int schema, java.util.function.IntSupplier count) throws IOException {
        var caught = new ArrayList<Caught>();
        for (int n = count.getAsInt(); n > 0; n--) {
            var id = new UUID(in.readLong(), in.readLong());
            int species = in.readInt();
            var at = Instant.ofEpochSecond(in.readLong(), in.readInt());
            if (schema >= 4) { in.readBoolean(); in.readLong(); }            // shiny, evolution progress
            if (schema >= 5 && in.readBoolean()) in.readUTF();                // nickname
            if (schema >= 9) in.readInt();                                    // storage slot
            caught.add(new Caught(id, species, at));
        }
        if (in.readBoolean()) { in.readLong(); in.readLong(); }              // buddy
        long used = in.readLong(), rewarded = in.readLong();
        if (schema >= 5) for (int n = count.getAsInt(); n > 0; n--) { in.readLong(); in.readLong(); }  // party
        if (schema >= 9) for (int n = count.getAsInt(); n > 0; n--) { in.readUTF(); in.readInt(); }    // boxes
        return new LegacyCollection(caught, used, rewarded);
    }

    /**
     * The campaign this collection's counters carry on as. Seeded from the first
     * catch so that reading the same old vault twice gives the same encounters.
     */
    Campaign campaign() {
        long seed = caught.isEmpty() ? 0 : caught.getFirst().id().getMostSignificantBits();
        return new Campaign(seed, encountersUsed, rewardedSeconds);
    }

    /** The rewards already held, plus one waiting for the game for every old catch not already among them. */
    List<Reward> rewards(List<Reward> existing) {
        var out = new ArrayList<>(existing);
        var ids = new HashSet<UUID>();
        existing.forEach(r -> ids.add(r.id()));
        for (var c : caught) if (ids.add(c.id())) out.add(new Reward(c.id(), national(c.species()), 5, c.caughtAt(), null));
        return out;
    }
}
