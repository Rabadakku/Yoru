package dev.yoru.application;

import dev.yoru.domain.Model.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a task typed on one line (#74): "Essay draft tomorrow 5pm #school
 * !high every monday" is a task called "Essay draft", due tomorrow at five in
 * the afternoon, tagged school, of high priority, repeating every Monday.
 *
 * English, with no network and no clock of its own: "today" and the owner's
 * week start are given, so the same words always mean the same day. Every part
 * it recognises keeps where it was typed, so the field can show it and the
 * owner can keep it as plain text instead: a part named in {@code kept} is
 * left in the title.
 *
 * <h2>The rules for words that could mean two things</h2>
 * <ul>
 *   <li>A weekday is the next one to come. Said on a Friday, "Friday" is a
 *       week today; "this Friday" is today; "next Friday" is the Friday of
 *       next week, counted from the owner's week start.</li>
 *   <li>A date without a year is the next one to come: "Oct 14" typed on
 *       October 15 is next October. "The 14th" is the next 14th, skipping a
 *       month that has none.</li>
 *   <li>"At 9" with no am or pm means 9 in the morning from 8 to 12, and in
 *       the afternoon from 1 to 7: "at 3" is 3 pm, "at 9" is 9 am.</li>
 *   <li>A time with no day is today. "Tonight" is today, at 8 pm unless a time
 *       is given.</li>
 *   <li>"Next week" is the first day of next week; "next month" and "next
 *       year" are their first days; "this weekend" is the coming Saturday.</li>
 *   <li>A repeat with no date starts on its first day to come: "every Monday"
 *       typed on a Wednesday is due next Monday; "every day" is due today.</li>
 *   <li>Only the first date and the first time count; a second stays in the
 *       title. Numbers that are not written as a date — "chapter 4", "HW
 *       3.1" — are never read as one.</li>
 *   <li>A tag is made if it does not exist yet, and starts with a letter; a
 *       list is only recognised if it exists, since typing "/misc" should not
 *       make a list by accident.</li>
 *   <li>"Sat" and "sun" on their own are words, not days; "on sat" is a day.</li>
 *   <li>If nothing is left for a title, nothing is recognised: "tomorrow" on
 *       its own is a task called "tomorrow".</li>
 * </ul>
 */
public final class QuickAdd {
    private QuickAdd() { }

    /** What a recognised part sets. */
    public enum Kind {
        DATE("Due"), TIME("Time"), TAG("Tag"), LIST("List"), PRIORITY("Priority"), REPEAT("Repeats");
        public final String label;
        Kind(String label) { this.label = label; }
    }

    /** One recognised part: what it sets and where it was typed. */
    public record Part(Kind kind, int start, int end, String text) {
        /** Names this part across edits, so "keep as text" survives typing elsewhere in the line. */
        public String key() { return kind.name() + ":" + text.toLowerCase(Locale.ROOT).strip(); }
    }

    /** What the line holds. Absent parts are null, or empty for the tags. */
    public record Result(String title, LocalDate due, LocalTime time, List<String> tags, String list,
                         Priority priority, Repeat repeat, List<Part> parts) {
        public Result { tags = List.copyOf(tags); parts = List.copyOf(parts); }
    }

    /** What the words are read against: the day, the owner's week start, and the tags and lists the vault has. */
    public record Context(LocalDate today, DayOfWeek weekStart, List<String> tags, List<String> lists) {
        public Context {
            Objects.requireNonNull(today);
            weekStart = weekStart == null ? DayOfWeek.MONDAY : weekStart;
            tags = tags == null ? List.of() : List.copyOf(tags);
            lists = lists == null ? List.of() : List.copyOf(lists);
        }
    }

    /** Reads a line, recognising everything. */
    public static Result parse(String input, Context context) { return parse(input, context, Set.of()); }

    /** Reads a line, leaving the parts whose {@link Part#key()} is in {@code kept} as text. */
    public static Result parse(String input, Context context, Set<String> kept) {
        return new Reader(Objects.requireNonNull(input), context, kept == null ? Set.of() : kept).read();
    }

    // ------------------------------------------------------------------ words

    private static final String WEEKDAY = "monday|mon|tuesday|tues|tue|wednesday|wed|thursday|thurs|thur|thu|friday|fri|saturday|sat|sunday|sun";
    private static final String MONTH = "january|jan|february|feb|march|mar|april|apr|may|june|jun|july|jul|august|aug|september|sept|sep|october|oct|november|nov|december|dec";
    private static final String COUNT = "\\d{1,3}|a|an|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve";
    private static final String UNIT = "days?|weeks?|months?|years?";
    private static final String LEAD = "(?:(?:due|by|on|for)\\s+)?";

    private static final Pattern REPEAT = Pattern.compile(
        "\\b(?:every\\s+(?:(other)\\s+)?(?:(" + COUNT + ")\\s+)?(days?|weekdays?|weeks?|months?|years?"
        + "|(?:" + WEEKDAY + ")(?:\\s*(?:,|and|&)\\s*(?:" + WEEKDAY + "))*)"
        + "|(daily|weekly|monthly|yearly|annually)"
        + "|each\\s+(day|week|month|year|" + WEEKDAY + "))\\b");

    private static final Pattern PRIORITY = Pattern.compile("(?<![^\\s])!(urgent|high|medium|med|low|none|p?[1-4])(?![^\\s])");

    /** The date forms, each read by its own rule below; the earliest in the line wins. */
    private static final List<Pattern> DATES = List.of(
        Pattern.compile("\\b" + LEAD + "(today|tonight|tomorrow|tmrw|tmr)\\b"),
        Pattern.compile("\\b" + LEAD + "in\\s+(" + COUNT + ")\\s+(" + UNIT + ")\\b"),
        Pattern.compile("\\b" + LEAD + "(next|this)\\s+(week|month|year|weekend|" + WEEKDAY + ")\\b"),
        Pattern.compile("\\b" + LEAD + "(weekend)\\b"),
        Pattern.compile("\\b" + LEAD + "(\\d{4})-(\\d{1,2})-(\\d{1,2})\\b"),
        Pattern.compile("(?<![\\w/.])" + LEAD + "(\\d{1,2})/(\\d{1,2})(?:/(\\d{2}|\\d{4}))?(?![\\w/])"),
        Pattern.compile("\\b" + LEAD + "(" + MONTH + ")\\.?\\s+(\\d{1,2})(?:st|nd|rd|th)?(?:,?\\s+(\\d{4}))?\\b"),
        Pattern.compile("\\b" + LEAD + "(\\d{1,2})(?:st|nd|rd|th)?\\s+(?:of\\s+)?(" + MONTH + ")\\.?(?:,?\\s+(\\d{4}))?\\b"),
        Pattern.compile("\\b" + LEAD + "the\\s+(\\d{1,2})(st|nd|rd|th)\\b"),
        Pattern.compile("\\b" + LEAD + "(" + WEEKDAY + ")\\b"));

    private static final List<Pattern> TIMES = List.of(
        Pattern.compile("\\b(?:at\\s+)?(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm|a\\.m\\.|p\\.m\\.)(?![\\w])"),
        Pattern.compile("\\b(?:at\\s+)?([01]?\\d|2[0-3]):([0-5]\\d)\\b"),
        Pattern.compile("\\b(?:at\\s+)?(noon|midday|midnight)\\b"),
        Pattern.compile("\\bat\\s+(\\d{1,2})\\b(?![:.]\\d)"));

    private static final class Reader {
        private final String input, lower;
        private final Context context;
        private final Set<String> kept;
        private final boolean[] taken;
        private final List<Part> parts = new ArrayList<>();
        private final List<String> tags = new ArrayList<>();
        private LocalDate due;
        private LocalTime time;
        private boolean tonight;
        private String list;
        private Priority priority = Priority.NONE;
        private Repeat repeat;
        private RepeatSpec repeatSpec;

        Reader(String input, Context context, Set<String> kept) {
            this.input = input;
            this.context = context;
            this.kept = kept;
            // Lower-cased a character at a time, so every index means the same
            // place in both: a whole-string lower-casing can change the length.
            var chars = input.toCharArray();
            for (int i = 0; i < chars.length; i++) chars[i] = Character.toLowerCase(chars[i]);
            this.lower = new String(chars);
            this.taken = new boolean[input.length()];
        }

        Result read() {
            repeats();
            marked('#', Kind.TAG, context.tags(), true);
            marked('/', Kind.LIST, context.lists(), false);
            marked('@', Kind.LIST, context.lists(), false);
            priorities();
            date();
            time();
            String title = title();
            if (title.isBlank()) return new Result(input.strip(), null, null, List.of(), null, Priority.NONE, null, List.of());
            if (tonight && time == null) time = LocalTime.of(20, 0);
            if (due == null && time != null) due = context.today();
            if (repeatSpec != null) {
                if (due == null) due = repeatSpec.first(context.today());
                repeat = repeatSpec.rule(due);
            }
            parts.sort(Comparator.comparingInt(Part::start));
            return new Result(title, due, time, tags, list, priority, repeat, parts);
        }

        /** Claims a span if it is free and not kept as text. */
        private boolean claim(Kind kind, int start, int end) {
            for (int i = start; i < end; i++) if (taken[i]) return false;
            var part = new Part(kind, start, end, input.substring(start, end));
            if (kept.contains(part.key())) return false;
            for (int i = start; i < end; i++) taken[i] = true;
            parts.add(part);
            return true;
        }

        // ------------------------------------------------------------ parts

        private void repeats() {
            var m = REPEAT.matcher(lower);
            while (m.find()) {
                var spec = RepeatSpec.of(m);
                if (spec == null || !claim(Kind.REPEAT, m.start(), m.end())) continue;
                repeatSpec = spec;
                return;
            }
        }

        /**
         * A word after its mark: "#school", "/Errands". Names the vault already
         * has are matched whole, longest first, so "#reading group" finds a tag
         * of two words; otherwise a tag is the one word after the mark.
         */
        private void marked(char mark, Kind kind, List<String> known, boolean anyWord) {
            var names = known.stream().sorted(Comparator.comparingInt(String::length).reversed()).toList();
            for (int at = 0; at < input.length(); at++) {
                if (input.charAt(at) != mark || (at > 0 && !Character.isWhitespace(input.charAt(at - 1)))) continue;
                String found = null;
                int end = -1;
                for (var name : names) {
                    int stop = at + 1 + name.length();
                    if (stop <= input.length() && lower.startsWith(name.toLowerCase(Locale.ROOT), at + 1)
                        && (stop == input.length() || !Character.isLetterOrDigit(input.charAt(stop)))) {
                        found = name; end = stop; break;
                    }
                }
                if (found == null && anyWord && at + 1 < input.length() && Character.isLetter(input.charAt(at + 1))) {
                    int stop = at + 1;
                    while (stop < input.length() && (Character.isLetterOrDigit(input.charAt(stop)) || "-_/".indexOf(input.charAt(stop)) >= 0)) stop++;
                    if (stop > at + 1) { found = input.substring(at + 1, stop); end = stop; }
                }
                if (found == null) continue;
                if (kind == Kind.TAG && found.length() > 40) continue;
                if (kind == Kind.LIST && list != null) continue;
                if (!claim(kind, at, end)) continue;
                if (kind == Kind.TAG) { if (tags.stream().noneMatch(found::equalsIgnoreCase)) tags.add(found); }
                else list = found;
            }
        }

        private void priorities() {
            var m = PRIORITY.matcher(lower);
            while (m.find()) {
                if (!claim(Kind.PRIORITY, m.start(), m.end())) continue;
                priority = switch (m.group(1).replace("p", "")) {
                    case "urgent", "1" -> Priority.URGENT;
                    case "high", "2" -> Priority.HIGH;
                    case "medium", "med", "3" -> Priority.MEDIUM;
                    case "low", "4" -> Priority.LOW;
                    default -> Priority.NONE;
                };
                return;
            }
        }

        /** The earliest date form in the line that reads as a real day. */
        private void date() {
            int best = Integer.MAX_VALUE;
            Matcher chosen = null;
            int which = -1;
            for (int i = 0; i < DATES.size(); i++) {
                var m = DATES.get(i).matcher(lower);
                while (m.find()) {
                    if (m.start() >= best) break;
                    if (!free(m.start(), m.end()) || readDate(i, m) == null) continue;
                    var probe = new Part(Kind.DATE, m.start(), m.end(), input.substring(m.start(), m.end()));
                    if (kept.contains(probe.key())) continue;
                    best = m.start(); chosen = m; which = i;
                    break;
                }
            }
            if (chosen == null) return;
            var day = readDate(which, chosen);
            if (claim(Kind.DATE, chosen.start(), chosen.end())) {
                due = day;
                tonight = chosen.group(0).contains("tonight");
            }
        }

        private boolean free(int start, int end) {
            for (int i = start; i < end; i++) if (taken[i]) return false;
            return true;
        }

        private void time() {
            int best = Integer.MAX_VALUE;
            Matcher chosen = null;
            int which = -1;
            for (int i = 0; i < TIMES.size(); i++) {
                var m = TIMES.get(i).matcher(lower);
                while (m.find()) {
                    if (m.start() >= best) break;
                    if (!free(m.start(), m.end()) || readTime(i, m) == null) continue;
                    var probe = new Part(Kind.TIME, m.start(), m.end(), input.substring(m.start(), m.end()));
                    if (kept.contains(probe.key())) continue;
                    best = m.start(); chosen = m; which = i;
                    break;
                }
            }
            if (chosen != null && claim(Kind.TIME, chosen.start(), chosen.end())) time = readTime(which, chosen);
        }

        // ------------------------------------------------------------ reading

        private LocalDate readDate(int form, Matcher m) {
            var today = context.today();
            try {
                return switch (form) {
                    case 0 -> switch (m.group(1)) {
                        case "today", "tonight" -> today;
                        default -> today.plusDays(1);
                    };
                    case 1 -> {
                        int n = count(m.group(1));
                        yield switch (m.group(2).replaceAll("s$", "")) {
                            case "day" -> today.plusDays(n);
                            case "week" -> today.plusWeeks(n);
                            case "month" -> today.plusMonths(n);
                            default -> today.plusYears(n);
                        };
                    }
                    case 2 -> relative(m.group(1), m.group(2));
                    case 3 -> relative("this", "weekend");
                    case 4 -> LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
                    case 5 -> {
                        int month = Integer.parseInt(m.group(1)), day = Integer.parseInt(m.group(2));
                        yield m.group(3) == null ? coming(month, day) : LocalDate.of(year(m.group(3)), month, day);
                    }
                    case 6 -> m.group(3) == null ? coming(month(m.group(1)), Integer.parseInt(m.group(2)))
                        : LocalDate.of(Integer.parseInt(m.group(3)), month(m.group(1)), Integer.parseInt(m.group(2)));
                    case 7 -> m.group(3) == null ? coming(month(m.group(2)), Integer.parseInt(m.group(1)))
                        : LocalDate.of(Integer.parseInt(m.group(3)), month(m.group(2)), Integer.parseInt(m.group(1)));
                    case 8 -> ordinal(Integer.parseInt(m.group(1)), m.group(2));
                    case 9 -> {
                        // "Sat" and "sun" are words as often as days — "SAT prep",
                        // "sun cream" — so on their own they are only days after
                        // "on", "by", "due" or "for".
                        var word = m.group(1);
                        if ((word.equals("sat") || word.equals("sun")) && m.group(0).equals(word)) yield null;
                        yield after(today, weekday(word));
                    }
                    default -> null;
                };
            } catch (DateTimeException | NumberFormatException impossible) {
                return null;
            }
        }

        /** "Next Friday", "this weekend", "next month". */
        private LocalDate relative(String which, String what) {
            var today = context.today();
            var weekStart = today.with(TemporalAdjusters.previousOrSame(context.weekStart()));
            boolean next = which.equals("next");
            return switch (what) {
                case "week" -> next ? weekStart.plusWeeks(1) : weekStart;
                case "month" -> next ? today.plusMonths(1).withDayOfMonth(1) : today.withDayOfMonth(1);
                case "year" -> next ? today.plusYears(1).withDayOfYear(1) : today.withDayOfYear(1);
                case "weekend" -> {
                    var saturday = today.getDayOfWeek() == DayOfWeek.SUNDAY ? today : today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY));
                    yield next ? saturday.plusWeeks(1) : saturday;
                }
                default -> {
                    var day = weekday(what);
                    if (!next) yield today.with(TemporalAdjusters.nextOrSame(day));
                    var nextWeek = weekStart.plusWeeks(1);
                    yield nextWeek.with(TemporalAdjusters.nextOrSame(day));
                }
            };
        }

        /** The next date with this month and day, today included; February 29 waits for a leap year. */
        private LocalDate coming(int month, int day) {
            var today = context.today();
            if (month < 1 || month > 12 || day < 1 || day > 31) throw new DateTimeException("no such day");
            for (int year = today.getYear(); year <= today.getYear() + 8; year++) {
                if (day > YearMonth.of(year, month).lengthOfMonth()) continue;
                var candidate = LocalDate.of(year, month, day);
                if (!candidate.isBefore(today)) return candidate;
            }
            throw new DateTimeException("no such day");
        }

        /** "The 14th": the next 14th, skipping a month too short for it. "The 3th" is not a date. */
        private LocalDate ordinal(int day, String suffix) {
            String right = day % 100 >= 11 && day % 100 <= 13 ? "th"
                : switch (day % 10) { case 1 -> "st"; case 2 -> "nd"; case 3 -> "rd"; default -> "th"; };
            if (!suffix.equals(right) || day < 1 || day > 31) return null;
            var month = YearMonth.from(context.today());
            for (int i = 0; i < 13; i++, month = month.plusMonths(1)) {
                if (day > month.lengthOfMonth()) continue;
                var candidate = month.atDay(day);
                if (!candidate.isBefore(context.today())) return candidate;
            }
            return null;
        }

        private LocalTime readTime(int form, Matcher m) {
            try {
                return switch (form) {
                    case 0 -> {
                        int hour = Integer.parseInt(m.group(1));
                        int minute = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
                        if (hour < 1 || hour > 12 || minute > 59) yield null;
                        yield LocalTime.of(hour % 12 + (m.group(3).startsWith("p") ? 12 : 0), minute);
                    }
                    case 1 -> LocalTime.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
                    case 2 -> m.group(1).equals("midnight") ? LocalTime.MIDNIGHT : LocalTime.NOON;
                    case 3 -> {
                        int hour = Integer.parseInt(m.group(1));
                        if (hour < 1 || hour > 23) yield null;
                        // "At 3" is the afternoon, "at 9" the morning; 13 to 23 are already the afternoon.
                        yield LocalTime.of(hour >= 1 && hour <= 7 ? hour + 12 : hour, 0);
                    }
                    default -> null;
                };
            } catch (DateTimeException | NumberFormatException impossible) {
                return null;
            }
        }

        private int year(String typed) {
            int year = Integer.parseInt(typed);
            return typed.length() == 2 ? 2000 + year : year;
        }

        /** What is left once every part is taken out, tidied at the joins. */
        private String title() {
            var out = new StringBuilder();
            for (int i = 0; i < input.length(); i++) out.append(taken[i] ? ' ' : input.charAt(i));
            return out.toString().replaceAll("\\s+", " ").replaceAll(" ([,;:.!?])", "$1")
                .replaceAll("^[\\s,;:\\-–—]+|[\\s,;:\\-–—]+$", "").strip();
        }
    }

    // ------------------------------------------------------------------ repeats

    /** What "every …" said, turned into a rule once the day it starts is known. */
    private record RepeatSpec(RepeatUnit unit, int every, Set<DayOfWeek> days) {
        static RepeatSpec of(Matcher m) {
            if (m.group(4) != null) return switch (m.group(4)) {
                case "daily" -> new RepeatSpec(RepeatUnit.DAY, 1, Set.of());
                case "weekly" -> new RepeatSpec(RepeatUnit.WEEK, 1, Set.of());
                case "monthly" -> new RepeatSpec(RepeatUnit.MONTH, 1, Set.of());
                default -> new RepeatSpec(RepeatUnit.YEAR, 1, Set.of());
            };
            if (m.group(5) != null) return spec(m.group(5), 1);
            int every = m.group(1) != null ? 2 : m.group(2) != null ? count(m.group(2)) : 1;
            if (every < 1 || every > 999) return null;
            return spec(m.group(3), every);
        }

        private static RepeatSpec spec(String what, int every) {
            return switch (what.replaceAll("s$", "")) {
                case "day" -> new RepeatSpec(RepeatUnit.DAY, every, Set.of());
                case "week" -> new RepeatSpec(RepeatUnit.WEEK, every, Set.of());
                case "month" -> new RepeatSpec(RepeatUnit.MONTH, every, Set.of());
                case "year" -> new RepeatSpec(RepeatUnit.YEAR, every, Set.of());
                case "weekday" -> new RepeatSpec(RepeatUnit.WEEK, every,
                    EnumSet.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY));
                default -> {
                    var days = EnumSet.noneOf(DayOfWeek.class);
                    for (var word : what.split("\\s*(?:,|and|&)\\s*")) if (!word.isBlank()) days.add(weekday(word.strip()));
                    yield new RepeatSpec(RepeatUnit.WEEK, every, days);
                }
            };
        }

        /** The first day it falls on, from today: the next of its weekdays, or today. */
        LocalDate first(LocalDate today) {
            if (days.isEmpty()) return today;
            for (int i = 0; i < 7; i++) if (days.contains(today.plusDays(i).getDayOfWeek())) return today.plusDays(i);
            return today;
        }

        Repeat rule(LocalDate start) {
            return switch (unit) {
                case DAY -> Repeat.daily(every, start);
                case WEEK -> Repeat.weekly(every, days.isEmpty() ? Set.of(start.getDayOfWeek()) : days, start);
                case MONTH -> Repeat.monthly(every, start);
                case YEAR -> Repeat.yearly(every, start);
            };
        }
    }

    // ------------------------------------------------------------------ helpers

    private static final List<String> NUMBERS = List.of("zero", "one", "two", "three", "four", "five", "six",
        "seven", "eight", "nine", "ten", "eleven", "twelve");

    private static int count(String word) {
        if (word.equals("a") || word.equals("an")) return 1;
        int at = NUMBERS.indexOf(word);
        return at >= 0 ? at : Integer.parseInt(word);
    }

    private static DayOfWeek weekday(String word) {
        for (var day : DayOfWeek.values()) {
            String name = day.name().toLowerCase(Locale.ROOT);
            if (name.startsWith(word) && word.length() >= 3) return day;
        }
        throw new DateTimeException("Not a weekday: " + word);
    }

    /** The next such weekday strictly after today: said on a Friday, "Friday" is a week today. */
    private static LocalDate after(LocalDate today, DayOfWeek day) {
        return today.with(TemporalAdjusters.next(day));
    }

    private static int month(String word) {
        String[] names = {"jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"};
        for (int i = 0; i < names.length; i++) if (word.startsWith(names[i])) return i + 1;
        throw new DateTimeException("Not a month: " + word);
    }

    /** A task ready to save from a line, and the tags it names that the vault does not have yet. */
    public record Draft(Task task, List<Tag> newTags) {
        public Draft { newTags = List.copyOf(newTags); }
    }

    /**
     * The task a line makes (#74): its tags matched to the vault's by name or
     * made new, its list matched by name, and — with no date typed — due
     * today, as a task written down in the form is (#67).
     *
     * @param list    the list it goes in when the line names none: the one on screen, or null for the Inbox.
     * @param colours a colour for the n-th new tag, from the tag palette.
     */
    public static Draft draft(Result line, State state, UUID list, LocalDate today, Instant now,
                              java.util.function.IntUnaryOperator colours) {
        var tags = new ArrayList<UUID>();
        var fresh = new ArrayList<Tag>();
        for (var name : line.tags()) {
            var known = state.tags().stream().filter(t -> t.name().equalsIgnoreCase(name)).findFirst();
            if (known.isPresent()) { if (!tags.contains(known.get().id())) tags.add(known.get().id()); continue; }
            var made = new Tag(UUID.randomUUID(), name, colours.applyAsInt(state.tags().size() + fresh.size()));
            fresh.add(made);
            tags.add(made.id());
        }
        UUID filed = list;
        if (line.list() != null)
            filed = state.lists().stream().filter(l -> l.name().equalsIgnoreCase(line.list())).map(TaskList::id).findFirst().orElse(list);
        var due = line.due() != null ? line.due() : today;
        int order = state.tasks().stream().mapToInt(Task::order).max().orElse(-1) + 1;
        var task = new Task(UUID.randomUUID(), null, tags, line.title(), "", due, TaskStatus.TODO, "Quick add", now, order,
            null, List.of(), filed, line.repeat(), List.of());
        task = task.withDetails(task.details().withPriority(line.priority()).withDueTime(line.time()));
        return new Draft(task, fresh);
    }

    /** How far a day is from today, in words a hint can use: "today", "tomorrow", "in 3 days". */
    public static String distance(LocalDate day, LocalDate today) {
        long days = ChronoUnit.DAYS.between(today, day);
        if (days == 0) return "today";
        if (days == 1) return "tomorrow";
        if (days > 1) return "in " + days + " days";
        return days == -1 ? "yesterday" : -days + " days ago";
    }
}
