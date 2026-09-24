package dev.yoru.domain;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Every stored record, with validation in the constructors so illegal states
 * cannot be built — the UI's job is to catch bad input earlier and more
 * legibly, never to be the only thing standing between a typo and the vault.
 *
 * Product rules (a five-minute floor, a daily goal) are NOT baked in here.
 * They live in Settings and are applied by Analytics at read time, so changing
 * one is an edit rather than a migration. See docs/DATA-MODEL.md.
 */
public final class Model {
    private Model() {
    }
    /** The first moment a record may hold, and the first one past the last it may. */
    private static final Instant EARLIEST = Instant.parse("1900-01-01T00:00:00Z"), LATEST = Instant.parse("2200-01-01T00:00:00Z");
    private static void requireTime(Instant time) {
        Objects.requireNonNull(time);
        if(time.isBefore(EARLIEST) || !time.isBefore(LATEST))
            throw new IllegalArgumentException("Choose a date between 1900 and 2199.");
    }
    private static void requireSpan(Instant start, Instant end) {
        if(Duration.between(start,end).compareTo(Duration.ofDays(31))>0)
            throw new IllegalArgumentException("A single session or schedule block can span at most 31 days. Correct the start or end time.");
    }
    private static String requireName(String value,int limit,String what) {
        value = Objects.requireNonNull(value).strip();
        if (value.isEmpty() || value.length()>limit || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Use a "+what+" of 1–"+limit+" printable characters.");
        return value;
    }
    private static int requireColour(int rgb) {
        if((rgb & ~0xFFFFFF)!=0) throw new IllegalArgumentException("Colour must be 24-bit RGB.");
        return rgb;
    }
    private record Interval(UUID id,Instant start,Instant end) {}
    private static void validateIntervals(List<Interval> intervals) {
        var ids=new HashSet<UUID>(); Interval previous=null;
        for(var value:intervals.stream().sorted(Comparator.comparing(Interval::start)).toList()) {
            if(!ids.add(value.id())) throw new IllegalArgumentException("Duplicate time record.");
            if(previous!=null && (previous.end()==null || value.start().isBefore(previous.end())))
                throw new IllegalArgumentException("This overlaps another time record.");
            previous=value;
        }
    }

    /** Named separately from dev.yoru.ui.Theme, which resolves one of these to colours. */
    public enum ThemeId {
        MIDNIGHT, EMBER, SAKURA, LINEN, MOONLIGHT;

        /**
         * The theme a saved workspace names, or the nearest one that still exists.
         *
         * A workspace saved with the withdrawn Waifu theme (1.0.10 only) would
         * otherwise fail to open on its name alone. It reads as Moonlight, the
         * palette it was split from; anything else unknown reads as the default.
         */
        public static ThemeId known(String name) {
            if ("WAIFU".equals(name)) return MOONLIGHT;
            try { return valueOf(name); }
            catch (IllegalArgumentException | NullPointerException unknown) { return MIDNIGHT; }
        }
    }
    /**
     * A task's state. The stored form is the constant's name; {@code label} is
     * only how it is written on screen, so a task's status button reads "To do"
     * rather than the raw enum.
     */
    public enum TaskStatus {
        TODO("To do"), DOING("Doing"), DONE("Done");
        public final String label;
        TaskStatus(String label) { this.label=label; }
        @Override public String toString() { return label; }
    }
    public enum HabitKind { DAILY, TIME_SINCE }

    public record Settings(ThemeId theme, int dailyGoalHours, int minSessionSeconds,
                           DayOfWeek weekStartsOn) {
        /** Kept for callers that predate the week-start preference. */
        public Settings(ThemeId theme, int dailyGoalHours, int minSessionSeconds) {
            this(theme,dailyGoalHours,minSessionSeconds,DayOfWeek.MONDAY);
        }
        public Settings {
            Objects.requireNonNull(theme);
            Objects.requireNonNull(weekStartsOn);
            if(dailyGoalHours<1 || dailyGoalHours>16)
                throw new IllegalArgumentException("Daily goal must be between 1 and 16 hours.");
            if(minSessionSeconds<0 || minSessionSeconds>3600)
                throw new IllegalArgumentException("Minimum session must be between 0 and 60 minutes.");
        }
        public static Settings defaults() { return new Settings(ThemeId.MIDNIGHT,4,300,DayOfWeek.MONDAY); }
        /** The start of the week containing this date, under this preference. */
        public LocalDate weekOf(LocalDate date) {
            return date.with(java.time.temporal.TemporalAdjusters.previousOrSame(weekStartsOn));
        }
    }

    public record Activity(UUID id, String name, int targetMinutes) {
        public Activity {
            Objects.requireNonNull(id);
            name = requireName(name,60,"name");
            if(targetMinutes<0 || targetMinutes>1440) throw new IllegalArgumentException("Target must be 0–1440 minutes (0 means none).");
        }
        /**
         * The same activity under a new label.
         *
         * Renaming is not a new activity: the id, and so every session, block,
         * weekly repeat and task that points at it, is untouched. Only the words
         * on screen change.
         */
        public Activity renamed(String name) {
            return new Activity(id,name,targetMinutes);
        }
        /** The same activity with another daily target; nothing that points at it changes. */
        public Activity retargeted(int targetMinutes) {
            return new Activity(id,name,targetMinutes);
        }
        @Override
        public String toString() {
            return name;
        }
    }

    /**
     * Where records go when the activity they pointed at is removed.
     *
     * A name rather than a fixed id: the bucket is an ordinary activity, created
     * the first time something needs it and reused after that, so a vault that
     * never removes anything never grows one and the user can rename it like any
     * other activity.
     */
    public static final String UNCATEGORIZED = "Uncategorized";

    /** A class or section: "CS 240", "Japanese". Colour is 24-bit RGB. */
    public record Tag(UUID id, String name, int colour) {
        public Tag {
            Objects.requireNonNull(id);
            name = requireName(name,40,"tag name");
            colour = requireColour(colour);
        }
        @Override public String toString() { return name; }
    }

    /**
     * A list tasks are filed in: "Chores", "Personal", "School" (#56).
     *
     * A place rather than a label: every task is in exactly one list, or in
     * none, which is the Inbox. Tags still cut across lists. The order is the
     * owner's, as the sidebar shows it.
     */
    public record TaskList(UUID id, String name, int colour, int order) {
        public TaskList {
            Objects.requireNonNull(id);
            name = requireName(name,40,"list name");
            colour = requireColour(colour);
            if (order < 0) throw new IllegalArgumentException("Invalid list order.");
        }
        public TaskList renamed(String next) { return new TaskList(id,next,colour,order); }
        public TaskList recoloured(int next) { return new TaskList(id,name,next,order); }
        public TaskList withOrder(int next) { return new TaskList(id,name,colour,next); }
        @Override public String toString() { return name; }
    }

    /**
     * How a task comes back (#57): every N days, weeks, months or years.
     *
     * Weekly, on the given weekdays. Monthly, on a day of the month — the 31st
     * falls on the last day of a shorter month — or on the nth weekday, where
     * -1 is the last. Yearly, on {@code start}'s month and day, the 29th of
     * February falling on the 28th in other years. The weeks and months are
     * counted from {@code start}, so "every 2 weeks" keeps its own fortnight.
     *
     * On a schedule the next date comes from the rule; {@code afterDone}, it
     * comes from the day the task was last finished. It stops after
     * {@code until}, or after {@code times} occurrences when that is not 0.
     */
    public record Repeat(RepeatUnit unit, int every, Set<DayOfWeek> days, int monthDay, int weekOfMonth,
                         LocalDate start, boolean afterDone, LocalDate until, int times) {
        public Repeat {
            Objects.requireNonNull(unit);
            Objects.requireNonNull(start);
            days = days == null || days.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(days));
            if (every < 1 || every > 999) throw new IllegalArgumentException("Repeat every 1 to 999.");
            if (unit == RepeatUnit.WEEK && days.isEmpty()) days = Set.of(start.getDayOfWeek());
            if (unit != RepeatUnit.WEEK && !days.isEmpty() && !(unit == RepeatUnit.MONTH && weekOfMonth != 0 && days.size() == 1))
                throw new IllegalArgumentException("Weekdays belong to a weekly repeat, or one to a monthly one on the nth weekday.");
            if (unit == RepeatUnit.MONTH && weekOfMonth == 0 && (monthDay < 1 || monthDay > 31))
                throw new IllegalArgumentException("Choose a day of the month from 1 to 31.");
            if (weekOfMonth != 0 && (unit != RepeatUnit.MONTH || weekOfMonth < -1 || weekOfMonth > 4 || days.size() != 1))
                throw new IllegalArgumentException("Choose the first to fourth, or the last, of one weekday.");
            if (until != null && until.isBefore(start)) throw new IllegalArgumentException("A repeat cannot end before it starts.");
            if (times < 0 || times > 10_000) throw new IllegalArgumentException("Repeat at most 10,000 times.");
        }
        /** Every {@code every} days from {@code start}. */
        public static Repeat daily(int every, LocalDate start) {
            return new Repeat(RepeatUnit.DAY, every, Set.of(), 0, 0, start, false, null, 0);
        }
        /** Every {@code every} weeks on {@code days}, counted from {@code start}'s week. */
        public static Repeat weekly(int every, Set<DayOfWeek> days, LocalDate start) {
            return new Repeat(RepeatUnit.WEEK, every, days, 0, 0, start, false, null, 0);
        }
        /** Every {@code every} months on {@code start}'s day of the month. */
        public static Repeat monthly(int every, LocalDate start) {
            return new Repeat(RepeatUnit.MONTH, every, Set.of(), start.getDayOfMonth(), 0, start, false, null, 0);
        }
        /** Every {@code every} months on the nth (or last, -1) {@code weekday}. */
        public static Repeat monthlyOn(int every, int nth, DayOfWeek weekday, LocalDate start) {
            return new Repeat(RepeatUnit.MONTH, every, Set.of(weekday), 0, nth, start, false, null, 0);
        }
        /** Every {@code every} years on {@code start}'s month and day. */
        public static Repeat yearly(int every, LocalDate start) {
            return new Repeat(RepeatUnit.YEAR, every, Set.of(), 0, 0, start, false, null, 0);
        }
        public Repeat afterDone(boolean next) { return new Repeat(unit, every, days, monthDay, weekOfMonth, start, next, until, times); }
        public Repeat ending(LocalDate on, int count) { return new Repeat(unit, every, days, monthDay, weekOfMonth, start, afterDone, on, count); }
    }

    public enum RepeatUnit { DAY, WEEK, MONTH, YEAR }

    /**
     * One occurrence of a repeating task that is behind it: the day it was
     * due, and when it was done or skipped (#57). The task keeps these rather
     * than a copy of itself for every time it came round.
     */
    public record Occurrence(LocalDate due, Instant at, boolean skipped) {
        public Occurrence {
            Objects.requireNonNull(due);
            requireTime(at);
        }
    }

    public record Session(UUID id, UUID activityId, Instant start, Instant end) {
        public Session {
            Objects.requireNonNull(id);
            Objects.requireNonNull(activityId);
            requireTime(start);
            if(end!=null) { requireTime(end); requireSpan(start,end); }
            if(end!=null && !end.isAfter(start)) throw new IllegalArgumentException("End must be after start.");
        }
        public long seconds(Instant now) { return Duration.between(start,end==null?now:end).getSeconds(); }
    }

    public record ScheduleBlock(UUID id, UUID activityId, Instant start, Instant end) {
        public ScheduleBlock {
            Objects.requireNonNull(id);
            Objects.requireNonNull(activityId);
            requireTime(start);
            requireTime(end); requireSpan(start,end);
            if(!end.isAfter(start)) throw new IllegalArgumentException("End must be after start.");
        }
    }

    /**
     * One entry in the weekly template: "every Monday, 09:00 to 10:30".
     *
     * Stores LocalTime and a day of week, never an Instant. A 09:00 class stays
     * at 09:00 across a daylight-saving boundary, which is what "every Monday at
     * nine" actually means; storing instants would drift it by an hour twice a
     * year. Occurrences are expanded for whichever week is on screen.
     *
     * Kept alongside dated ScheduleBlock rather than replacing it: a timetable
     * has both a lecture every Monday and a one-off exam next Thursday.
     */
    public record RecurringBlock(UUID id, UUID activityId, DayOfWeek dayOfWeek,
                                 LocalTime startTime, LocalTime endTime, List<RepeatChange> changes) {
        /** The most weeks one rule keeps changed on their own: twenty years of them. */
        public static final int MAX_CHANGES = 1_040;
        /** A rule no week of which has been changed on its own. */
        public RecurringBlock(UUID id, UUID activityId, DayOfWeek dayOfWeek, LocalTime startTime, LocalTime endTime) {
            this(id, activityId, dayOfWeek, startTime, endTime, List.of());
        }
        public RecurringBlock {
            Objects.requireNonNull(id);
            Objects.requireNonNull(activityId);
            Objects.requireNonNull(dayOfWeek);
            Objects.requireNonNull(startTime);
            Objects.requireNonNull(endTime);
            if(!endTime.isAfter(startTime))
                throw new IllegalArgumentException("A repeating block must end after it starts, on the same day.");
            if(changes.size() > MAX_CHANGES) throw new IllegalArgumentException("Vault record limit reached.");
            var byDate = new TreeMap<LocalDate, RepeatChange>();
            for(var change : changes) {
                Objects.requireNonNull(change);
                // A change is to one of this rule's own weeks: a Monday rule has
                // nothing on a Tuesday to skip or move.
                if(change.date().getDayOfWeek() != dayOfWeek)
                    throw new IllegalArgumentException("A repeat on " + day(dayOfWeek)
                        + " has no block on " + change.date() + " to change.");
                if(byDate.put(change.date(), change) != null)
                    throw new IllegalArgumentException("That week is already changed on its own.");
            }
            changes = List.copyOf(byDate.values());
        }
        /** The change to the week holding this date's block, or null when it follows the rule. */
        public RepeatChange changeOn(LocalDate date) {
            for(var change : changes) if(change.date().equals(date)) return change;
            return null;
        }
        /** The same rule, its changed weeks and all, under another activity. */
        public RecurringBlock withActivity(UUID next) {
            return new RecurringBlock(id, next, dayOfWeek, startTime, endTime, changes);
        }
        /** The same rule with one week's change added, replaced or, given null, taken away. */
        public RecurringBlock withChange(LocalDate date, RepeatChange change) {
            var next = new ArrayList<RepeatChange>();
            for(var c : changes) if(!c.date().equals(date)) next.add(c);
            if(change != null) next.add(change);
            return new RecurringBlock(id, activityId, dayOfWeek, startTime, endTime, next);
        }
        private static String day(DayOfWeek day) {
            return day.getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH);
        }
    }

    /**
     * One week of a repeat that does not follow the rule (#59): skipped, or held
     * at another time — and possibly another day — that week only.
     *
     * Keyed by the date the rule would have put the block on, so the rule and
     * every other week stay exactly as they were, and taking the change away
     * puts that week back where the rule says. A skipped week has no times.
     *
     * @param date    the day the rule puts this week's block on.
     * @param movedTo the day it is held instead, or null when skipped.
     */
    public record RepeatChange(LocalDate date, LocalDate movedTo, LocalTime start, LocalTime end) {
        /** The furthest a week's block may move from its own day: within the same fortnight either way. */
        public static final int MAX_MOVE_DAYS = 6;
        public RepeatChange {
            Objects.requireNonNull(date);
            if(movedTo == null) {
                if(start != null || end != null) throw new IllegalArgumentException("A skipped week has no times.");
            } else {
                Objects.requireNonNull(start);
                Objects.requireNonNull(end);
                if(!end.isAfter(start))
                    throw new IllegalArgumentException("This week's block must end after it starts, on the same day.");
                if(Math.abs(movedTo.toEpochDay() - date.toEpochDay()) > MAX_MOVE_DAYS)
                    throw new IllegalArgumentException("Move one week's block within six days of its own day;"
                        + " further than that, skip it and plan a block.");
            }
        }
        public static RepeatChange skip(LocalDate date) { return new RepeatChange(date, null, null, null); }
        public boolean skipped() { return movedTo == null; }
    }

    /**
     * @param due       the deadline: when this is actually due.
     * @param plannedFor the day you mean to work on it, or null.
     *
     * Two dates because real trackers grow a third one in prose — task titles
     * carrying "(actually due Friday, 11:59 PM)" while the date field holds the
     * day you plan to sit down with it. A deadline you cannot sort on, colour or
     * be warned about is not really recorded.
     */
    public record Task(UUID id, UUID activityId, List<UUID> tagIds, String title, String notes,
                       LocalDate due, TaskStatus status, String source, Instant createdAt, int order,
                       LocalDate plannedFor, List<UUID> pageIds, UUID listId, Repeat repeat, List<Occurrence> history,
                       Details details) {
        /** The most occurrences a repeating task keeps behind it: years of a daily task. */
        public static final int MAX_HISTORY = 10_000;
        /** A task in the Inbox: every task before lists (#56), and every one made outside a list. */
        public Task(UUID id, UUID activityId, List<UUID> tagIds, String title, String notes,
                    LocalDate due, TaskStatus status, String source, Instant createdAt, int order,
                    LocalDate plannedFor, List<UUID> pageIds) {
            this(id,activityId,tagIds,title,notes,due,status,source,createdAt,order,plannedFor,pageIds,null);
        }
        /** A task that does not repeat: every task before #57, and most after it. */
        public Task(UUID id, UUID activityId, List<UUID> tagIds, String title, String notes,
                    LocalDate due, TaskStatus status, String source, Instant createdAt, int order,
                    LocalDate plannedFor, List<UUID> pageIds, UUID listId) {
            this(id,activityId,tagIds,title,notes,due,status,source,createdAt,order,plannedFor,pageIds,listId,null,List.of());
        }
        /** A task with none of the database's details (#68): every task before them, and every new one until it is given some. */
        public Task(UUID id, UUID activityId, List<UUID> tagIds, String title, String notes,
                    LocalDate due, TaskStatus status, String source, Instant createdAt, int order,
                    LocalDate plannedFor, List<UUID> pageIds, UUID listId, Repeat repeat, List<Occurrence> history) {
            this(id,activityId,tagIds,title,notes,due,status,source,createdAt,order,plannedFor,pageIds,listId,repeat,history,Details.NONE);
        }
        /** The most pages one task may link to. */
        public static final int MAX_PAGES = 100;
        /** The most tags one task may carry: more than a row could ever show, fewer than a typo loop could add. */
        public static final int MAX_TAGS = 20;
        /** A task with at most one tag that links to no page: every task before Pages, and every new one. */
        public Task(UUID id, UUID activityId, UUID tagId, String title, String notes,
                    LocalDate due, TaskStatus status, String source, Instant createdAt, int order,
                    LocalDate plannedFor) {
            this(id,activityId,one(tagId),title,notes,due,status,source,createdAt,order,plannedFor,List.of());
        }
        /** Predates the planned/deadline split; everything before it planned nothing. */
        public Task(UUID id, UUID activityId, UUID tagId, String title, String notes,
                    LocalDate due, TaskStatus status, String source, Instant createdAt, int order) {
            this(id,activityId,tagId,title,notes,due,status,source,createdAt,order,null);
        }
        /** The tags of a task that had room for one (#66): none, or that one. */
        public static List<UUID> one(UUID tagId) { return tagId == null ? List.of() : List.of(tagId); }
        /** Convenience for callers that predate status and tags. */
        public Task(UUID id, UUID activityId, String title, String notes, LocalDate due, boolean done, String source) {
            this(id,activityId,null,title,notes,due,done?TaskStatus.DONE:TaskStatus.TODO,source,Instant.now(),0);
        }
        public Task {
            Objects.requireNonNull(id);
            Objects.requireNonNull(status);
            requireTime(createdAt);
            title = requireName(title,160,"task title");
            notes = Objects.requireNonNull(notes);
            source = Objects.requireNonNull(source);
            if (notes.length() > 4000 || source.length() > 160)
                throw new IllegalArgumentException("Task notes or source are too long.");
            if (order < 0) throw new IllegalArgumentException("Invalid task order.");
            // The tags in the order they were given, once each (#66). Null is
            // no tags, so a caller that passed "no tag" before tags were a list
            // still means what it meant.
            tagIds = tagIds == null ? List.of() : List.copyOf(new LinkedHashSet<>(tagIds));
            if (tagIds.size() > MAX_TAGS) throw new IllegalArgumentException("A task can carry at most " + MAX_TAGS + " tags.");
            // The pages a task links to, in the order they were linked, once each.
            pageIds = List.copyOf(new LinkedHashSet<>(Objects.requireNonNull(pageIds)));
            // A repeating task comes back on a date, so it has one (#57).
            history = history == null ? List.of() : List.copyOf(history);
            if (history.size() > MAX_HISTORY) throw new IllegalArgumentException("History limit reached.");
            if (repeat != null && due == null) throw new IllegalArgumentException("A repeating task needs a due date.");
            if (pageIds.size() > MAX_PAGES) throw new IllegalArgumentException("A task can link to at most " + MAX_PAGES + " pages.");
            details = details == null ? Details.NONE : details;
            if (details.dueTime() != null && due == null) throw new IllegalArgumentException("A due time needs a due date.");
        }
        /** The time of day it is due, or null for any time on its due date (#74). */
        public LocalTime dueTime() { return details.dueTime(); }
        public Priority priority() { return details.priority(); }
        public UUID statusId() { return details.statusId(); }
        public Map<UUID, Value> values() { return details.values(); }
        /** When it last changed, or when it was made for a task from before that was kept. */
        public Instant edited() { return details.editedAt() == null ? createdAt : details.editedAt(); }
        public Task withDetails(Details next) {
            return new Task(id,activityId,tagIds,title,notes,due,status,source,createdAt,order,plannedFor,pageIds,listId,repeat,history,next);
        }
        public Task withPriority(Priority next) { return withDetails(details.withPriority(next)); }
        public Task withValue(UUID property, Value value) { return withDetails(details.withValue(property, value)); }
        public boolean done() { return status==TaskStatus.DONE; }
        /**
         * Whether another task is the same entry: same title, same deadline, the
         * same activity link. The one rule for "already recorded", shared by the
         * batch add so a re-import cannot count the same assignment twice.
         */
        public boolean sameEntryAs(Task other) {
            return title.equalsIgnoreCase(other.title()) && Objects.equals(due, other.due())
                && Objects.equals(activityId, other.activityId());
        }
        /**
         * Whether an imported row is this task again.
         *
         * {@link #sameEntryAs} also compares the activity link, which an import
         * never sets: it records the class as a tag. Two classes that share an
         * assignment title and a deadline are different work, so a row filed
         * under a class is the same entry only as a task under that class.
         */
        public boolean sameImportEntryAs(Task other) {
            return sameEntryAs(other)
                && (tagIds.isEmpty() || other.tagIds().isEmpty() || !Collections.disjoint(tagIds, other.tagIds()));
        }
        /** The day this wants attention: the plan if there is one, else the deadline. */
        public LocalDate workOn() { return plannedFor != null ? plannedFor : due; }
        /** Planning to start it after it is due is worth saying out loud. */
        public boolean scheduledLate() { return plannedFor != null && due != null && plannedFor.isAfter(due); }

        // One field changed and everything else kept, page links included. Every
        // rebuild of an existing task goes through these rather than a
        // constructor, which is where a forgotten field used to go missing.
        /** Both task dates changed, with every unrelated property preserved. */
        public Task withDates(LocalDate deadline, LocalDate planned) {
            // No deadline, no time on it (#74).
            var kept=deadline==null?details.withDueTime(null):details;
            return new Task(id,activityId,tagIds,title,notes,deadline,status,source,createdAt,order,planned,pageIds,listId,repeat,history,kept);
        }
        /** Another group; the owner's status inside the old one does not come along (#68). */
        public Task withStatus(TaskStatus next) {
            var kept=next==status?details:details.withStatus(null);
            return new Task(id,activityId,tagIds,title,notes,due,next,source,createdAt,order,plannedFor,pageIds,listId,repeat,history,kept);
        }
        public Task withActivity(UUID next) {
            return new Task(id,next,tagIds,title,notes,due,status,source,createdAt,order,plannedFor,pageIds,listId,repeat,history,details);
        }
        public Task withTags(List<UUID> next) {
            return new Task(id,activityId,next,title,notes,due,status,source,createdAt,order,plannedFor,pageIds,listId,repeat,history,details);
        }
        /** The same task filed in another list, or in the Inbox for null (#56). */
        public Task withList(UUID next) {
            return new Task(id,activityId,tagIds,title,notes,due,status,source,createdAt,order,plannedFor,pageIds,next,repeat,history,details);
        }
        /** The same task with another rule, or none (#57); its history stays. */
        public Task withRepeat(Repeat next) {
            return new Task(id,activityId,tagIds,title,notes,due,status,source,createdAt,order,plannedFor,pageIds,listId,next,history,details);
        }
        /** The same task moved on: the occurrence behind it recorded, the next one due. */
        public Task advanced(Occurrence behind, LocalDate nextDue, TaskStatus nextStatus) {
            var kept = new ArrayList<>(history);
            kept.add(behind);
            // The next occurrence starts at its group's own status (#68).
            return new Task(id,activityId,tagIds,title,notes,nextDue,nextStatus,source,createdAt,order,null,pageIds,listId,repeat,kept,
                details.withStatus(null));
        }
        public boolean repeats() { return repeat != null; }
        /** The same task without one tag, which is what deleting a tag does to it. */
        public Task withoutTag(UUID tag) {
            return withTags(tagIds.stream().filter(t -> !t.equals(tag)).toList());
        }
        public Task withOrder(int next) {
            return new Task(id,activityId,tagIds,title,notes,due,status,source,createdAt,next,plannedFor,pageIds,listId,repeat,history,details);
        }
        public Task withPages(List<UUID> next) {
            return new Task(id,activityId,tagIds,title,notes,due,status,source,createdAt,order,plannedFor,next,listId,repeat,history,details);
        }
    }

    // ---------------------------------------------------------------------
    // Tasks as a database (#68): priority, the owner's own statuses, and
    // properties of their own, as Notion keeps them.

    /** How much a task matters, as a flag on its row. NONE is no flag at all. */
    public enum Priority {
        NONE("No priority"), LOW("Low"), MEDIUM("Medium"), HIGH("High"), URGENT("Urgent");
        public final String label;
        Priority(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    /**
     * What a task carries beyond its fixed fields (#68): its priority, which of
     * the owner's statuses it has inside its group, the values of the
     * properties the owner defined, and when it was last changed.
     *
     * One part rather than four more constructor arguments: later database
     * features add to this, not to every place a task is rebuilt.
     *
     * @param statusId one of the vault's {@link StatusOption}s, in the group the
     *                 task's {@link TaskStatus} names, or null for the group's
     *                 own status ("To do", "Doing", "Done").
     * @param editedAt when the task last changed, stamped by the tracker; null
     *                 for a task from before this was kept, which reads as its
     *                 creation.
     * @param dueTime  the time of day it is due, on its due date, or null for
     *                 any time that day (#74).
     */
    public record Details(Priority priority, UUID statusId, Map<UUID, Value> values, Instant editedAt, LocalTime dueTime) {
        /** The most property values one task keeps. */
        public static final int MAX_VALUES = 200;
        public static final Details NONE = new Details(Priority.NONE, null, Map.of(), null, null);
        /** Details with no due time: every task before #74. */
        public Details(Priority priority, UUID statusId, Map<UUID, Value> values, Instant editedAt) {
            this(priority, statusId, values, editedAt, null);
        }
        public Details {
            priority = priority == null ? Priority.NONE : priority;
            values = values == null ? Map.of() : Map.copyOf(values);
            if (values.size() > MAX_VALUES) throw new IllegalArgumentException("A task can hold at most " + MAX_VALUES + " property values.");
            for (var value : values.values()) Objects.requireNonNull(value);
            if (editedAt != null) requireTime(editedAt);
        }
        public Details withPriority(Priority next) { return new Details(next, statusId, values, editedAt, dueTime); }
        public Details withStatus(UUID next) { return new Details(priority, next, values, editedAt, dueTime); }
        public Details withEdited(Instant next) { return new Details(priority, statusId, values, next, dueTime); }
        /** Due at this time on the due date, or any time that day with null. Seconds are not kept. */
        public Details withDueTime(LocalTime next) {
            return new Details(priority, statusId, values, editedAt, next == null ? null : next.withSecond(0).withNano(0));
        }
        /** The same details with one property's value set, or cleared by null. */
        public Details withValue(UUID property, Value value) {
            var next = new HashMap<>(values);
            if (value == null) next.remove(Objects.requireNonNull(property)); else next.put(Objects.requireNonNull(property), value);
            return new Details(priority, statusId, next, editedAt, dueTime);
        }
    }

    /**
     * One property value on one task. An empty value is no value: a task
     * without one simply has no entry, and an unticked checkbox is not stored.
     */
    public sealed interface Value permits Value.Text, Value.Amount, Value.Choice, Value.Choices, Value.Day, Value.Tick {
        /** Text, or a link: one line or several, never blank. */
        record Text(String text) implements Value {
            public static final int MAX = 2_000;
            public Text {
                text = Objects.requireNonNull(text).strip();
                if (text.isEmpty() || text.length() > MAX) throw new IllegalArgumentException("Use text of 1–" + MAX + " characters.");
            }
        }
        /** A number, kept exactly as typed rather than as a binary fraction. */
        record Amount(java.math.BigDecimal amount) implements Value {
            private static final java.math.BigDecimal LIMIT = new java.math.BigDecimal("1e15");
            public Amount {
                Objects.requireNonNull(amount);
                if (amount.abs().compareTo(LIMIT) >= 0 || amount.stripTrailingZeros().scale() > 10)
                    throw new IllegalArgumentException("Use a number under a thousand trillion, with at most ten decimals.");
                // 2.50 and 2.5 are the same number, and must compare as one.
                amount = amount.signum() == 0 ? java.math.BigDecimal.ZERO : amount.stripTrailingZeros();
            }
        }
        /** One option of a select property. */
        record Choice(UUID option) implements Value {
            public Choice { Objects.requireNonNull(option); }
        }
        /** Some options of a multi-select property, in the order they were chosen. */
        record Choices(List<UUID> options) implements Value {
            public Choices {
                options = List.copyOf(new LinkedHashSet<>(options));
                if (options.isEmpty() || options.size() > Property.MAX_OPTIONS)
                    throw new IllegalArgumentException("Choose between one and " + Property.MAX_OPTIONS + " options.");
            }
        }
        /** A date. */
        record Day(LocalDate date) implements Value {
            public Day {
                Objects.requireNonNull(date);
                if (date.getYear() < 1900 || date.getYear() > 2199) throw new IllegalArgumentException("Choose a date between 1900 and 2199.");
            }
        }
        /** A ticked checkbox. */
        record Tick() implements Value { }
    }

    /** The kinds of property a task can have. The last two are read from the task, never typed in. */
    public enum PropertyType {
        TEXT("Text"), NUMBER("Number"), SELECT("Select"), MULTI_SELECT("Multi-select"), DATE("Date"),
        CHECKBOX("Checkbox"), URL("URL"), CREATED("Created time"), EDITED("Edited time");
        public final String label;
        PropertyType(String label) { this.label = label; }
        @Override public String toString() { return label; }
        /** Whether a task holds a value for it, rather than it being read from the task. */
        public boolean stored() { return this != CREATED && this != EDITED; }
        public boolean hasOptions() { return this == SELECT || this == MULTI_SELECT; }
    }

    /** One choice of a select or multi-select property. Colour is 24-bit RGB. */
    public record PropertyOption(UUID id, String name, int colour) {
        public PropertyOption {
            Objects.requireNonNull(id);
            // As long as a tag's name, which is how an option is drawn.
            name = requireName(name, 40, "option name");
            requireColour(colour);
        }
    }

    /**
     * A property the owner defined for tasks (#68): its name, its type, the
     * options a select offers, and whether it belongs to one list or to every
     * task. Its place among the others is its place in the vault's list.
     *
     * @param listId the list whose tasks show it, or null for every task.
     * @param hidden kept out of the table's columns; still on the task.
     */
    public record Property(UUID id, String name, PropertyType type, List<PropertyOption> options, UUID listId, boolean hidden) {
        public static final int MAX_OPTIONS = 100;
        public Property {
            Objects.requireNonNull(id);
            name = requireName(name, 60, "property name");
            Objects.requireNonNull(type);
            options = options == null ? List.of() : List.copyOf(options);
            if (!type.hasOptions() && !options.isEmpty()) throw new IllegalArgumentException("Only a select property has options.");
            if (options.size() > MAX_OPTIONS) throw new IllegalArgumentException("A property can offer at most " + MAX_OPTIONS + " options.");
            var ids = new HashSet<UUID>();
            var names = new HashSet<String>();
            for (var option : options) {
                if (!ids.add(option.id())) throw new IllegalArgumentException("Duplicate option.");
                if (!names.add(option.name().toLowerCase(Locale.ROOT)))
                    throw new IllegalArgumentException("\"" + name + "\" already offers \"" + option.name() + "\".");
            }
        }
        /** Whether a task may hold this value for this property. */
        public boolean accepts(Value value) {
            return switch (type) {
                case TEXT -> value instanceof Value.Text;
                case URL -> value instanceof Value.Text text && !text.text().contains("\n") && text.text().chars().noneMatch(Character::isWhitespace);
                case NUMBER -> value instanceof Value.Amount;
                case SELECT -> value instanceof Value.Choice choice && option(choice.option()) != null;
                case MULTI_SELECT -> value instanceof Value.Choices choices && choices.options().stream().allMatch(o -> option(o) != null);
                case DATE -> value instanceof Value.Day;
                case CHECKBOX -> value instanceof Value.Tick;
                case CREATED, EDITED -> false;
            };
        }
        public PropertyOption option(UUID id) {
            for (var option : options) if (option.id().equals(id)) return option;
            return null;
        }
        /** Whether it shows on a task filed in this list, or in the Inbox for null. */
        public boolean appliesTo(UUID taskList) { return listId == null || listId.equals(taskList); }
        public Property withName(String next) { return new Property(id, next, type, options, listId, hidden); }
        public Property withOptions(List<PropertyOption> next) { return new Property(id, name, type, next, listId, hidden); }
        public Property withList(UUID next) { return new Property(id, name, type, options, next, hidden); }
        public Property withHidden(boolean next) { return new Property(id, name, type, options, listId, next); }
        public Property withType(PropertyType next, List<PropertyOption> nextOptions) { return new Property(id, name, next, nextOptions, listId, hidden); }
    }

    /**
     * One of the owner's own statuses (#68), inside one of the three groups
     * every task board knows: "Waiting" inside To do, "Review" inside Doing.
     * The group's own status ("To do", "Doing", "Done") is always there too,
     * as the task with no status of its own.
     */
    public record StatusOption(UUID id, String name, TaskStatus group, int colour) {
        public StatusOption {
            Objects.requireNonNull(id);
            name = requireName(name, 40, "status name");
            Objects.requireNonNull(group);
            requireColour(colour);
        }
        public StatusOption withName(String next) { return new StatusOption(id, next, group, colour); }
        public StatusOption withColour(int next) { return new StatusOption(id, name, group, next); }
    }

    /**
     * How the owner has set up their tasks as a database (#68): their own
     * statuses and the properties tasks carry. Views, templates and projects
     * join this rather than each becoming another part of the vault's state.
     */
    public record TaskDatabase(List<StatusOption> statuses, List<Property> properties) {
        public static final TaskDatabase EMPTY = new TaskDatabase(List.of(), List.of());
        public static final int MAX_STATUSES = 60, MAX_PROPERTIES = 200;
        public TaskDatabase {
            statuses = statuses == null ? List.of() : List.copyOf(statuses);
            properties = properties == null ? List.of() : List.copyOf(properties);
            if (statuses.size() > MAX_STATUSES) throw new IllegalArgumentException("A vault can keep at most " + MAX_STATUSES + " statuses.");
            if (properties.size() > MAX_PROPERTIES) throw new IllegalArgumentException("A vault can keep at most " + MAX_PROPERTIES + " properties.");
            var ids = new HashSet<UUID>();
            var names = new HashSet<String>();
            for (var group : TaskStatus.values()) names.add(group.label.toLowerCase(Locale.ROOT));
            for (var status : statuses) {
                if (!ids.add(status.id())) throw new IllegalArgumentException("Duplicate status.");
                if (!names.add(status.name().toLowerCase(Locale.ROOT)))
                    throw new IllegalArgumentException("Another status is already called \"" + status.name() + "\".");
            }
            var propertyNames = new HashSet<String>();
            for (var property : properties) {
                if (!ids.add(property.id())) throw new IllegalArgumentException("Duplicate property.");
                if (!propertyNames.add(property.name().toLowerCase(Locale.ROOT)))
                    throw new IllegalArgumentException("Another property is already called \"" + property.name() + "\".");
            }
        }
        public StatusOption status(UUID id) {
            for (var status : statuses) if (status.id().equals(id)) return status;
            return null;
        }
        public Property property(UUID id) {
            for (var property : properties) if (property.id().equals(id)) return property;
            return null;
        }
        public TaskDatabase withStatuses(List<StatusOption> next) { return new TaskDatabase(next, properties); }
        public TaskDatabase withProperties(List<Property> next) { return new TaskDatabase(statuses, next); }
        /** What a task in this group can be set to: the group's own status first, then the owner's, in order. */
        public List<StatusOption> statusesIn(TaskStatus group) {
            return statuses.stream().filter(s -> s.group() == group).toList();
        }
    }

    public record Habit(UUID id, String name, HabitKind kind, String zone,
                        Set<LocalDate> checkIns, List<Instant> starts, LocalDate since) {
        /** Kept for callers that predate the day a habit began (#55). */
        public Habit(UUID id, String name, HabitKind kind, String zone, Set<LocalDate> checkIns, List<Instant> starts) {
            this(id, name, kind, zone, checkIns, starts, began(checkIns, starts, zone));
        }

        /**
         * The day a habit older than this field began: its first check-off, or
         * the day its first period started, or today for one with no history.
         *
         * Consistency counts the days since a habit began (#55), so a habit
         * from an older vault needs a beginning. The earliest thing it records
         * is the honest one: it cannot have been kept before that.
         */
        private static LocalDate began(Set<LocalDate> checkIns, List<Instant> starts, String zone) {
            var earliest = checkIns.stream().min(LocalDate::compareTo);
            if (earliest.isPresent()) return earliest.get();
            if (!starts.isEmpty()) return starts.getFirst().atZone(ZoneId.of(zone)).toLocalDate();
            return LocalDate.now(ZoneId.of(zone));
        }

        public Habit {
            Objects.requireNonNull(id); Objects.requireNonNull(kind);
            name = requireName(name,60,"name");
            ZoneId.of(zone); checkIns = Set.copyOf(checkIns); starts = onTheMinute(starts);
            if (checkIns.size() > 100_000 || starts.size() > 100_000) throw new IllegalArgumentException("History limit reached.");
            if (kind == HabitKind.DAILY && !starts.isEmpty() || kind == HabitKind.TIME_SINCE && (starts.isEmpty() || !checkIns.isEmpty()))
                throw new IllegalArgumentException("Invalid tracker history.");
            Objects.requireNonNull(since);
            starts.forEach(Model::requireTime);
            for (int i=1; i<starts.size(); i++) if (!starts.get(i).isAfter(starts.get(i-1)))
                throw new IllegalArgumentException("Restart must follow the previous start.");
        }
        /**
         * Every period starts on a whole minute (#50).
         *
         * A tracker restarted at 08:10:37 and another at 08:10:04 both read
         * "since 08:10" and then counted differently for ever, because the
         * seconds nobody was shown were still in the number. The rule lives
         * here so that no way of making or editing a period can miss it,
         * including the periods already in a vault, which are read through
         * this. Existing periods sharing a minute keep their exact starts as a
         * group, since rounding would collapse distinct historical periods.
         */
        private static List<Instant> onTheMinute(List<Instant> starts) {
            // Rounding must never turn an invalid imported history into a valid one.
            for (int i = 0; i < starts.size(); i++) {
                requireTime(starts.get(i));
                if (i > 0 && !starts.get(i).isAfter(starts.get(i - 1)))
                    throw new IllegalArgumentException("Restart must follow the previous start.");
            }
            var out = new ArrayList<Instant>(starts.size());
            for (int i = 0; i < starts.size(); i++) {
                var start = starts.get(i);
                var minute = start.truncatedTo(ChronoUnit.MINUTES);
                boolean collision = i > 0 && minute.equals(starts.get(i - 1).truncatedTo(ChronoUnit.MINUTES))
                    || i + 1 < starts.size() && minute.equals(starts.get(i + 1).truncatedTo(ChronoUnit.MINUTES));
                out.add(collision ? start : minute);
            }
            return List.copyOf(out);
        }

        public int streak(LocalDate today) {
            LocalDate day = checkIns.contains(today) ? today : today.minusDays(1);
            int count=0; while(checkIns.contains(day)) { count++; day=day.minusDays(1); }
            return count;
        }
    }

    /**
     * What a page or folder may be called.
     *
     * The same rules Obsidian applies to a file name, because a page is exported
     * as one: no path separators, and none of the characters link syntax
     * reserves (# heading, ^ block, | alias, [ ] the brackets themselves).
     */
    public static String requirePageName(String value, String what) {
        value = Objects.requireNonNull(value).strip();
        if (value.isEmpty() || value.length() > 200)
            throw new IllegalArgumentException("Use a " + what + " of 1–200 characters.");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c) || "/\\:#^[]|".indexOf(c) >= 0)
                throw new IllegalArgumentException("A " + what + " cannot contain / \\ : # ^ [ ] or |.");
        }
        if (value.equals(".") || value.equals("..") || value.startsWith("."))
            throw new IllegalArgumentException("A " + what + " cannot start with a dot.");
        return value;
    }

    /** A folder in the Pages tree. No parent means the top level; deletedAt means it is in the trash. */
    public record Folder(UUID id, UUID parentId, String name, Instant createdAt, Instant deletedAt) {
        public Folder {
            Objects.requireNonNull(id);
            name = requirePageName(name, "folder name");
            requireTime(createdAt);
            if (deletedAt != null) requireTime(deletedAt);
            if (id.equals(parentId)) throw new IllegalArgumentException("A folder cannot be inside itself.");
        }
        public boolean trashed() { return deletedAt != null; }
    }

    /**
     * A page: a title and its Markdown, kept exactly as written.
     *
     * The body is the whole text, frontmatter included. Links, headings and tags
     * are read out of it when they are needed and never stored beside it, so the
     * text is the only thing that can be true (docs/ARCHITECTURE.md, rule 3).
     */
    public record Page(UUID id, UUID folderId, String title, String body, Instant createdAt,
                       Instant updatedAt, Instant deletedAt) {
        /** About three hundred printed pages of text; the vault itself stops at 31 MB. */
        public static final int MAX_BODY = 1_000_000;
        public Page {
            Objects.requireNonNull(id);
            title = requirePageName(title, "page title");
            Objects.requireNonNull(body);
            if (body.length() > MAX_BODY) throw new IllegalArgumentException("This page is too long to save.");
            requireTime(createdAt);
            requireTime(updatedAt);
            if (deletedAt != null) requireTime(deletedAt);
        }
        public boolean trashed() { return deletedAt != null; }
        public Page withBody(String next, Instant when) { return new Page(id, folderId, title, next, createdAt, when, deletedAt); }
        public Page withTitle(String next, Instant when) { return new Page(id, folderId, next, body, createdAt, when, deletedAt); }
        public Page withFolder(UUID next, Instant when) { return new Page(id, next, title, body, createdAt, when, deletedAt); }
        public Page withDeletedAt(Instant when) { return new Page(id, folderId, title, body, createdAt, updatedAt, when); }
    }

    /**
     * Every folder and page, checked as one tree.
     *
     * Names are unique within a folder, ignoring case, among the things that are
     * not in the trash: two live pages called "Notes" in one folder could not be
     * exported side by side, and a link to either would be a guess. Something in
     * the trash keeps its name without reserving it. A live page or folder never
     * sits inside a trashed one, which is what lets a trashed folder take its
     * whole subtree with it and bring it back whole.
     */
    public record Notes(List<Folder> folders, List<Page> pages) {
        public Notes {
            folders = List.copyOf(folders);
            pages = List.copyOf(pages);
            if (folders.size() > 100_000 || pages.size() > 100_000)
                throw new IllegalArgumentException("Vault record limit reached.");
            var byId = new HashMap<UUID, Folder>();
            for (var f : folders) if (byId.put(f.id(), f) != null) throw new IllegalArgumentException("Duplicate folder.");
            for (var f : folders) {
                if (f.parentId() != null && !byId.containsKey(f.parentId()))
                    throw new IllegalArgumentException("A folder is inside a folder that does not exist.");
                // Walking up must reach the top: a cycle would never get there.
                var seen = new HashSet<UUID>();
                for (var at = f; at.parentId() != null; at = byId.get(at.parentId()))
                    if (!seen.add(at.id())) throw new IllegalArgumentException("A folder cannot be inside itself.");
                if (!f.trashed() && f.parentId() != null && byId.get(f.parentId()).trashed())
                    throw new IllegalArgumentException("A folder cannot be inside a folder in the trash.");
            }
            var pageIds = new HashSet<UUID>();
            for (var p : pages) {
                if (!pageIds.add(p.id())) throw new IllegalArgumentException("Duplicate page.");
                if (p.folderId() != null && !byId.containsKey(p.folderId()))
                    throw new IllegalArgumentException("A page is inside a folder that does not exist.");
                if (!p.trashed() && p.folderId() != null && byId.get(p.folderId()).trashed())
                    throw new IllegalArgumentException("A page cannot be inside a folder in the trash.");
            }
            var names = new HashSet<String>();
            for (var f : folders) if (!f.trashed() && !names.add("f" + f.parentId() + "/" + f.name().toLowerCase(Locale.ROOT)))
                throw new IllegalArgumentException("Another folder here is already called \"" + f.name() + "\".");
            for (var p : pages) if (!p.trashed() && !names.add("p" + p.folderId() + "/" + p.title().toLowerCase(Locale.ROOT)))
                throw new IllegalArgumentException("Another page here is already called \"" + p.title() + "\".");
        }
        public static Notes empty() { return new Notes(List.of(), List.of()); }
        public Optional<Page> page(UUID id) { return pages.stream().filter(p -> p.id().equals(id)).findFirst(); }
        public Optional<Folder> folder(UUID id) { return folders.stream().filter(f -> f.id().equals(id)).findFirst(); }
    }

    /**
     * The Anki integration, as the vault keeps it (#85).
     *
     * Off until it is switched on in Settings. The key is here rather than in
     * this computer's preferences because the vault is the encrypted thing, and
     * an API key is a secret: it travels with the workspace and never sits in
     * plain text. What is kept of Anki itself is counts and times — never a
     * card, a question or an answer.
     */
    public record Anki(boolean enabled, String key, boolean addsTime, int refreshMinutes, AnkiSnapshot last) {
        public Anki {
            key = Objects.requireNonNull(key);
            if (key.length() > 200) throw new IllegalArgumentException("That API key is too long.");
            if (refreshMinutes < 1 || refreshMinutes > 60)
                throw new IllegalArgumentException("Refresh between 1 and 60 minutes.");
        }
        public static Anki off() { return new Anki(false, "", true, 1, null); }
        public Anki withSnapshot(AnkiSnapshot next) { return new Anki(enabled, key, addsTime, refreshMinutes, next); }
        public Anki withKey(String next) { return new Anki(enabled, next, addsTime, refreshMinutes, last); }
        public Anki enabled(boolean on) { return new Anki(on, key, addsTime, refreshMinutes, last); }
    }

    /**
     * The last thing Anki said, so the card can show it while Anki is closed.
     *
     * Counts and times only: how many reviews today, how many on each of the
     * available review days, the profile they came from, and when they were read.
     */
    public record AnkiSnapshot(String profile, long today, Map<LocalDate, Long> days, Instant fetchedAt) {
        /** Match the vault record bound without silently truncating a long streak. */
        public static final int MAX_DAYS = 100_000;
        public AnkiSnapshot {
            profile = Objects.requireNonNull(profile);
            if (profile.length() > 200) throw new IllegalArgumentException("That profile name is too long.");
            if (today < 0) throw new IllegalArgumentException("A review count cannot be negative.");
            var kept = new TreeMap<LocalDate, Long>();
            for (var day : days.entrySet()) {
                if (day.getValue() == null || day.getValue() < 0) throw new IllegalArgumentException("A review count cannot be negative.");
                kept.put(day.getKey(), day.getValue());
            }
            if (kept.size() > MAX_DAYS) throw new IllegalArgumentException("Too many Anki review days.");
            days = Collections.unmodifiableSortedMap(kept);
            requireTime(fetchedAt);
        }
    }

    /**
     * Everything a vault holds.
     *
     * One canonical constructor with every part, and a wither for each, so
     * rebuilding the state can never quietly drop a part: a new field is a
     * compile error at every call site that forgot it, rather than a habit's
     * history or a page that silently vanishes.
     */
    public record State(List<Activity> activities, List<Session> sessions, List<ScheduleBlock> blocks,
                        List<RecurringBlock> recurring, List<Task> tasks, List<Habit> habits, List<Tag> tags,
                        Settings settings, Notes notes, Anki anki, List<TaskList> lists, TaskDatabase database) {
        /** The time-tracking core alone, with everything else empty. */
        public State(List<Activity> activities, List<Session> sessions, List<ScheduleBlock> blocks) {
            this(activities, sessions, blocks, List.of(), List.of(), List.of(), List.of(), Settings.defaults(),
                Notes.empty(), Anki.off(), List.of(), TaskDatabase.EMPTY);
        }

        public State {
            activities = List.copyOf(activities);
            sessions = List.copyOf(sessions);
            blocks = List.copyOf(blocks);
            recurring = List.copyOf(recurring);
            tasks = List.copyOf(tasks);
            habits = List.copyOf(habits);
            tags = List.copyOf(tags);
            lists = List.copyOf(lists);
            Objects.requireNonNull(settings);
            Objects.requireNonNull(anki);
            Objects.requireNonNull(notes);
            database = database == null ? TaskDatabase.EMPTY : database;
            if(habits.stream().map(Habit::id).distinct().count()!=habits.size()) throw new IllegalArgumentException("Duplicate habit.");
            if(tags.stream().map(Tag::id).distinct().count()!=tags.size()) throw new IllegalArgumentException("Duplicate tag.");
            if (recurring.size()>1_000) throw new IllegalArgumentException("Vault record limit reached.");
            if (activities.size()>100_000 || sessions.size()>100_000 || blocks.size()>100_000 || tasks.size()>100_000
                || habits.size()>100_000 || tags.size()>1_000)
                throw new IllegalArgumentException("Vault record limit reached.");
            Set<UUID> ids = new HashSet<>();
            for (var a : activities) if (!ids.add(a.id())) throw new IllegalArgumentException("Duplicate activity.");
            if (sessions.stream().filter(x -> x.end() == null).count() > 1)
                throw new IllegalArgumentException("Only one running session allowed.");
            for (var x : sessions) if (!ids.contains(x.activityId())) throw new IllegalArgumentException("Unknown session activity.");
            for (var b : blocks) if (!ids.contains(b.activityId())) throw new IllegalArgumentException("Unknown schedule activity.");
            for (var r : recurring) if (!ids.contains(r.activityId())) throw new IllegalArgumentException("Unknown repeating activity.");
            var recurringIds = new HashSet<UUID>();
            for (var r : recurring) if (!recurringIds.add(r.id())) throw new IllegalArgumentException("Duplicate repeating block.");
            // Overlap is checked per weekday: two Mondays clash, Monday and Tuesday cannot.
            for (var day : DayOfWeek.values())
                validateIntervals(recurring.stream().filter(r -> r.dayOfWeek()==day)
                    .map(r -> new Interval(r.id(),
                        LocalDate.EPOCH.atTime(r.startTime()).toInstant(java.time.ZoneOffset.UTC),
                        LocalDate.EPOCH.atTime(r.endTime()).toInstant(java.time.ZoneOffset.UTC))).toList());
            validateChangedWeeks(recurring);
            validateIntervals(sessions.stream().map(x -> new Interval(x.id(),x.start(),x.end())).toList());
            validateIntervals(blocks.stream().map(x -> new Interval(x.id(),x.start(),x.end())).toList());
            // Lists (#56): each once, each name once whatever its case, since
            // "School" and "school" in one sidebar could only be a mistake.
            if (lists.size() > 1_000) throw new IllegalArgumentException("Vault record limit reached.");
            var listIds = new HashSet<UUID>();
            var listNames = new HashSet<String>();
            for (var l : lists) {
                if (!listIds.add(l.id())) throw new IllegalArgumentException("Duplicate list.");
                if (!listNames.add(l.name().toLowerCase(Locale.ROOT)))
                    throw new IllegalArgumentException("Another list is already called \"" + l.name() + "\".");
            }
            var tagIds = new HashSet<UUID>(); tags.forEach(t->tagIds.add(t.id()));
            Set<UUID> taskIds = new HashSet<>();
            for (var t : tasks) {
                if (!taskIds.add(t.id())) throw new IllegalArgumentException("Duplicate task.");
                if (t.activityId() != null && !ids.contains(t.activityId())) throw new IllegalArgumentException("Unknown task activity.");
                for (var tag : t.tagIds()) if (!tagIds.contains(tag)) throw new IllegalArgumentException("Unknown task tag.");
                if (t.listId() != null && !listIds.contains(t.listId())) throw new IllegalArgumentException("A task is in a list that does not exist.");
            }
            // A task may link to a page in the trash, so restoring the page
            // restores the link; it may not link to one that is gone for good.
            var pageIds = new HashSet<UUID>();
            for (var p : notes.pages()) pageIds.add(p.id());
            for (var t : tasks) for (var page : t.pageIds())
                if (!pageIds.contains(page)) throw new IllegalArgumentException("A task links to a page that does not exist.");
            // The database (#68): a property belongs to a list that exists, a
            // task's own status is one of the vault's and in the task's group,
            // and every value is one its property accepts.
            for (var property : database.properties())
                if (property.listId() != null && !listIds.contains(property.listId()))
                    throw new IllegalArgumentException("A property belongs to a list that does not exist.");
            for (var t : tasks) {
                if (t.statusId() != null) {
                    var own = database.status(t.statusId());
                    if (own == null) throw new IllegalArgumentException("A task has a status that does not exist.");
                    if (own.group() != t.status())
                        throw new IllegalArgumentException("\"" + own.name() + "\" is not a " + t.status().label + " status.");
                }
                for (var value : t.values().entrySet()) {
                    var property = database.property(value.getKey());
                    if (property == null) throw new IllegalArgumentException("A task has a value for a property that does not exist.");
                    if (!property.accepts(value.getValue()))
                        throw new IllegalArgumentException("\"" + property.name() + "\" cannot hold that value.");
                }
            }
        }
        /**
         * The days a changed week touches, checked the way the rules are (#59).
         *
         * The rules cannot clash on a weekday, but one week's block held at
         * another time, or on another day, can land on a block that follows its
         * rule. So every day a change reaches is laid out as it will be drawn —
         * the rules' own blocks less the weeks moved away or skipped, plus the
         * blocks moved onto it — and refused where two overlap, as a clash on
         * the template is.
         */
        private static void validateChangedWeeks(List<RecurringBlock> recurring) {
            int total = 0;
            var days = new TreeSet<LocalDate>();
            for (var r : recurring) {
                total += r.changes().size();
                for (var c : r.changes()) { days.add(c.date()); if (!c.skipped()) days.add(c.movedTo()); }
            }
            if (total > 10_000) throw new IllegalArgumentException("Vault record limit reached.");
            for (var day : days) {
                var held = new ArrayList<Interval>();
                for (var r : recurring) {
                    if (r.dayOfWeek() == day.getDayOfWeek() && r.changeOn(day) == null)
                        held.add(onDay(r.startTime(), r.endTime()));
                    for (var c : r.changes())
                        if (!c.skipped() && c.movedTo().equals(day)) held.add(onDay(c.start(), c.end()));
                }
                validateIntervals(held);
            }
        }
        /** A span of one day. Its own id: two weeks of one rule can both be moved onto the same day. */
        private static Interval onDay(LocalTime start, LocalTime end) {
            return new Interval(UUID.randomUUID(), LocalDate.EPOCH.atTime(start).toInstant(java.time.ZoneOffset.UTC),
                LocalDate.EPOCH.atTime(end).toInstant(java.time.ZoneOffset.UTC));
        }
        /**
         * Removes one activity, keeping or deleting the time recorded under it.
         *
         * Identity decides everything: the activity is matched by id, and every
         * record is matched by the id it stores rather than by the label, so a
         * renamed activity can never lose its history and two activities that
         * share a name can never be confused for one another.
         *
         * Planned blocks, weekly repeats and tasks are never deleted with it —
         * deleting an activity is not a decision to throw away work that is
         * still ahead of you. They move to a single {@link Model#UNCATEGORIZED}
         * bucket, created on demand and reused if it already exists. Sessions
         * either move there too (keepTime) or go with the activity.
         *
         *
         * Refused when records still point at the bucket itself: there would be
         * nowhere to put them.
         */
        public State withoutActivity(UUID activityId, boolean keepTime) {
            Objects.requireNonNull(activityId);
            var target = activities.stream().filter(a -> a.id().equals(activityId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("That activity no longer exists."));
            boolean hasSessions = sessions.stream().anyMatch(s -> s.activityId().equals(activityId));
            boolean hasBlocks = blocks.stream().anyMatch(b -> b.activityId().equals(activityId));
            boolean hasRepeats = recurring.stream().anyMatch(r -> r.activityId().equals(activityId));
            boolean hasTasks = tasks.stream().anyMatch(t -> activityId.equals(t.activityId()));
            boolean needsBucket = keepTime && hasSessions || hasBlocks || hasRepeats || hasTasks;
            var kept = new ArrayList<>(activities.stream().filter(a -> !a.id().equals(activityId)).toList());
            UUID bucket = null;
            if (needsBucket) {
                var existing = kept.stream().filter(a -> a.name().equalsIgnoreCase(UNCATEGORIZED)).findFirst();
                if (existing.isPresent()) bucket = existing.get().id();
                else {
                    if (target.name().equalsIgnoreCase(UNCATEGORIZED))
                        throw new IllegalArgumentException("Rename \"" + target.name()
                            + "\" first: records still point at it, and " + UNCATEGORIZED
                            + " is already its name.");
                    var created = new Activity(UUID.randomUUID(), UNCATEGORIZED, 0);
                    kept.add(created);
                    bucket = created.id();
                }
            }
            final UUID replacement = bucket;
            var nextSessions = keepTime
                ? sessions.stream().map(s -> s.activityId().equals(activityId)
                    ? new Session(s.id(), replacement, s.start(), s.end()) : s).toList()
                : sessions.stream().filter(s -> !s.activityId().equals(activityId)).toList();
            var nextBlocks = blocks.stream().map(b -> b.activityId().equals(activityId)
                ? new ScheduleBlock(b.id(), replacement, b.start(), b.end()) : b).toList();
            var nextRepeats = recurring.stream().map(r -> r.activityId().equals(activityId)
                ? r.withActivity(replacement) : r).toList();
            var nextTasks = tasks.stream().map(t -> activityId.equals(t.activityId())
                ? t.withActivity(replacement) : t).toList();
            return new State(kept, nextSessions, nextBlocks, nextRepeats, nextTasks, habits, tags,
                settings, notes, anki, lists, database);
        }

        public State withCore(List<Activity> a, List<Session> s, List<ScheduleBlock> b) {
            return new State(a,s,b,recurring,tasks,habits,tags,settings,notes,anki,lists,database);
        }
        public State withTasks(List<Task> next) { return new State(activities,sessions,blocks,recurring,next,habits,tags,settings,notes,anki,lists,database); }
        public State withHabits(List<Habit> next) { return new State(activities,sessions,blocks,recurring,tasks,next,tags,settings,notes,anki,lists,database); }
        public State withTags(List<Tag> next) { return new State(activities,sessions,blocks,recurring,tasks,habits,next,settings,notes,anki,lists,database); }
        public State withSettings(Settings next) { return new State(activities,sessions,blocks,recurring,tasks,habits,tags,next,notes,anki,lists,database); }
        public State withRecurring(List<RecurringBlock> next) { return new State(activities,sessions,blocks,next,tasks,habits,tags,settings,notes,anki,lists,database); }
        public State withNotes(Notes next) { return new State(activities,sessions,blocks,recurring,tasks,habits,tags,settings,next,anki,lists,database); }
        public State withAnki(Anki next) { return new State(activities,sessions,blocks,recurring,tasks,habits,tags,settings,notes,next,lists,database); }
        public State withLists(List<TaskList> next) { return new State(activities,sessions,blocks,recurring,tasks,habits,tags,settings,notes,anki,next,database); }
        /** Tasks and the database at once, for a change neither is valid without the other: a property's new type and its converted values. */
        public State withTasks(List<Task> nextTasks, TaskDatabase nextDatabase) { return new State(activities,sessions,blocks,recurring,nextTasks,habits,tags,settings,notes,anki,lists,nextDatabase); }
        public State withDatabase(TaskDatabase next) { return new State(activities,sessions,blocks,recurring,tasks,habits,tags,settings,notes,anki,lists,next); }
        public static State empty() { return new State(List.of(), List.of(), List.of()); }
    }
}
