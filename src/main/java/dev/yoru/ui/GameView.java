package dev.yoru.ui;

import dev.yoru.domain.Model.GameSave;
import dev.yoru.domain.Model.State;
import dev.yoru.game.Gen3Pokemon;
import dev.yoru.game.Gen3Save;
import dev.yoru.game.Progression;
import dev.yoru.game.SpeciesNames;
import dev.yoru.game.StudyEncounter;
import dev.yoru.game.StudyGift;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The vault's game save, read for the pages that show it.
 *
 * A page reads it once per render with {@link #read} and passes the result
 * down. Nothing is cached here: a static cache kept another vault's records
 * alive after a switch, and a Gen3Save is a working copy with setters, not
 * something pages should share.
 */
final class GameView {

    enum SaveKind { ABSENT, UNREADABLE, READABLE }

    /** One reading of the vault's save (#5). The bytes stay in the vault's GameSave, untouched. */
    record SaveRead(SaveKind kind, GameSave game, Gen3Save save, Gen3Save.Unreadable problem,
                    int partyCount, int boxedCount) {

        Instant savedAt() { return game == null ? null : game.updatedAt(); }

        String headline() {
            return switch (kind) {
                case ABSENT -> "No game save yet";
                case UNREADABLE -> "Your save is here, but Yoru cannot read it.";
                case READABLE -> "Saved in your vault";
            };
        }

        String detail() {
            if (kind == SaveKind.ABSENT) return "Save once inside the game to show your party here.";
            if (kind == SaveKind.READABLE) return "";
            return switch (problem) {
                case WRONG_SIZE -> "It is not the size of a Pokémon Emerald save. Export a copy before trying anything else.";
                case NEVER_SAVED -> "The game has never written to it. Open the game and save once.";
                case DAMAGED -> "Neither of its save slots is complete. Export a copy before trying anything else.";
                case INCOMPLETE_SLOT -> "The slot the game would load is incomplete. Export a copy before trying anything else.";
            };
        }

        /** Why this save cannot be changed from Yoru right now, or null when it can. */
        String editBlock(boolean gameRunning) {
            if (kind == SaveKind.ABSENT) return "There is no save in this vault yet.";
            if (kind == SaveKind.UNREADABLE) return "Yoru cannot read this save, so it will not change it.";
            if (gameRunning) return "Close the game first. While it runs it keeps its own copy of the save.";
            return save.whyNotEditable();
        }
    }

    private GameView() { }

    static SaveRead read(State state) {
        var game = state.game();
        if (game == null) return new SaveRead(SaveKind.ABSENT, null, null, null, 0, 0);
        try {
            var save = Gen3Save.read(game.bytes());
            return new SaveRead(SaveKind.READABLE, game, save, null, save.partyCount(), save.boxedCount());
        } catch (Gen3Save.UnreadableSave e) {
            return new SaveRead(SaveKind.UNREADABLE, game, null, e.reason(), 0, 0);
        }
    }

    /** The decoded save, or null when there is none or it cannot be read. */
    static Gen3Save save(State state) { return read(state).save(); }

    static boolean readable(Gen3Pokemon mon) {
        return mon != null && mon.checksumValid() && (mon.flags & 1) == 0
            && (mon.isEgg() || mon.nationalDex() > 0);
    }

    static Progression.Progress progress(State state) {
        var save = save(state);
        return save == null ? Progression.Progress.start() : new Progression.Progress(save.badges(), save.gameClear());
    }

    /** The first hatched member of the party — the one studying alongside you — or null. */
    static Gen3Pokemon lead(State state) {
        var save = save(state);
        if (save == null) return null;
        for (var mon : save.party()) if (readable(mon) && !mon.isEgg() && mon.nationalDex() > 0) return mon;
        return null;
    }

    /** Its nickname, or its species when it has none. */
    static String name(Gen3Pokemon mon) {
        if (!readable(mon)) return "Cannot read Pokémon";
        if (mon.isEgg()) return "Egg";
        String species = species(mon);
        String nickname = mon.nickname == null ? "" : mon.nickname.strip();
        return nickname.isEmpty() || nickname.equalsIgnoreCase(species) ? species : nickname;
    }

    static String species(Gen3Pokemon mon) {
        return mon.nationalDex() > 0 ? SpeciesNames.of(mon.nationalDex()) : "?";
    }

    /** "Lv 12 · Treecko ♂" — the species only when a nickname hides it. */
    static String detail(Gen3Pokemon mon) {
        if (!readable(mon)) return "This occupied slot is preserved. Export a save copy before attempting recovery.";
        if (mon.isEgg()) return "Waiting to hatch";
        var out = new StringBuilder("Lv ").append(mon.level());
        if (!name(mon).equals(species(mon))) out.append("  ·  ").append(species(mon));
        String gender = gender(mon);
        if (!gender.isEmpty()) out.append(' ').append(gender);
        if (mon.shiny()) out.append("  ·  ✦ shiny");
        return out.toString();
    }

    static String gender(Gen3Pokemon mon) {
        return switch (mon.gender()) {
            case Gen3Pokemon.MALE -> "♂";
            case Gen3Pokemon.FEMALE -> "♀";
            default -> "";
        };
    }

    /** A species' picture from the user's own artwork, or null when there is none for it. */
    static java.awt.image.BufferedImage sprite(int national, boolean shiny) {
        return national > 0 ? SpriteAssets.load((shiny ? "shiny/" : "") + national + ".png") : null;
    }

    private static final String[] NATURES = {"Hardy", "Lonely", "Brave", "Adamant", "Naughty", "Bold", "Docile",
        "Relaxed", "Impish", "Lax", "Timid", "Hasty", "Serious", "Jolly", "Naive", "Modest", "Mild", "Quiet",
        "Bashful", "Rash", "Calm", "Gentle", "Sassy", "Careful", "Quirky"};

    static String nature(Gen3Pokemon mon) { return NATURES[mon.nature()]; }

    /**
     * The next study encounter, or null when no game file is set to draw it from.
     * The encounter tables come from the session that asks, not from a static
     * cache here (#12).
     */
    static StudyEncounter.Found nextEncounter(State state, EncounterTables tables) throws IOException {
        var rom = GameFiles.rom();
        if (rom == null) return null;
        return StudyEncounter.roll(state.campaign().nextEncounter(), tables.areas(rom), progress(state));
    }

    /** Whether a reward's Pokémon will be shiny, which depends on the trainer it arrives with. */
    static boolean shiny(State state, UUID reward, int national, int level) {
        var save = save(state);
        return save != null && StudyGift.build(reward, national, level, save.trainer(), null, 0).shiny();
    }
}
