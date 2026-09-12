package dev.yoru.persistence;

import dev.yoru.domain.Model.*;
import dev.yoru.json.Json;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * The whole vault as readable JSON, and back (#1).
 *
 * Not a migration format. It is the debugging tool, the way to protect your own
 * data while the schema churns, and the fastest acceptance check: track a week,
 * export, and confirm the numbers match what the interface claimed.
 *
 * Import builds a complete {@link State} before anything is written, so every
 * domain constructor has already refused an illegal vault by the time a caller
 * could persist one. A half-valid file cannot leave the vault half-updated.
 *
 * Unencrypted by design and by name — the caller is responsible for saying so.
 */
public final class PortableVault {
    /**
     * Export format version. Not the storage schema; this one is plain text.
     * Format 2 replaced the tracker's own collection with the campaign and the
     * game save; format 1 files still import, their catches becoming rewards.
     */
    public static final int FORMAT = 2;
    /** A whole vault is far larger than the API response Json defaults to. */
    private static final int READ_LIMIT = 64_000_000;

    private PortableVault() { }

    // ---------------------------------------------------------------- export

    public static String export(State state, Instant when) {
        var out = new LinkedHashMap<String, Object>();
        out.put("yoru", FORMAT);
        out.put("exported", when.toString());
        out.put("note", "Plain text, not encrypted. Anything in your vault is readable here.");
        out.put("settings", settings(state.settings()));
        out.put("activities", state.activities().stream().map(PortableVault::activity).toList());
        out.put("sessions", state.sessions().stream().map(PortableVault::session).toList());
        out.put("blocks", state.blocks().stream().map(PortableVault::block).toList());
        out.put("recurring", state.recurring().stream().map(PortableVault::recurring).toList());
        out.put("tags", state.tags().stream().map(PortableVault::tag).toList());
        out.put("tasks", state.tasks().stream().map(PortableVault::task).toList());
        out.put("habits", state.habits().stream().map(PortableVault::habit).toList());
        out.put("campaign", campaign(state.campaign()));
        out.put("rewards", state.rewards().stream().map(PortableVault::reward).toList());
        out.put("game", state.game() == null ? null : game(state.game()));
        return Json.pretty(out);
    }

    private static Map<String, Object> settings(Settings s) {
        var m = new LinkedHashMap<String, Object>();
        m.put("theme", s.theme().name());
        m.put("trainer", s.trainer().name());
        m.put("dailyGoalHours", s.dailyGoalHours());
        m.put("minSessionSeconds", s.minSessionSeconds());
        m.put("weekStartsOn", s.weekStartsOn().name());
        // Null when no decorative folder is set; an export taken before this
        // setting existed imports the same way, because the reader tolerates it.
        m.put("waifu", s.waifu());
        return m;
    }

    private static Map<String, Object> activity(Activity a) {
        var m = new LinkedHashMap<String, Object>();
        m.put("id", a.id().toString());
        m.put("name", a.name());
        m.put("targetMinutes", a.targetMinutes());
        return m;
    }

    private static Map<String, Object> session(Session s) {
        var m = new LinkedHashMap<String, Object>();
        m.put("id", s.id().toString());
        m.put("activityId", s.activityId().toString());
        m.put("start", s.start().toString());
        m.put("end", s.end() == null ? null : s.end().toString());
        return m;
    }

    private static Map<String, Object> block(ScheduleBlock b) {
        var m = new LinkedHashMap<String, Object>();
        m.put("id", b.id().toString());
        m.put("activityId", b.activityId().toString());
        m.put("start", b.start().toString());
        m.put("end", b.end().toString());
        return m;
    }

    private static Map<String, Object> recurring(RecurringBlock r) {
        var m = new LinkedHashMap<String, Object>();
        m.put("id", r.id().toString());
        m.put("activityId", r.activityId().toString());
        m.put("dayOfWeek", r.dayOfWeek().name());
        m.put("startTime", r.startTime().toString());
        m.put("endTime", r.endTime().toString());
        return m;
    }

    private static Map<String, Object> tag(Tag t) {
        var m = new LinkedHashMap<String, Object>();
        m.put("id", t.id().toString());
        m.put("name", t.name());
        // Hex, because a tag's colour is the one field a human reads back.
        m.put("colour", String.format("#%06X", t.colour()));
        return m;
    }

    private static Map<String, Object> task(Task t) {
        var m = new LinkedHashMap<String, Object>();
        m.put("id", t.id().toString());
        m.put("activityId", t.activityId() == null ? null : t.activityId().toString());
        m.put("tagId", t.tagId() == null ? null : t.tagId().toString());
        m.put("title", t.title());
        m.put("notes", t.notes());
        m.put("due", t.due() == null ? null : t.due().toString());
        m.put("status", t.status().name());
        m.put("source", t.source());
        m.put("createdAt", t.createdAt().toString());
        m.put("order", t.order());
        m.put("plannedFor", t.plannedFor() == null ? null : t.plannedFor().toString());
        return m;
    }

    private static Map<String, Object> habit(Habit h) {
        var m = new LinkedHashMap<String, Object>();
        m.put("id", h.id().toString());
        m.put("name", h.name());
        m.put("kind", h.kind().name());
        m.put("zone", h.zone());
        // Sorted: a set has no order, and an export that reshuffles between runs
        // is useless for diffing one against another.
        m.put("checkIns", h.checkIns().stream().sorted().map(LocalDate::toString).toList());
        m.put("starts", h.starts().stream().map(Instant::toString).toList());
        return m;
    }

    private static Map<String, Object> campaign(Campaign c) {
        var m = new LinkedHashMap<String, Object>();
        m.put("seed", c.seed());
        m.put("encountersUsed", c.encountersUsed());
        m.put("rewardedSeconds", c.rewardedSeconds());
        return m;
    }

    private static Map<String, Object> reward(Reward r) {
        var m = new LinkedHashMap<String, Object>();
        m.put("id", r.id().toString());
        m.put("nationalDex", r.nationalDex());
        m.put("level", r.level());
        m.put("earnedAt", r.earnedAt().toString());
        m.put("deliveredAt", r.deliveredAt() == null ? null : r.deliveredAt().toString());
        return m;
    }

    /** The save as base64, so the export stays one readable text file. */
    private static Map<String, Object> game(GameSave g) {
        var m = new LinkedHashMap<String, Object>();
        m.put("updatedAt", g.updatedAt().toString());
        m.put("save", Base64.getEncoder().encodeToString(g.bytes()));
        return m;
    }

    // ---------------------------------------------------------------- import

    /**
     * Reads a whole vault. Every field is validated by the same domain
     * constructors a normal write goes through, and the {@link State} is complete
     * before this returns — so a caller cannot persist a partly-read file.
     */
    public static State parse(String text) {
        var root = Json.object(Json.read(text, READ_LIMIT));
        long format = integer(root, "yoru");
        if (format != 1 && format != FORMAT)
            throw new IllegalArgumentException("This file says it is Yoru export format " + format
                + ". This build reads formats 1 and " + FORMAT + ".");

        var settings = Json.object(required(root, "settings"));
        List<Reward> rewards = root.get("rewards") == null ? List.of() : list(root, "rewards", PortableVault::readReward);
        Campaign campaign;
        GameSave game = null;
        if (format == 1) {
            // Format 1 carried the tracker's own collection; its catches become
            // rewards waiting for the game, as they do when an old vault opens.
            var legacy = readLegacyCollection(Json.object(required(root, "collection")));
            campaign = legacy.campaign();
            rewards = legacy.rewards(rewards);
        } else {
            var c = Json.object(required(root, "campaign"));
            campaign = new Campaign(integer(c, "seed"), integer(c, "encountersUsed"), integer(c, "rewardedSeconds"));
            if (root.get("game") != null) game = readGame(Json.object(root.get("game")));
        }
        return new State(
            list(root, "activities", PortableVault::readActivity),
            list(root, "sessions", PortableVault::readSession),
            list(root, "blocks", PortableVault::readBlock),
            list(root, "recurring", PortableVault::readRecurring),
            list(root, "tasks", PortableVault::readTask),
            list(root, "habits", PortableVault::readHabit),
            list(root, "tags", PortableVault::readTag),
            new Settings(
                enumeration(ThemeId.class, text(settings, "theme")),
                enumeration(TrainerId.class, text(settings, "trainer")),
                (int) integer(settings, "dailyGoalHours"),
                (int) integer(settings, "minSessionSeconds"),
                enumeration(java.time.DayOfWeek.class, text(settings, "weekStartsOn")),
                optionalText(settings, "waifu")),
            campaign, rewards, game);
    }

    private static Activity readActivity(Map<?, ?> m) {
        return new Activity(id(m, "id"), text(m, "name"), (int) integer(m, "targetMinutes"));
    }

    private static Session readSession(Map<?, ?> m) {
        return new Session(id(m, "id"), id(m, "activityId"), instant(m, "start"), optionalInstant(m, "end"));
    }

    private static ScheduleBlock readBlock(Map<?, ?> m) {
        return new ScheduleBlock(id(m, "id"), id(m, "activityId"), instant(m, "start"), instant(m, "end"));
    }

    private static RecurringBlock readRecurring(Map<?, ?> m) {
        return new RecurringBlock(id(m, "id"), id(m, "activityId"),
            enumeration(java.time.DayOfWeek.class, text(m, "dayOfWeek")),
            localTime(m, "startTime"), localTime(m, "endTime"));
    }

    private static Tag readTag(Map<?, ?> m) {
        return new Tag(id(m, "id"), text(m, "name"), colour(text(m, "colour")));
    }

    private static Task readTask(Map<?, ?> m) {
        return new Task(id(m, "id"), optionalId(m, "activityId"), optionalId(m, "tagId"),
            text(m, "title"), text(m, "notes"), optionalDate(m, "due"),
            enumeration(TaskStatus.class, text(m, "status")), text(m, "source"),
            instant(m, "createdAt"), (int) integer(m, "order"), optionalDate(m, "plannedFor"));
    }

    private static Habit readHabit(Map<?, ?> m) {
        var checkIns = new LinkedHashSet<LocalDate>();
        for (var value : Json.array(required(m, "checkIns"))) checkIns.add(LocalDate.parse(Json.string(value)));
        var starts = new ArrayList<Instant>();
        for (var value : Json.array(required(m, "starts"))) starts.add(Instant.parse(Json.string(value)));
        return new Habit(id(m, "id"), text(m, "name"), enumeration(HabitKind.class, text(m, "kind")),
            text(m, "zone"), checkIns, starts);
    }

    private static Reward readReward(Map<?, ?> m) {
        return new Reward(id(m, "id"), (int) integer(m, "nationalDex"), (int) integer(m, "level"),
            instant(m, "earnedAt"), m.get("deliveredAt") == null ? null : instant(m, "deliveredAt"));
    }

    private static GameSave readGame(Map<?, ?> m) {
        byte[] save;
        try { save = Base64.getDecoder().decode(text(m, "save")); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("\"save\" is not base64."); }
        return new GameSave(save, instant(m, "updatedAt"));
    }

    private static LegacyCollection readLegacyCollection(Map<?, ?> m) {
        var caught = new ArrayList<LegacyCollection.Caught>();
        int index = 0;
        for (var value : Json.array(required(m, "captures"))) {
            var c = Json.object(value);
            try {
                caught.add(new LegacyCollection.Caught(id(c, "id"), (int) integer(c, "species"), instant(c, "caughtAt")));
                LegacyCollection.national((int) integer(c, "species"));
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("collection.captures[" + index + "]: " + e.getMessage(), e);
            }
            index++;
        }
        return new LegacyCollection(caught, integer(m, "encountersUsed"), integer(m, "rewardedSeconds"));
    }

    // --------------------------------------------------------------- reading
    // Every accessor names the field it could not read. This is the tool people
    // reach for when a vault already looks wrong; "Invalid JSON" would not help.

    private interface Reader<T> { T read(Map<?, ?> entry); }

    private static <T> List<T> list(Map<?, ?> root, String key, Reader<T> reader) {
        var out = new ArrayList<T>();
        int index = 0;
        for (var value : Json.array(required(root, key))) {
            try { out.add(reader.read(Json.object(value))); }
            catch (RuntimeException e) {
                throw new IllegalArgumentException(key + "[" + index + "]: " + e.getMessage(), e);
            }
            index++;
        }
        return out;
    }

    private static Object required(Map<?, ?> m, String key) {
        var value = m.get(key);
        if (value == null) throw new IllegalArgumentException("Missing \"" + key + "\".");
        return value;
    }

    private static String text(Map<?, ?> m, String key) {
        var value = required(m, key);
        if (value instanceof String s) return s;
        throw new IllegalArgumentException("Expected text for \"" + key + "\".");
    }

    private static String optionalText(Map<?, ?> m, String key) {
        var value = m.get(key);
        if (value == null) return null;
        if (value instanceof String s) return s;
        throw new IllegalArgumentException("Expected text or null for \"" + key + "\".");
    }

    private static long integer(Map<?, ?> m, String key) {
        var value = required(m, key);
        if (value instanceof java.math.BigDecimal number)
            try { return number.longValueExact(); }
            catch (ArithmeticException e) { throw new IllegalArgumentException("\"" + key + "\" must be a whole number."); }
        throw new IllegalArgumentException("Expected a number for \"" + key + "\".");
    }

    private static UUID id(Map<?, ?> m, String key) {
        try { return UUID.fromString(text(m, key)); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("\"" + key + "\" is not an identifier."); }
    }

    private static UUID optionalId(Map<?, ?> m, String key) {
        var value = optionalText(m, key);
        if (value == null) return null;
        try { return UUID.fromString(value); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("\"" + key + "\" is not an identifier."); }
    }

    private static Instant instant(Map<?, ?> m, String key) {
        try { return Instant.parse(text(m, key)); }
        catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException("\"" + key + "\" is not an ISO-8601 instant.");
        }
    }

    private static Instant optionalInstant(Map<?, ?> m, String key) {
        var value = optionalText(m, key);
        if (value == null) return null;
        try { return Instant.parse(value); }
        catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException("\"" + key + "\" is not an ISO-8601 instant.");
        }
    }

    private static LocalDate optionalDate(Map<?, ?> m, String key) {
        var value = optionalText(m, key);
        if (value == null) return null;
        try { return LocalDate.parse(value); }
        catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException("\"" + key + "\" is not a YYYY-MM-DD date.");
        }
    }

    private static java.time.LocalTime localTime(Map<?, ?> m, String key) {
        try { return java.time.LocalTime.parse(text(m, key)); }
        catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException("\"" + key + "\" is not a HH:MM clock time.");
        }
    }

    private static int colour(String value) {
        var hex = value.startsWith("#") ? value.substring(1) : value;
        try { return Integer.parseInt(hex, 16); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("\"colour\" must look like #90D8DA."); }
    }

    private static <E extends Enum<E>> E enumeration(Class<E> type, String value) {
        try { return Enum.valueOf(type, value); }
        catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("\"" + value + "\" is not one of "
                + Arrays.toString(type.getEnumConstants()) + ".");
        }
    }
}
