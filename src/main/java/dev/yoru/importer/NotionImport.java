package dev.yoru.importer;

import dev.yoru.domain.Model.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

/**
 * Reads a Notion "Markdown & CSV" export into task candidates, one reviewed
 * batch at a time.
 *
 * The export is accepted exactly as Notion produced it: the zip it downloads —
 * with the database's CSV beside a folder of Markdown pages, one per row — or
 * that CSV on its own. Nothing here writes anything. Reading produces a
 * {@link Sheet}, {@link #preview} applies a mapping the user can still change,
 * and {@link #prepare} builds the records for a single
 * {@code Tracker.importTasks} write, so a mapping half-way through being fixed
 * can never leave a partial import behind.
 *
 * Deliberately not a {@code plugins.ImportProvider}: that contract's
 * {@code Observation} is a measured value with a unit, which cannot carry a
 * title, a deadline, notes and tags. The shape is the same — user-selected bytes
 * in, proposals out, nothing written until the caller commits — but the record
 * differs because the data does.
 */
public final class NotionImport {
    private NotionImport() { }

    /** Notion exports are small; past this it is not the file the user meant. */
    public static final int MAX_EXPORT_BYTES = 16 * 1024 * 1024;
    /** One reviewed batch, the same ceiling Tracker.addTasks applies. */
    public static final int MAX_ROWS = 1000;
    /** Model.Task bounds, applied rather than refused so one long page cannot lose an import. */
    private static final int MAX_TITLE = 160, MAX_NOTES = 4000, MAX_SOURCE = 160;
    private static final int MAX_TAG = 40, MAX_TAGS_PER_ROW = 12;
    private static final String DEFAULT_SOURCE = "Notion export";

    /**
     * Which column feeds which field. A null header leaves that field out of the
     * import. {@code properties} are the other columns kept as task properties
     * of the owner's (#68), each under its own name.
     */
    public record Mapping(String title, String status, String due, String tags, String priority, List<String> properties) {
        public Mapping { properties = properties == null ? List.of() : List.copyOf(new LinkedHashSet<>(properties)); }
        /** The four fields a task always had, and nothing kept as a property. */
        public Mapping(String title, String status, String due, String tags) { this(title, status, due, tags, null, List.of()); }
        public static Mapping none() { return new Mapping(null, null, null, null); }
        public Mapping withProperties(List<String> next) { return new Mapping(title, status, due, tags, priority, next); }
        public Mapping withPriority(String next) { return new Mapping(title, status, due, tags, next, properties); }
    }

    /** One data row: its cells by header, the page Markdown belonging to it, and its number from 1. */
    public record Row(int number, Map<String, String> values, String page) {
        public Row { values = Map.copyOf(values); page = Objects.requireNonNull(page); }
        /** The cell, trimmed, or empty when the row is short or the column is unmapped. */
        public String cell(String header) {
            return header == null ? "" : values.getOrDefault(header, "").strip();
        }
    }

    /** A parsed export: the CSV's headers, its rows, and the CSV it came from. */
    public record Sheet(String source, List<String> headers, List<Row> rows) {
        public Sheet { headers = List.copyOf(headers); rows = List.copyOf(rows); }
    }

    /** A row with the mapping applied: what the review screen shows before anything is written. */
    public record Candidate(int row, String title, String notes, LocalDate due,
                            TaskStatus status, List<String> tags, Priority priority, Map<String, String> cells) {
        public Candidate {
            tags = List.copyOf(tags);
            priority = priority == null ? Priority.NONE : priority;
            cells = cells == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(cells));
        }
        public Candidate(int row, String title, String notes, LocalDate due, TaskStatus status, List<String> tags) {
            this(row, title, notes, due, status, tags, Priority.NONE, Map.of());
        }
    }

    /**
     * What an accepted import writes: the tags that do not exist yet, their
     * tasks, and the task database with the properties and options the kept
     * columns need (#68).
     */
    public record Batch(List<Tag> newTags, List<Task> tasks, TaskDatabase database) { }

    /**
     * Reads the zip Notion downloaded, or the CSV inside it.
     *
     * The bytes decide, not the file name: a zip is a zip whatever it is called,
     * and a CSV taken out of an unzipped export is still a CSV.
     */
    public static Sheet read(byte[] export, String name) throws IOException {
        Objects.requireNonNull(export, "Choose the export Notion downloaded.");
        if (export.length == 0) throw new IOException("That file is empty. Choose the export Notion downloaded.");
        if (export.length > MAX_EXPORT_BYTES)
            throw new IOException("That export is larger than " + (MAX_EXPORT_BYTES / 1024 / 1024) + " MB. Export the database in smaller batches.");
        if (isZip(export)) return readZip(export);
        return parseCsv(new String(export, StandardCharsets.UTF_8), fileName(name), Map.of());
    }

    /** The usual Notion columns, guessed from the headers so the preview opens ready to accept. */
    public static Mapping autoMap(List<String> headers) {
        var claimed = new HashSet<String>();
        var title = pick(headers, TITLE_WORDS, claimed);
        var status = pick(headers, STATUS_WORDS, claimed);
        var due = pick(headers, DUE_WORDS, claimed);
        var tags = pick(headers, TAG_WORDS, claimed);
        var priority = pick(headers, PRIORITY_WORDS, claimed);
        // Every other column is kept as a property of its own name, except the
        // times and people Notion stamps on every row, which Yoru keeps itself.
        var kept = headers.stream().filter(h -> !claimed.contains(h) && !stamped(h)).toList();
        return new Mapping(title, status, due, tags, priority, kept);
    }

    /** Notion's own bookkeeping columns: when a row was made or edited, and by whom. */
    private static boolean stamped(String header) {
        var lower = header.strip().toLowerCase(Locale.ROOT);
        return lower.contains("created") || lower.contains("edited") || lower.equals("last updated");
    }

    /**
     * Applies a mapping, row by row, without writing anything.
     *
     * A row with no title is not a task and is passed over; a date the mapping
     * cannot read is refused with its row number, because a silently dropped
     * deadline is worse than one the user has to remap.
     */
    public static List<Candidate> preview(Sheet sheet, Mapping mapping) throws IOException {
        Objects.requireNonNull(sheet);
        Objects.requireNonNull(mapping);
        if (mapping.title() == null) throw new IOException("Choose which column holds the task title.");
        require(sheet, mapping.title(), "title");
        require(sheet, mapping.status(), "status");
        require(sheet, mapping.due(), "due date");
        require(sheet, mapping.tags(), "class or tag");
        require(sheet, mapping.priority(), "priority");
        for (var header : mapping.properties()) require(sheet, header, "property");
        var out = new ArrayList<Candidate>();
        for (var row : sheet.rows()) {
            // The page is matched on the whole title, not the shortened one: a
            // title past the limit ends in "…", which no page heading repeats,
            // and the heading and property lines then landed in the notes.
            String whole = printable(row.cell(mapping.title()));
            String title = bound(whole, MAX_TITLE);
            if (title.isEmpty()) continue;
            var cells = new LinkedHashMap<String, String>();
            for (var header : mapping.properties()) {
                var cell = row.cell(header);
                if (!cell.isEmpty()) cells.put(header, cell);
            }
            out.add(new Candidate(row.number(), title, body(row.page(), whole, sheet.headers()),
                due(row, mapping), status(row.cell(mapping.status())), tagNames(row.cell(mapping.tags())),
                priority(row.cell(mapping.priority())), cells));
        }
        return List.copyOf(out);
    }

    /**
     * Turns the chosen candidates into records, ready for one Tracker write.
     *
     * Duplicates — the same title, deadline and class as a task already there —
     * are dropped here and again by the tracker, so re-importing the same export
     * adds nothing twice. The class is part of it: two classes can set an
     * assignment with the same name on the same day, and both are real work. Tags are reused by name; only the ones
     * that do not exist yet are returned, and a task carries every tag its row
     * names.
     */
    public static Batch prepare(List<Candidate> chosen, State current, String source) throws IOException {
        return prepare(chosen, current, source, Map.of());
    }

    /**
     * The same, with the columns kept as properties (#68): each column is the
     * owner's property of that name if there is one — its type decides how the
     * cells are read — or a new one of the type {@link #kinds} found. Options
     * are matched by name and made when missing, with ids derived from the
     * names as a tag's are, so importing the same export twice makes nothing
     * twice. A cell its property cannot hold is left out rather than failing
     * the import.
     */
    public static Batch prepare(List<Candidate> chosen, State current, String source,
                                Map<String, PropertyType> kinds) throws IOException {
        Objects.requireNonNull(chosen, "Choose what to import.");
        Objects.requireNonNull(current);
        if (chosen.size() > MAX_ROWS) throw new IOException("Import at most " + MAX_ROWS + " tasks at once.");
        String label = source == null || source.isBlank() ? DEFAULT_SOURCE : bound(source.strip(), MAX_SOURCE);
        var tags = new LinkedHashMap<String, Tag>();
        for (var tag : current.tags()) tags.putIfAbsent(tag.name().toLowerCase(Locale.ROOT), tag);
        // A tag an earlier import made, and the user has since renamed, still
        // has the id derived from its old name: reuse it rather than minting
        // that id a second time, which the vault refuses as a duplicate.
        var byId = new HashMap<UUID, Tag>();
        for (var tag : current.tags()) byId.put(tag.id(), tag);
        var newTags = new ArrayList<Tag>();
        var accepted = new ArrayList<Task>();
        var properties = new ArrayList<>(current.database().properties());
        var byHeader = new LinkedHashMap<String, Integer>();
        for (var kept : kinds.entrySet()) {
            String name = bound(printable(kept.getKey()), 60);
            if (name.isEmpty()) continue;
            int at = -1;
            for (int i = 0; i < properties.size(); i++)
                if (properties.get(i).name().equalsIgnoreCase(name) || properties.get(i).id().equals(propertyId(name))) at = i;
            if (at < 0) {
                properties.add(new Property(propertyId(name), name, kept.getValue(), List.of(), null, false));
                at = properties.size() - 1;
            }
            byHeader.put(kept.getKey(), at);
        }
        var known = new ArrayList<Task>(current.tasks());
        int order = current.tasks().stream().mapToInt(Task::order).max().orElse(-1) + 1;
        var createdAt = Instant.now();
        for (var candidate : chosen) {
            // Every value of a multi-select column is a tag of its own (#66),
            // in the order the column lists them.
            var tagIds = new ArrayList<UUID>();
            for (String name : candidate.tags()) {
                if (tagIds.size() == Task.MAX_TAGS) break;
                String key = name.toLowerCase(Locale.ROOT);
                var tag = tags.get(key);
                if (tag == null) tag = byId.get(tagId(name));
                if (tag == null) {
                    tag = new Tag(tagId(name), name, colourFor(name));
                    newTags.add(tag);
                }
                tags.put(key, tag);
                tagIds.add(tag.id());
            }
            var values = new HashMap<UUID, Value>();
            for (var cell : candidate.cells().entrySet()) {
                var at = byHeader.get(cell.getKey());
                if (at == null) continue;
                var property = properties.get(at);
                var read = read(property, cell.getValue());
                if (read == null) continue;
                properties.set(at, read.property());
                values.put(property.id(), read.value());
            }
            var task = new Task(UUID.randomUUID(), null, tagIds, candidate.title(), candidate.notes(),
                candidate.due(), candidate.status(), label, createdAt, order++, null, List.of())
                .withDetails(new Details(candidate.priority(), null, values, null));
            if (known.stream().anyMatch(existing -> existing.sameImportEntryAs(task))) continue;
            known.add(task);
            accepted.add(task);
        }
        return new Batch(List.copyOf(newTags), List.copyOf(accepted), current.database().withProperties(properties));
    }

    /** The same id for the same property name, so re-importing cannot make a column a second property. */
    static UUID propertyId(String name) {
        return UUID.nameUUIDFromBytes(("yoru/property/" + name.toLowerCase(Locale.ROOT)).getBytes(StandardCharsets.UTF_8));
    }

    /** And for an option of one. */
    static UUID optionId(UUID property, String name) {
        return UUID.nameUUIDFromBytes(("yoru/option/" + property + "/" + name.toLowerCase(Locale.ROOT)).getBytes(StandardCharsets.UTF_8));
    }

    private record Read(Property property, Value value) { }

    /** A cell as its property holds it, with any option it names made; null when the property cannot hold it. */
    private static Read read(Property property, String cell) {
        String text = printable(cell);
        if (text.isEmpty()) return null;
        try {
            return switch (property.type()) {
                case TEXT -> new Read(property, new Value.Text(bound(cell.strip(), Value.Text.MAX)));
                case URL -> text.contains(" ") ? null : new Read(property, new Value.Text(bound(text, Value.Text.MAX)));
                case NUMBER -> {
                    var number = number(text);
                    yield number == null ? null : new Read(property, new Value.Amount(number));
                }
                case DATE -> {
                    var date = date(text);
                    yield date == null ? null : new Read(property, new Value.Day(date));
                }
                case CHECKBOX -> CHECKED.contains(text.toLowerCase(Locale.ROOT)) ? new Read(property, new Value.Tick()) : null;
                case SELECT -> {
                    var options = new ArrayList<>(property.options());
                    var id = option(property.id(), options, bound(text, 40));
                    yield id == null ? null : new Read(property.withOptions(options), new Value.Choice(id));
                }
                case MULTI_SELECT -> {
                    var options = new ArrayList<>(property.options());
                    var ids = new ArrayList<UUID>();
                    for (var name : tagNames(cell)) {
                        var id = option(property.id(), options, bound(name, 40));
                        if (id != null && !ids.contains(id)) ids.add(id);
                    }
                    yield ids.isEmpty() ? null : new Read(property.withOptions(options), new Value.Choices(ids));
                }
                case CREATED, EDITED -> null;
            };
        } catch (IllegalArgumentException unreadable) {
            return null;
        }
    }

    /** The option of this name, made when missing; null when a property already has all it may. */
    private static UUID option(UUID property, List<PropertyOption> options, String name) {
        for (var o : options) if (o.name().equalsIgnoreCase(name)) return o.id();
        if (options.size() >= Property.MAX_OPTIONS) return null;
        var made = new PropertyOption(optionId(property, name), name, colourFor(name));
        options.add(made);
        return made.id();
    }

    private static final Set<String> CHECKED = Set.of("yes", "true", "checked", "✓", "x", "1");
    private static final Set<String> UNCHECKED = Set.of("no", "false", "unchecked", "0", "");

    /** A number as Notion writes one: grouping commas, a currency sign or a percent sign around it. */
    private static java.math.BigDecimal number(String text) {
        var clean = text.replace(",", "").replace("$", "").replace("€", "").replace("£", "").replace("%", "").strip();
        if (clean.isEmpty()) return null;
        try { return new java.math.BigDecimal(clean); } catch (NumberFormatException e) { return null; }
    }

    /** A date in any form Notion writes, or null. */
    private static LocalDate date(String value) {
        String text = value;
        int arrow = text.indexOf('→');
        if (arrow < 0) arrow = text.indexOf("->");
        if (arrow >= 0) text = text.substring(0, arrow).strip();
        int time = text.indexOf('T');
        if (time > 0) text = text.substring(0, time).strip();
        text = TIME_OF_DAY.matcher(text).replaceFirst("").strip();
        for (var format : DATE_FORMATS)
            try { return LocalDate.parse(text, format); } catch (DateTimeParseException ignored) { }
        return null;
    }

    /**
     * The type each kept column's cells read as (#68), judged on every
     * non-blank cell: all numbers, all Notion checkbox words, all dates or all
     * links make that type; comma lists of a few repeated names a multi-select;
     * a few names repeated across rows a select; anything else text.
     */
    public static Map<String, PropertyType> kinds(Sheet sheet, List<String> headers) {
        var out = new LinkedHashMap<String, PropertyType>();
        for (var header : headers) {
            var cells = sheet.rows().stream().map(r -> r.cell(header)).filter(c -> !c.isBlank()).map(String::strip).toList();
            out.put(header, kind(cells));
        }
        return out;
    }

    private static PropertyType kind(List<String> cells) {
        if (cells.isEmpty()) return PropertyType.TEXT;
        if (cells.stream().allMatch(c -> CHECKED.contains(c.toLowerCase(Locale.ROOT)) || UNCHECKED.contains(c.toLowerCase(Locale.ROOT))))
            return PropertyType.CHECKBOX;
        if (cells.stream().allMatch(c -> number(c) != null)) return PropertyType.NUMBER;
        if (cells.stream().allMatch(c -> date(c) != null)) return PropertyType.DATE;
        if (cells.stream().allMatch(c -> c.matches("(?i)(https?://|mailto:)\\S+"))) return PropertyType.URL;
        // A select's or multi-select's values are short names that come back
        // row after row; a comment with a comma in it is neither.
        var rows = new HashMap<String, Integer>();
        boolean lists = false, names = true;
        for (var c : cells) {
            var parts = tagNames(c);
            if (parts.size() > 1) lists = true;
            for (var part : parts) {
                rows.merge(part.toLowerCase(Locale.ROOT), 1, Integer::sum);
                if (!shortName(part)) names = false;
            }
        }
        boolean repeats = rows.values().stream().anyMatch(n -> n > 1);
        if (names && lists && repeats && rows.size() <= 40) return PropertyType.MULTI_SELECT;
        var wholes = new HashSet<String>();
        for (var c : cells) wholes.add(c.toLowerCase(Locale.ROOT));
        if (cells.stream().allMatch(NotionImport::shortName) && wholes.size() < cells.size() && wholes.size() <= 20)
            return PropertyType.SELECT;
        return PropertyType.TEXT;
    }

    /** A name an option could have: at most forty characters and four words. */
    private static boolean shortName(String value) {
        var clean = value.strip();
        return !clean.isEmpty() && clean.length() <= 40 && clean.split("\\s+").length <= 4;
    }

    private static final List<String> PRIORITY_WORDS = List.of("priority", "prio", "importance", "urgency");

    /** Notion's priority words, or a P0 to P3 scale. Anything else is no priority. */
    private static Priority priority(String value) {
        String word = value.strip().toLowerCase(Locale.ROOT);
        if (word.isEmpty()) return Priority.NONE;
        if (Set.of("urgent", "critical", "highest", "p0", "asap").contains(word)) return Priority.URGENT;
        if (Set.of("high", "p1", "important").contains(word)) return Priority.HIGH;
        if (Set.of("medium", "normal", "p2", "mid", "moderate").contains(word)) return Priority.MEDIUM;
        if (Set.of("low", "p3", "lowest", "minor").contains(word)) return Priority.LOW;
        return Priority.NONE;
    }

    /** The same id for the same tag name, so re-importing cannot rename a tag into a second one. */
    static UUID tagId(String name) {
        return UUID.nameUUIDFromBytes(("yoru/tag/" + name.toLowerCase(Locale.ROOT)).getBytes(StandardCharsets.UTF_8));
    }

    /** The palette TagEditor walks, so an imported tag looks like one made by hand. */
    private static final int[] TAG_PALETTE = {0x90D8DA,0xE8B24C,0xD9736A,0xA98BD4,0x6E8FD6,0x6FBF8B,0xD98CB4,0xC9C273};

    /** A stable colour for a tag name, so the same class always arrives the same colour. */
    public static int colourFor(String name) {
        return TAG_PALETTE[Math.floorMod(name.toLowerCase(Locale.ROOT).hashCode(), TAG_PALETTE.length)];
    }

    // ---- reading -------------------------------------------------------------

    private static Sheet readZip(byte[] zip) throws IOException {
        var files = unzip(zip);
        String csv = null;
        for (var name : files.keySet())
            if (name.toLowerCase(Locale.ROOT).endsWith(".csv") && (csv == null || depth(name) < depth(csv))) csv = name;
        if (csv == null)
            throw new IOException("That export has no CSV in it. In Notion, export the database as Markdown & CSV.");
        var pages = new LinkedHashMap<String, String>();
        // Pages are matched to rows by title. Two pages with the same title —
        // "Reading" for two classes — cannot be told apart that way, so neither
        // row gets notes rather than both getting the first page's.
        var ambiguous = new HashSet<String>();
        for (var entry : files.entrySet())
            if (entry.getKey().toLowerCase(Locale.ROOT).endsWith(".md")) {
                String key = pageKey(entry.getKey());
                if (pages.putIfAbsent(key, new String(entry.getValue(), StandardCharsets.UTF_8)) != null) ambiguous.add(key);
            }
        for (String key : ambiguous) pages.remove(key);
        return parseCsv(new String(files.get(csv), StandardCharsets.UTF_8), fileName(csv), pages);
    }

    private static Sheet parseCsv(String text, String source, Map<String, String> pages) throws IOException {
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') text = text.substring(1); // Notion writes a byte-order mark
        var records = records(text);
        if (records.isEmpty()) throw new IOException("That CSV is empty. Choose the export Notion downloaded.");
        var headers = new ArrayList<String>();
        for (String header : records.getFirst()) {
            String name = header.strip();
            if (name.isEmpty()) throw new IOException("That CSV has a column with no name. Re-export the database from Notion.");
            headers.add(name);
        }
        if (headers.size() < 2)
            throw new IOException("That CSV has only one column. In Notion, export the database as Markdown & CSV.");
        int width = headers.size();
        // Pages are matched on the column the mapping will use by default, so a
        // page never attaches itself to a row through an unrelated cell.
        String titleHeader = autoMap(headers).title();
        var rows = new ArrayList<Row>();
        int number = 0;
        for (int i = 1; i < records.size(); i++) {
            var cells = records.get(i);
            if (cells.stream().allMatch(String::isBlank)) continue;
            if (++number > MAX_ROWS)
                throw new IOException("That export has more than " + MAX_ROWS + " rows. Export the database in smaller batches.");
            var values = new LinkedHashMap<String, String>();
            for (int c = 0; c < width; c++) values.put(headers.get(c), c < cells.size() ? cells.get(c) : "");
            String page = "";
            String key = titleHeader == null ? "" : canonical(values.get(titleHeader));
            if (!key.isEmpty()) page = pages.getOrDefault(key, "");
            rows.add(new Row(number, values, page));
        }
        return new Sheet(source, headers, rows);
    }

    /** RFC 4180 enough for Notion: quoted cells, doubled quotes, commas and newlines inside them. */
    private static List<List<String>> records(String text) throws IOException {
        var records = new ArrayList<List<String>>();
        var record = new ArrayList<String>();
        var cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c != '"') { cell.append(c); continue; }
                if (i + 1 < text.length() && text.charAt(i + 1) == '"') { cell.append('"'); i++; }
                else quoted = false;
                continue;
            }
            switch (c) {
                case '"' -> quoted = true;
                case ',' -> { record.add(cell.toString()); cell.setLength(0); }
                case '\r' -> {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                    end(record, cell, records);
                }
                case '\n' -> end(record, cell, records);
                default -> cell.append(c);
            }
        }
        if (quoted) throw new IOException("That CSV has an unclosed quote. Re-export the database from Notion.");
        if (cell.length() > 0 || !record.isEmpty()) end(record, cell, records);
        return records;
    }

    private static void end(List<String> record, StringBuilder cell, List<List<String>> records) {
        record.add(cell.toString());
        cell.setLength(0);
        records.add(List.copyOf(record));
        record.clear();
    }

    private static Map<String, byte[]> unzip(byte[] bytes) throws IOException {
        var files = new LinkedHashMap<String, byte[]>();
        long total = 0;
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory() || ignored(entry.getName())) continue;
                // Only the two kinds Yoru reads are read at all: attachments are
                // skipped rather than loaded into memory and counted against the
                // limit. A 1000-row export is one CSV and 1000 pages, which the
                // old count of 1000 files refused at exactly the documented size.
                String lower = entry.getName().toLowerCase(Locale.ROOT);
                if (!lower.endsWith(".csv") && !lower.endsWith(".md")) continue;
                byte[] data = zip.readNBytes(MAX_EXPORT_BYTES + 1);
                total += data.length;
                if (data.length > MAX_EXPORT_BYTES || total > MAX_EXPORT_BYTES || files.size() >= 2 * MAX_ROWS + 4)
                    throw new IOException("That export holds more than Yoru will read. Export the database in smaller batches.");
                files.put(entry.getName(), data);
            }
        } catch (ZipException e) {
            throw new IOException("That file is not a zip Yoru can read. Choose the .zip Notion downloaded, or the .csv inside it.", e);
        }
        return files;
    }

    /** Notion's export carries macOS resource forks and folder metadata, which are never the data. */
    private static boolean ignored(String name) {
        return name.startsWith("__MACOSX/") || name.startsWith("._") || name.contains("/._")
            || name.equals(".DS_Store") || name.endsWith("/.DS_Store");
    }

    private static boolean isZip(byte[] bytes) {
        return bytes.length >= 4 && bytes[0] == 'P' && bytes[1] == 'K'
            && (bytes[2] == 3 || bytes[2] == 5 || bytes[2] == 7);
    }

    private static int depth(String name) {
        int depth = 0;
        for (int i = 0; i < name.length(); i++) if (name.charAt(i) == '/') depth++;
        return depth;
    }

    private static String fileName(String path) {
        if (path == null || path.isBlank()) return DEFAULT_SOURCE;
        String base = path.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1).strip();
        return base.isEmpty() ? DEFAULT_SOURCE : base;
    }

    private static final Pattern PAGE_HASH = Pattern.compile("(?s)^(.*) [0-9a-fA-F]{32}$");

    /** A page file's name without the hash Notion appends, in the form row titles are compared in. */
    private static String pageKey(String path) {
        String base = fileName(path);
        if (base.toLowerCase(Locale.ROOT).endsWith(".md")) base = base.substring(0, base.length() - 3);
        var match = PAGE_HASH.matcher(base.strip());
        return canonical(match.matches() ? match.group(1) : base);
    }

    /** Case and punctuation do not survive a trip through the file system, so they are not compared. */
    private static String canonical(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }

    // ---- interpreting --------------------------------------------------------

    private static final List<String> TITLE_WORDS = List.of("name","title","task","task name","assignment","to do","todo","item","page");
    private static final List<String> STATUS_WORDS = List.of("status","done","complete","completed","checkbox","state","progress","finished");
    private static final List<String> DUE_WORDS = List.of("due","due date","deadline","date","when");
    private static final List<String> TAG_WORDS = List.of("class","classes","category","categories","tag","tags","course","subject","type","label","labels","topic");

    /** Exact names first, then columns merely containing the word; a column is never claimed twice. */
    private static String pick(List<String> headers, List<String> words, Set<String> claimed) {
        for (String word : words)
            for (String header : headers)
                if (!claimed.contains(header) && header.strip().equalsIgnoreCase(word)) {
                    claimed.add(header);
                    return header;
                }
        for (String word : words)
            for (String header : headers) {
                String lower = header.strip().toLowerCase(Locale.ROOT);
                if (!claimed.contains(header) && lower.contains(word)) {
                    claimed.add(header);
                    return header;
                }
            }
        return null;
    }

    private static void require(Sheet sheet, String header, String what) throws IOException {
        if (header != null && !sheet.headers().contains(header))
            throw new IOException("The " + what + " column \"" + header + "\" is not in this export. Choose another column.");
    }

    private static final Set<String> DONE_WORDS = Set.of("done","complete","completed","yes","true","checked","finished","closed","submitted","handed in","✓");
    private static final Set<String> DOING_WORDS = Set.of("in progress","in-progress","doing","started","ongoing","in review","next up");

    /** Notion's status words, or a checkbox's yes and no. Anything else is simply not done yet. */
    private static TaskStatus status(String value) {
        String word = value.strip().toLowerCase(Locale.ROOT);
        if (DONE_WORDS.contains(word)) return TaskStatus.DONE;
        if (DOING_WORDS.contains(word)) return TaskStatus.DOING;
        return TaskStatus.TODO;
    }

    private static final Pattern TIME_OF_DAY = Pattern.compile("\\s+\\d{1,2}:\\d{2}.*$");

    /**
     * The date forms Notion writes, all resolved strictly.
     *
     * Strictly, because the default rounds an impossible date into a real one:
     * 2/30/2026 arrived as 28 February rather than being refused with its row
     * number, which is what the preview promises. ("uuuu" rather than "yyyy" is
     * what strict resolution needs: the year of an era is ambiguous without it.)
     */
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
        DateTimeFormatter.ISO_LOCAL_DATE,
        strict("uuuu/M/d"),
        strict("M/d/uuuu"),
        strict("M/d/uu"),
        strict("MMMM d, uuuu"),
        strict("MMM d, uuuu"),
        strict("d MMMM uuuu"),
        strict("d MMM uuuu"),
        strict("uuuu.M.d"));

    private static DateTimeFormatter strict(String pattern) {
        return DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT);
    }

    private static LocalDate due(Row row, Mapping mapping) throws IOException {
        String value = row.cell(mapping.due());
        if (value.isEmpty()) return null;
        String text = value;
        // Notion writes a date range as "start → end"; the deadline is when it starts.
        int arrow = text.indexOf('→');
        if (arrow < 0) arrow = text.indexOf("->");
        if (arrow >= 0) text = text.substring(0, arrow).strip();
        int time = text.indexOf('T');
        if (time > 0) text = text.substring(0, time).strip();
        // A date property with a time — "September 12, 2026 3:00 PM (EDT)" — is due that day.
        text = TIME_OF_DAY.matcher(text).replaceFirst("").strip();
        for (var format : DATE_FORMATS)
            try { return LocalDate.parse(text, format); } catch (DateTimeParseException ignored) { }
        throw new IOException("Row " + row.number() + ": \"" + value + "\" is not a date Yoru can read. "
            + "Map another column to the due date, or set it to none.");
    }

    /** A Notion multi-value column arrives as one quoted cell: "Biology, Mathematics". */
    private static List<String> tagNames(String cell) {
        if (cell.isEmpty()) return List.of();
        var names = new LinkedHashMap<String, String>();
        for (String part : cell.split(",")) {
            String name = bound(printable(part), MAX_TAG);
            if (!name.isEmpty()) names.putIfAbsent(name.toLowerCase(Locale.ROOT), name);
            if (names.size() >= MAX_TAGS_PER_ROW) break;
        }
        return List.copyOf(names.values());
    }

    /**
     * The page's own text. Notion opens every page with a heading that repeats
     * the title and then one "Property: value" line per column — the row's own
     * fields, which the import already carries — so notes start after both.
     */
    private static String body(String page, String title, List<String> headers) {
        if (page.isBlank()) return "";
        String[] lines = page.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        int start = skipBlank(lines, 0);
        if (start < lines.length) {
            String first = lines[start].strip();
            if (first.startsWith("# ") && canonical(first.substring(2)).equals(canonical(title))) start = skipBlank(lines, start + 1);
        }
        var columns = new HashSet<String>();
        for (String header : headers) columns.add(canonical(header));
        while (start < lines.length) {
            int colon = lines[start].indexOf(':');
            if (colon <= 0 || !columns.contains(canonical(lines[start].substring(0, colon)))) break;
            start++;
        }
        return bound(String.join("\n", Arrays.copyOfRange(lines, start, lines.length)).strip(), MAX_NOTES);
    }

    private static int skipBlank(String[] lines, int from) {
        while (from < lines.length && lines[from].isBlank()) from++;
        return from;
    }

    /** Keeps a value inside a model's bound, visibly, rather than refusing the whole import. */
    private static String bound(String value, int max) {
        if (value.length() <= max) return value;
        int end = max - 1;
        // Never between the halves of a surrogate pair: an emoji at the limit
        // was stored as a lone half, which is not a character at all.
        if (Character.isHighSurrogate(value.charAt(end - 1))) end--;
        return value.substring(0, end).stripTrailing() + "…";
    }

    /**
     * A cell as text a task can hold: no control characters, no runs of spaces.
     *
     * The model refuses a title with a tab or a line break in it, so one such
     * cell threw part-way through the import, naming no row and importing
     * nothing. A title is bounded rather than refused, and this is the same
     * idea: what cannot be stored is cleaned up, not fatal.
     */
    private static String printable(String value) {
        var out = new StringBuilder(value.length());
        value.codePoints().forEach(c -> out.appendCodePoint(Character.isISOControl(c) ? ' ' : c));
        return out.toString().replaceAll("\\s+", " ").strip();
    }
}
