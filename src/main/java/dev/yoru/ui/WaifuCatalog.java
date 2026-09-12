package dev.yoru.ui;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Objects;

/**
 * The waifus Yoru ships: a small fixed roster of original pixel portraits.
 *
 * The art is bundled on the classpath under {@code /dev/yoru/waifu/} and shown
 * on the Today page as decoration. Settings stores an id — or the "rotate"
 * sentinel — and never the image itself, so the roster here is what turns a
 * stored id back into a picture. Nothing is read from the filesystem, so a
 * vault that travels still shows the same art on every machine.
 */
final class WaifuCatalog {
    /** The Settings value that cycles the whole roster rather than picking one. */
    static final String ROTATE = "rotate";

    /** One shipped portrait: its stored id and its name as shown in Settings. */
    record Waifu(String id, String name) { }

    private static final List<Waifu> ALL = List.of(
        new Waifu("hikari",  "Hikari"),
        new Waifu("yuki",    "Yuki"),
        new Waifu("ember",   "Ember"),
        new Waifu("noir",    "Noir"),
        new Waifu("rin",     "Rin"),
        new Waifu("scarlet", "Scarlet"),
        new Waifu("luna",    "Luna"),
        new Waifu("mai",     "Mai"));

    /** The roster in display order, for the Settings picker. */
    static List<Waifu> all() { return ALL; }

    /** The portrait for a stored id, or null if it is not one of the roster. */
    static BufferedImage load(String id) {
        try (var stream = WaifuCatalog.class.getResourceAsStream("/dev/yoru/waifu/" + id + ".png")) {
            if (stream == null) return null;
            return ImageIO.read(stream);
        } catch (java.io.IOException e) {
            return null;   // decoration: a missing picture is simply not shown
        }
    }

    /** The pictures a stored choice selects: none, one, or the whole roster. */
    static List<BufferedImage> imagesFor(String choice) {
        if (choice == null) return List.of();
        if (ROTATE.equals(choice))
            return ALL.stream().map(w -> load(w.id())).filter(Objects::nonNull).toList();
        var one = load(choice);
        return one == null ? List.of() : List.of(one);
    }
}
