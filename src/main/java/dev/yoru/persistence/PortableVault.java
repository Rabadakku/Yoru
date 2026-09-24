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
     * Format 3 added pages, folders and the pages a task links to. Format 4
     * dropped the game: a file from an older format still imports, and whatever
     * it held for the game is read past. A build that reads only up to 3 refuses
     * a format 4 file rather than dropping what it does not understand. Format 5
     * added the Anki integration and the counts it last saw. Format 6 added the
     * day a habit began. Format 7 gave a task a list of tags, "tagIds", where
     * it had room for one "tagId". Format 8 added task lists, "lists", and the
     * list each task is filed in, "listId". Format 9 added how a task repeats,
     * "repeat", and the occurrences behind it, "history". Format 10 added the
     * weeks of a repeat changed on their own, "changes" (#59).
     */
    public static final int FORMAT = 10;
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
        out.put("lists", state.lists().stream().map(PortableVault::taskList).toList());
        out.put("tasks", state.tasks().stream().map(PortableVault::task).toList());
        out.put("habits", state.habits().stream().map(PortableVault::habit).toList());
        out.put("folders", state.notes().folders().stream().map(PortableVault::folder).toList());
        out.put("pages", state.notes().pages().stream().map(PortableVault::page).toList());
        out.put("anki", anki(state.anki()));
        return Json.pretty(out);
    }

    /** The integration, its key included: an export holds everything the vault holds, and says so. */
    private static Map<String, Object> anki(Anki a) {
        var m = new LinkedHashMap<String, Object>();
        m.put("enabled", a.enabled());
        m.put("key", a.key());
        m.put("addsTime", a.addsTime());
        m.put("refreshMinutes", a.refreshMinutes());
        if (a.last() == null) m.put("last", null);
        else {
            var last = new LinkedHashMap<String, Object>();
            last.put("profile", a.last().profile());
            last.put("today", a.last().today());
            var days = new LinkedHashMap<String, Object>();
            a.last().days().forEach((day, count) -> days.put(day.toString(), count));
            last.put("days", days);
            last.put("fetchedAt", a.last().fetchedAt().toString());
            m.put("last", last);
        }
        return m;
    }

    private static Map<String, Object> settings(Settings s) {
        var m = new LinkedHashMap<String, Object>();
        m.put("theme", s.theme().name());
        m.put("dailyGoalHours", s.dailyGoalHours());
        m.put("minSessionSeconds", s.minSessionSeconds());
        m.put("weekStartsOn", s.weekStartsOn().name());
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
        m.put("changes", r.changes().stream().map(PortableVault::repeatChange).toList());
        return m;
    }

    /** One week changed on its own: moved, with its times, or skipped, with none. */
    private static Map<String, Object> repeatChange(RepeatChange c) {
        var m = new LinkedHashMap<String, Object>();
        m.put("week", c.date().toString());
        m.put("skipped", c.skipped());
        if (!c.skipped()) {
            m.put("day", c.movedTo().toString());
            m.put("startTime", c.start().toString());
            m.put("endTime", c.end().toString());
        }
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

    /** A repeat rule in words a person can read back: days by name, dates as dates. */
    private static Map<String, Object> repeat(Repeat r) {
        var m = new LinkedHashMap<String, Object>();
        m.put("unit", r.unit().name());
        m.put("every", r.every());
        m.put("days", r.days().stream().sorted().map(Enum::name).toList());
        m.put("monthDay", r.monthDay());
        m.put("weekOfMonth", r.weekOfMonth());
        m.put("start", r.start().toString());
        m.put("afterDone", r.afterDone());
        m.put("until", r.until() == null ? null : r.until().toString());
        m.put("times", r.times());
        return m;
    }

    private static Repeat readRepeat(Map<?, ?> m) {
        var days = EnumSet.noneOf(java.time.DayOfWeek.class);
        for (var day : Json.array(required(m, "days"))) days.add(java.time.DayOfWeek.valueOf(String.valueOf(day)));
        return new Repeat(enumeration(RepeatUnit.class, text(m, "unit")), int32(m, "every"), days,
            int32(m, "monthDay"), int32(m, "weekOfMonth"), java.time.LocalDate.parse(text(m, "start")),
            bool(m, "afterDone"), optionalDate(m, "until"), int32(m, "times"));
    }

    private static Map<String, Object> taskList(TaskList l) {
        var m = new LinkedHashMap<String, Object>();
        m.put("id", l.id().toString());
        m.put("name", l.name());
        m.put("colour", String.format("#%06X", l.colour()));
        m.put("order", l.order());
        return m;
    }

    private static TaskList readTaskList(Map<?, ?> m) {
        return new TaskList(id(m, "id"), text(m, "name"), colour(text(m, "colour")), int32(m, "order"));
    }

    private static Map<String, Object> task(Task t) {
        var m = new LinkedHashMap<String, Object>();
        m.put("id", t.id().toString());
        m.put("activityId", t.activityId() == null ? null : t.activityId().toString());
        m.put("tagIds", t.tagIds().stream().map(UUID::toString).toList());
        m.put("listId", t.listId() == null ? null : t.listId().toString());
        m.put("repeat", t.repeat() == null ? null : repeat(t.repeat()));
        m.put("history", t.history().stream().map(o -> {
            var h = new LinkedHashMap<String, Object>();
            h.put("due", o.due().toString());
            h.put("at", o.at().toString());
            h.put("skipped", o.skipped());
            return h;
        }).toList());
        m.put("title", t.title());
        m.put("notes", t.notes());
        m.put("due", t.due() == null ? null : t.due().toString());
        m.put("status", t.status().name());
        m.put("source", t.source());
        m.put("createdAt", t.createdAt().toString());
        m.put("order", t.order());
        m.put("plannedFor", t.plannedFor() == null ? null : t.plannedFor().toString());
        m.put("pageIds", t.pageIds().stream().map(UUID::toString).toList());
        return m;
    }

    private static Map<String, Object> folder(Folder f) {
        var m = new LinkedHashMap<String, Object>();
        m.put("id", f.id().toString());
        m.put("parentId", f.parentId() == null ? null : f.parentId().toString());
        m.put("name", f.name());
        m.put("createdAt", f.createdAt().toString());
        m.put("deletedAt", f.deletedAt() == null ? null : f.deletedAt().toString());
        return m;
    }

    private static Map<String, Object> page(Page p) {
        var m = new LinkedHashMap<String, Object>();
        m.put("id", p.id().toString());
        m.put("folderId", p.folderId() == null ? null : p.folderId().toString());
        m.put("title", p.title());
        m.put("createdAt", p.createdAt().toString());
        m.put("updatedAt", p.updatedAt().toString());
        m.put("deletedAt", p.deletedAt() == null ? null : p.deletedAt().toString());
        m.put("body", p.body());
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
        m.put("since", h.since().toString());
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
        if (format < 1 || format > FORMAT)
            throw new IllegalArgumentException("This file says it is Yoru export format " + format
                + ". This build reads formats 1 to " + FORMAT + ".");

        var settings = Json.object(required(root, "settings"));
        // Formats 1 to 3 carried the game: a collection, a campaign, rewards and
        // the save itself. The game is gone (#58), so those keys are ignored.
        return new State(
            list(root, "activities", PortableVault::readActivity),
            list(root, "sessions", PortableVault::readSession),
            list(root, "blocks", PortableVault::readBlock),
            list(root, "recurring", PortableVault::readRecurring),
            list(root, "tasks", PortableVault::readTask),
            list(root, "habits", PortableVault::readHabit),
            list(root, "tags", PortableVault::readTag),
            new Settings(
                // Not enumeration(): a workspace exported with the withdrawn
                // Waifu theme still opens, as the palette it was split from.
                ThemeId.known(text(settings, "theme")),
                int32(settings, "dailyGoalHours"),
                int32(settings, "minSessionSeconds"),
                enumeration(java.time.DayOfWeek.class, text(settings, "weekStartsOn"))),
            // Before format 3 there were no pages: an older file has none.
            new Notes(root.get("folders") == null ? List.of() : list(root, "folders", PortableVault::readFolder),
                root.get("pages") == null ? List.of() : list(root, "pages", PortableVault::readPage)),
            // Before format 5 there was no stored integration: it reads as off.
            root.get("anki") == null ? Anki.off() : readAnki(Json.object(root.get("anki"))),
            // Before format 8 there were no lists: every task is in the Inbox.
            root.get("lists") == null ? List.of() : list(root, "lists", PortableVault::readTaskList));
    }

    private static Anki readAnki(Map<?, ?> m) {
        AnkiSnapshot last = null;
        if (m.get("last") != null) {
            var kept = Json.object(m.get("last"));
            var days = new java.util.TreeMap<java.time.LocalDate, Long>();
            for (var day : Json.object(required(kept, "days")).entrySet())
                days.put(java.time.LocalDate.parse(String.valueOf(day.getKey())), integer(Map.of("n", day.getValue()), "n"));
            last = new AnkiSnapshot(text(kept, "profile"), integer(kept, "today"), days, instant(kept, "fetchedAt"));
        }
        return new Anki(bool(m, "enabled"), text(m, "key"), bool(m, "addsTime"), int32(m, "refreshMinutes"), last);
    }

    private static Folder readFolder(Map<?, ?> m) {
        return new Folder(id(m, "id"), optionalId(m, "parentId"), text(m, "name"),
            instant(m, "createdAt"), optionalInstant(m, "deletedAt"));
    }

    private static Page readPage(Map<?, ?> m) {
        return new Page(id(m, "id"), optionalId(m, "folderId"), text(m, "title"), text(m, "body"),
            instant(m, "createdAt"), instant(m, "updatedAt"), optionalInstant(m, "deletedAt"));
    }

    private static Activity readActivity(Map<?, ?> m) {
        return new Activity(id(m, "id"), text(m, "name"), int32(m, "targetMinutes"));
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
            localTime(m, "startTime"), localTime(m, "endTime"),
            // Before format 10 no week was changed on its own.
            m.get("changes") == null ? List.of() : list(m, "changes", PortableVault::readRepeatChange));
    }

    private static RepeatChange readRepeatChange(Map<?, ?> m) {
        var week = java.time.LocalDate.parse(text(m, "week"));
        if (bool(m, "skipped")) return RepeatChange.skip(week);
        return new RepeatChange(week, java.time.LocalDate.parse(text(m, "day")),
            localTime(m, "startTime"), localTime(m, "endTime"));
    }

    private static Tag readTag(Map<?, ?> m) {
        return new Tag(id(m, "id"), text(m, "name"), colour(text(m, "colour")));
    }

    private static Task readTask(Map<?, ?> m) {
        // Format 7 lists every tag (#66); an older file names one, or none.
        var tags = m.containsKey("tagIds") ? optionalIds(m, "tagIds") : Task.one(optionalId(m, "tagId"));
        return new Task(id(m, "id"), optionalId(m, "activityId"), tags,
            text(m, "title"), text(m, "notes"), optionalDate(m, "due"),
            enumeration(TaskStatus.class, text(m, "status")), text(m, "source"),
            instant(m, "createdAt"), int32(m, "order"), optionalDate(m, "plannedFor"),
            optionalIds(m, "pageIds"), optionalId(m, "listId"),
            // Before format 9 no task repeated.
            m.get("repeat") == null ? null : readRepeat(Json.object(m.get("repeat"))),
            m.get("history") == null ? List.of() : list(m, "history", h -> new Occurrence(
                java.time.LocalDate.parse(text(h, "due")), instant(h, "at"), bool(h, "skipped"))));
    }

    /** A list of identifiers that older files leave out entirely. */
    private static List<UUID> optionalIds(Map<?, ?> m, String key) {
        var value = m.get(key);
        if (value == null) return List.of();
        var out = new ArrayList<UUID>();
        for (var item : Json.array(value)) {
            try { out.add(UUID.fromString(Json.string(item))); }
            catch (IllegalArgumentException e) { throw new IllegalArgumentException("\"" + key + "\" holds something that is not an identifier."); }
        }
        return out;
    }

    private static Habit readHabit(Map<?, ?> m) {
        var checkIns = new LinkedHashSet<LocalDate>();
        for (var value : Json.array(required(m, "checkIns"))) checkIns.add(LocalDate.parse(Json.string(value)));
        var starts = new ArrayList<Instant>();
        for (var value : Json.array(required(m, "starts"))) starts.add(Instant.parse(Json.string(value)));
        // Before format 6 a habit had no beginning; the record works one out.
        return m.get("since") == null
            ? new Habit(id(m, "id"), text(m, "name"), enumeration(HabitKind.class, text(m, "kind")),
                text(m, "zone"), checkIns, starts)
            : new Habit(id(m, "id"), text(m, "name"), enumeration(HabitKind.class, text(m, "kind")),
                text(m, "zone"), checkIns, starts, optionalDate(m, "since"));
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

    private static boolean bool(Map<?, ?> m, String key) {
        var value = required(m, key);
        if (value instanceof Boolean flag) return flag;
        throw new IllegalArgumentException("Expected true or false for \"" + key + "\".");
    }

    private static long integer(Map<?, ?> m, String key) {
        var value = required(m, key);
        if (value instanceof java.math.BigDecimal number)
            try { return number.longValueExact(); }
            catch (ArithmeticException e) { throw new IllegalArgumentException("\"" + key + "\" must be a whole number."); }
        throw new IllegalArgumentException("Expected a number for \"" + key + "\".");
    }

    /**
     * A whole number that fits an int. A cast would turn one that does not into
     * a different number that passes every check — 4294967300 hours read as 4,
     * or a species read as the first one — so it is refused by name instead.
     */
    private static int int32(Map<?, ?> m, String key) {
        long value = integer(m, key);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE)
            throw new IllegalArgumentException("\"" + key + "\" is out of range.");
        return (int) value;
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
