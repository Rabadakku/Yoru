package dev.yoru.domain;
import java.time.*;
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
    private static void requireTime(Instant time) {
        Objects.requireNonNull(time);
        if(time.isBefore(Instant.parse("1900-01-01T00:00:00Z")) || !time.isBefore(Instant.parse("2200-01-01T00:00:00Z")))
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
    /** Overworld sprite set. Not called Character — that shadows java.lang.Character. */
    public enum TrainerId { BRENDAN, MAY }
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

    public record Settings(ThemeId theme, TrainerId trainer, int dailyGoalHours, int minSessionSeconds,
                           DayOfWeek weekStartsOn) {
        /** Kept for callers that predate the week-start preference. */
        public Settings(ThemeId theme, TrainerId trainer, int dailyGoalHours, int minSessionSeconds) {
            this(theme,trainer,dailyGoalHours,minSessionSeconds,DayOfWeek.MONDAY);
        }
        public Settings {
            Objects.requireNonNull(theme); Objects.requireNonNull(trainer);
            Objects.requireNonNull(weekStartsOn);
            if(dailyGoalHours<1 || dailyGoalHours>16)
                throw new IllegalArgumentException("Daily goal must be between 1 and 16 hours.");
            if(minSessionSeconds<0 || minSessionSeconds>3600)
                throw new IllegalArgumentException("Minimum session must be between 0 and 60 minutes.");
        }
        public static Settings defaults() { return new Settings(ThemeId.MIDNIGHT,TrainerId.BRENDAN,4,300,DayOfWeek.MONDAY); }
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
        public String toString() { return name; }
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
                                 LocalTime startTime, LocalTime endTime) {
        public RecurringBlock {
            Objects.requireNonNull(id);
            Objects.requireNonNull(activityId);
            Objects.requireNonNull(dayOfWeek);
            Objects.requireNonNull(startTime);
            Objects.requireNonNull(endTime);
            if(!endTime.isAfter(startTime))
                throw new IllegalArgumentException("A repeating block must end after it starts, on the same day.");
        }
        public Duration length() { return Duration.between(startTime,endTime); }
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
    public record Task(UUID id, UUID activityId, UUID tagId, String title, String notes,
                       LocalDate due, TaskStatus status, String source, Instant createdAt, int order,
                       LocalDate plannedFor) {
        /** Predates the planned/deadline split; everything before it planned nothing. */
        public Task(UUID id, UUID activityId, UUID tagId, String title, String notes,
                    LocalDate due, TaskStatus status, String source, Instant createdAt, int order) {
            this(id,activityId,tagId,title,notes,due,status,source,createdAt,order,null);
        }
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
        }
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
        /** The day this wants attention: the plan if there is one, else the deadline. */
        public LocalDate workOn() { return plannedFor != null ? plannedFor : due; }
        /** Planning to start it after it is due is worth saying out loud. */
        public boolean scheduledLate() { return plannedFor != null && due != null && plannedFor.isAfter(due); }

        public Task withStatus(TaskStatus next) {
            return new Task(id,activityId,tagId,title,notes,due,next,source,createdAt,order,plannedFor);
        }
    }

    /**
     * A Pokémon that study time has earned for the game.
     *
     * The id is the reward's identity, and it survives into the delivered
     * Pokémon's personality value, so the game save itself records what has been
     * handed over and a delivery can never be counted twice.
     *
     * deliveredAt being null is the whole state machine. There is no separate
     * flag to disagree with it, and no ordering in which a reward can be both.
     */
    public record Reward(UUID id, int nationalDex, int level, Instant earnedAt, Instant deliveredAt) {
        public Reward {
            Objects.requireNonNull(id);
            requireTime(earnedAt);
            if (deliveredAt != null) requireTime(deliveredAt);
            if (nationalDex < 1 || nationalDex > SPECIES_COUNT)
                throw new IllegalArgumentException("Unknown species.");
            if (level < 1 || level > 100) throw new IllegalArgumentException("Invalid level.");
        }
        public boolean delivered() { return deliveredAt != null; }
        public Reward deliveredAt(Instant when) {
            return new Reward(id, nationalDex, level, earnedAt, when);
        }
    }

    /** Generation I–III National Dex. Widening this leaves existing data valid. */
    public static final int SPECIES_COUNT = 386;

    /**
     * How far the study encounters have got.
     *
     * The seed fixes every encounter in advance: encounter n always has the same
     * id, and the id decides what it turns out to be. Reopening the vault, or
     * closing the dialog without catching, can never re-roll an encounter into
     * something better. encountersUsed counts the ones opened; rewardedSeconds
     * is the study time they have used up.
     */
    public record Campaign(long seed, long encountersUsed, long rewardedSeconds) {
        public Campaign {
            if (encountersUsed < 0 || encountersUsed > 1_000_000)
                throw new IllegalArgumentException("Invalid encounter count.");
            if (rewardedSeconds < 0) throw new IllegalArgumentException("Invalid encounter progress.");
        }
        public static Campaign start(long seed) { return new Campaign(seed, 0, 0); }
        /** The id encounter n will always have. */
        public UUID encounterId(long n) {
            return UUID.nameUUIDFromBytes(java.nio.ByteBuffer.allocate(16).putLong(seed).putLong(n).array());
        }
        /** The id of the encounter that opens next. */
        public UUID nextEncounter() { return encounterId(encountersUsed); }
        public Campaign encounterUsed(long seconds) {
            return new Campaign(seed, encountersUsed + 1, rewardedSeconds + seconds);
        }
        public Campaign withRewardedSeconds(long seconds) { return new Campaign(seed, encountersUsed, seconds); }
    }

    /**
     * The game's own battery save, kept in the vault (#40).
     *
     * Exactly the bytes the game wrote. Holding them here rather than in a file
     * beside the vault makes one vault one game: it is encrypted, backed up,
     * exported and reset with everything else, and a second vault is a second
     * playthrough rather than a second view of the same one.
     *
     * A class rather than a record so the bytes are copied on the way in and out
     * and compared by content; a record would hand out the array itself and
     * compare it by identity.
     */
    public static final class GameSave {
        /** Emerald writes 128 KiB; anything past a megabyte is not a Game Boy Advance save. */
        public static final int MAX_BYTES = 1 << 20;
        private final byte[] bytes;
        private final Instant updatedAt;

        public GameSave(byte[] bytes, Instant updatedAt) {
            Objects.requireNonNull(bytes);
            if (bytes.length == 0 || bytes.length > MAX_BYTES) throw new IllegalArgumentException("That is not a game save.");
            requireTime(updatedAt);
            this.bytes = bytes.clone();
            this.updatedAt = updatedAt;
        }
        public byte[] bytes() { return bytes.clone(); }
        public int size() { return bytes.length; }
        public Instant updatedAt() { return updatedAt; }
        /** Whether these are exactly the bytes this save holds. */
        public boolean holds(byte[] other) { return Arrays.equals(bytes, other); }
        @Override public boolean equals(Object o) {
            return o instanceof GameSave other && updatedAt.equals(other.updatedAt) && Arrays.equals(bytes, other.bytes);
        }
        @Override public int hashCode() { return 31 * Arrays.hashCode(bytes) + updatedAt.hashCode(); }
        @Override public String toString() { return "GameSave[" + bytes.length + " bytes, " + updatedAt + "]"; }
    }

    public record Habit(UUID id, String name, HabitKind kind, String zone,
                        Set<LocalDate> checkIns, List<Instant> starts) {
        public Habit {
            Objects.requireNonNull(id); Objects.requireNonNull(kind);
            name = requireName(name,60,"name");
            ZoneId.of(zone); checkIns = Set.copyOf(checkIns); starts = List.copyOf(starts);
            if (checkIns.size() > 100_000 || starts.size() > 100_000) throw new IllegalArgumentException("History limit reached.");
            if (kind == HabitKind.DAILY && !starts.isEmpty() || kind == HabitKind.TIME_SINCE && (starts.isEmpty() || !checkIns.isEmpty()))
                throw new IllegalArgumentException("Invalid tracker history.");
            starts.forEach(Model::requireTime);
            for (int i=1; i<starts.size(); i++) if (!starts.get(i).isAfter(starts.get(i-1)))
                throw new IllegalArgumentException("Restart must follow the previous start.");
        }
        public int streak(LocalDate today) {
            LocalDate day = checkIns.contains(today) ? today : today.minusDays(1);
            int count=0; while(checkIns.contains(day)) { count++; day=day.minusDays(1); }
            return count;
        }
    }

    /**
     * Everything a vault holds.
     *
     * One canonical constructor with every part, and a wither for each, so
     * rebuilding the state can never quietly drop a part: a new field is a
     * compile error at every call site that forgot it, rather than study credit
     * or a game save that silently vanishes.
     *
     * game is null until the game has been played or a save brought in.
     */
    public record State(List<Activity> activities, List<Session> sessions, List<ScheduleBlock> blocks,
                        List<RecurringBlock> recurring, List<Task> tasks, List<Habit> habits, List<Tag> tags,
                        Settings settings, Campaign campaign, List<Reward> rewards, GameSave game) {
        /** The time-tracking core alone, with everything else empty. */
        public State(List<Activity> activities, List<Session> sessions, List<ScheduleBlock> blocks) {
            this(activities, sessions, blocks, List.of(), List.of(), List.of(), List.of(), Settings.defaults(),
                Campaign.start(0), List.of(), null);
        }
        public State {
            activities = List.copyOf(activities);
            sessions = List.copyOf(sessions);
            blocks = List.copyOf(blocks);
            recurring = List.copyOf(recurring);
            tasks = List.copyOf(tasks);
            habits = List.copyOf(habits);
            tags = List.copyOf(tags);
            rewards = List.copyOf(rewards);
            Objects.requireNonNull(settings);
            Objects.requireNonNull(campaign);
            if (rewards.stream().map(Reward::id).distinct().count() != rewards.size())
                throw new IllegalArgumentException("Duplicate reward.");
            if(habits.stream().map(Habit::id).distinct().count()!=habits.size()) throw new IllegalArgumentException("Duplicate habit.");
            if(tags.stream().map(Tag::id).distinct().count()!=tags.size()) throw new IllegalArgumentException("Duplicate tag.");
            if (recurring.size()>1_000) throw new IllegalArgumentException("Vault record limit reached.");
            if (activities.size()>100_000 || sessions.size()>100_000 || blocks.size()>100_000 || tasks.size()>100_000
                || habits.size()>100_000 || tags.size()>1_000 || rewards.size()>100_000)
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
            validateIntervals(sessions.stream().map(x -> new Interval(x.id(),x.start(),x.end())).toList());
            validateIntervals(blocks.stream().map(x -> new Interval(x.id(),x.start(),x.end())).toList());
            var tagIds = new HashSet<UUID>(); tags.forEach(t->tagIds.add(t.id()));
            Set<UUID> taskIds = new HashSet<>();
            for (var t : tasks) {
                if (!taskIds.add(t.id())) throw new IllegalArgumentException("Duplicate task.");
                if (t.activityId() != null && !ids.contains(t.activityId())) throw new IllegalArgumentException("Unknown task activity.");
                if (t.tagId() != null && !tagIds.contains(t.tagId())) throw new IllegalArgumentException("Unknown task tag.");
            }
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
         * The campaign and the reward ledger are untouched. Encounter credit is
         * derived from the sessions at read time, so deleting time cannot mint a
         * new encounter, and an encounter already opened stays opened because
         * deleting recorded time is not a decision to un-earn it.
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
                ? new RecurringBlock(r.id(), replacement, r.dayOfWeek(), r.startTime(), r.endTime()) : r).toList();
            var nextTasks = tasks.stream().map(t -> activityId.equals(t.activityId())
                ? new Task(t.id(), replacement, t.tagId(), t.title(), t.notes(), t.due(), t.status(),
                    t.source(), t.createdAt(), t.order(), t.plannedFor())
                : t).toList();
            return new State(kept, nextSessions, nextBlocks, nextRepeats, nextTasks, habits, tags,
                settings, campaign, rewards, game);
        }

        public State withCore(List<Activity> a, List<Session> s, List<ScheduleBlock> b) {
            return new State(a,s,b,recurring,tasks,habits,tags,settings,campaign,rewards,game);
        }
        public State withTasks(List<Task> next) { return new State(activities,sessions,blocks,recurring,next,habits,tags,settings,campaign,rewards,game); }
        public State withHabits(List<Habit> next) { return new State(activities,sessions,blocks,recurring,tasks,next,tags,settings,campaign,rewards,game); }
        public State withTags(List<Tag> next) { return new State(activities,sessions,blocks,recurring,tasks,habits,next,settings,campaign,rewards,game); }
        public State withSettings(Settings next) { return new State(activities,sessions,blocks,recurring,tasks,habits,tags,next,campaign,rewards,game); }
        public State withRecurring(List<RecurringBlock> next) { return new State(activities,sessions,blocks,next,tasks,habits,tags,settings,campaign,rewards,game); }
        public State withCampaign(Campaign next) { return new State(activities,sessions,blocks,recurring,tasks,habits,tags,settings,next,rewards,game); }
        public State withRewards(List<Reward> next) { return new State(activities,sessions,blocks,recurring,tasks,habits,tags,settings,campaign,next,game); }
        public State withGame(GameSave next) { return new State(activities,sessions,blocks,recurring,tasks,habits,tags,settings,campaign,rewards,next); }
        /** Rewards earned for the real game and not yet recorded as delivered. */
        public List<Reward> pendingRewards() { return rewards.stream().filter(r -> !r.delivered()).toList(); }
        public static State empty() { return new State(List.of(), List.of(), List.of()); }
    }
}
