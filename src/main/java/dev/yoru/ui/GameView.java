package dev.yoru.ui;

import dev.yoru.domain.Model.GameSave;
import dev.yoru.domain.Model.State;
import dev.yoru.game.Gen3Pokemon;
import dev.yoru.game.Gen3Save;
import dev.yoru.game.Progression;
import dev.yoru.game.Rom;
import dev.yoru.game.SpeciesNames;
import dev.yoru.game.StudyEncounter;
import dev.yoru.game.StudyGift;
import dev.yoru.game.WildEncounters;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * The vault's game save, read for the pages that show it.
 *
 * Pages ask freely: a save is read once and kept until the vault holds a
 * different one. A save that cannot be read shows as no save rather than
 * breaking a page.
 */
final class GameView {

    private static GameSave source;
    private static Gen3Save read;

    private GameView() { }

    static synchronized Gen3Save save(State state) {
        var game = state.game();
        if (game == null) return null;
        if (game != source) {
            source = game;
            try { read = Gen3Save.read(game.bytes()); } catch (IllegalArgumentException e) { read = null; }
        }
        return read;
    }

    static Progression.Progress progress(State state) {
        var save = save(state);
        return save == null ? Progression.Progress.start() : new Progression.Progress(save.badges(), save.gameClear());
    }

    /** The first hatched member of the party — the one studying alongside you — or null. */
    static Gen3Pokemon lead(State state) {
        var save = save(state);
        if (save == null) return null;
        for (var mon : save.party()) if (!mon.isEgg() && mon.nationalDex() > 0) return mon;
        return null;
    }

    /** Its nickname, or its species when it has none. */
    static String name(Gen3Pokemon mon) {
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

    private static Path areasFrom;
    private static List<WildEncounters.Area> areas;

    /** The next study encounter, or null when no game file is set to draw it from. */
    static StudyEncounter.Found nextEncounter(State state) throws IOException {
        var rom = GameFiles.rom();
        if (rom == null) return null;
        return StudyEncounter.roll(state.campaign().nextEncounter(), areas(rom), progress(state));
    }

    private static synchronized List<WildEncounters.Area> areas(Path rom) throws IOException {
        if (!rom.equals(areasFrom)) {
            areas = WildEncounters.read(Rom.load(rom));
            areasFrom = rom;
        }
        return areas;
    }

    /** Whether a reward's Pokémon will be shiny, which depends on the trainer it arrives with. */
    static boolean shiny(State state, UUID reward, int national, int level) {
        var save = save(state);
        return save != null && StudyGift.build(reward, national, level, save.trainer(), null, 0).shiny();
    }
}
