package dev.yoru.application;
import dev.yoru.domain.Model.*;
import java.io.*;
import java.time.*;
import java.util.*;
public final class Tracker {
    private Repository repository;
    private final Clock clock;
    private State state;
    /** The save the last editSave wrote: a run of edits is backed up once, before its first. */
    private GameSave lastEdit;
    public Tracker(Repository repository, Clock clock) throws IOException {
        this.repository=repository;
        this.clock=clock;
        state=repository.load();
    }
    public State state() {
        return state;
    }
    /** The tracker's own clock, so the UI and tests agree on "now" instead of each asking the wall. */
    public Instant now() {
        return clock.instant();
    }
    /** Package-private so services beside the tracker (Pages) save through the same one path. */
    void commit(State next) throws IOException {
        repository.save(next);
        state=next;
    }
    /** The whole-vault backup a destructive change takes first. */
    void backup() throws IOException {
        repository.backup();
    }
    private final Pages pages=new Pages(this);
    /** Pages and folders: the one way to change them (#46). */
    public Pages pages() {
        return pages;
    }
    /**
     * Replaces the whole vault in one write, for importing a portable export.
     *
     * Takes an already-built State: the caller parses and validates first, so a
     * file that is wrong in its last record never reaches this method and can
     * never leave the vault half-replaced. commit still writes before it
     * publishes, so a failed save leaves the running app exactly as it was.
     *
     * The vault as it was is backed up first, like every other whole-vault
     * write: an import replaces everything, and the backup is the only copy of
     * what it replaced.
     */
    public void restore(State next) throws IOException {
        Objects.requireNonNull(next, "Nothing to restore.");
        repository.backup();
        commit(next);
        // A restored vault is a different vault: its game save was not the one
        // last edited, so the next edit is backed up rather than assumed covered.
        lastEdit=null;
    }

    public Session active() {
        return state.sessions().stream().filter(s->s.end()==null).findFirst().orElse(null);
    }

    /**
     * Moves this tracker onto another vault, or leaves it exactly where it was.
     *
     * The next vault is loaded in full before the one in use is let go, so a
     * vault that will not open — a wrong password, a file that is not there —
     * costs nothing: the tracker is still on the vault it had, and the vault
     * that would not open is released rather than left locked. The vault left
     * behind is closed, which is what gives up its lock.
     *
     * The caller stops the game first: it holds the save of the vault it was
     * playing, and would write that save into whichever vault came next.
     */
    public void switchTo(Repository next) throws IOException {
        Objects.requireNonNull(next,"There is no vault to switch to.");
        State loaded;
        try {
            loaded=next.load();
        }
        catch(IOException|RuntimeException e) {
            release(next);
            throw e;
        }
        var previous=repository;
        repository=next;
        state=loaded;
        // The closed vault's decrypted game save is not this vault's business,
        // and up to a megabyte of it stayed reachable through this field.
        lastEdit=null;
        release(previous);
    }

    /**
     * Gives up a vault's lock, and says nothing when that fails: the data is
     * already where it should be, and a lock that will not release is the
     * operating system's to clean up, not something to fail a switch over.
     */
    private static void release(Repository vault) {
        try {
            vault.close();
        }
        catch(IOException ignored) {
        }
    }
    private void requireActivity(UUID id) {
        if(state.activities().stream().noneMatch(a->a.id().equals(id))) throw new IllegalArgumentException("Choose an activity first.");
    }
    public void addActivity(String name,int target) throws IOException {
        if(state.activities().stream().anyMatch(a->a.name().equalsIgnoreCase(name.strip()))) throw new IllegalArgumentException("That activity already exists.");
        var list=new ArrayList<>(state.activities());
        list.add(new Activity(UUID.randomUUID(),name,target));
        commit(state.withCore(list,state.sessions(),state.blocks()));
    }

    /**
     * What removing an activity would touch, counted before it happens.
     *
     * Counted from the records themselves, by id, so the number the confirmation
     * quotes is exactly the number the removal acts on.
     */
    public record ActivityUsage(int sessions, long seconds, int blocks, int repeats, int tasks) { }

    public ActivityUsage usage(UUID id) {
        requireActivity(id);
        Instant now=clock.instant();
        int sessions=0;
        long seconds=0;
        for(var s:state.sessions()) if(s.activityId().equals(id)) { sessions++; seconds+=s.seconds(now); }
        int blocks=0;
        for(var b:state.blocks()) if(b.activityId().equals(id)) blocks++;
        int repeats=0;
        for(var r:state.recurring()) if(r.activityId().equals(id)) repeats++;
        int tasks=0;
        for(var t:state.tasks()) if(id.equals(t.activityId())) tasks++;
        return new ActivityUsage(sessions,seconds,blocks,repeats,tasks);
    }

    /**
     * Renames an activity, by identity.
     *
     * The label is the only thing that changes: every session, block, weekly
     * repeat and task keeps pointing at the same id, so renaming can never move
     * or lose recorded time, and the reward ledger is not read at all. The name
     * is validated by Activity first,
     * so a blank or overlong one is refused before anything is written, and
     * nothing is written at all when the label is already the one asked for.
     *
     * The vault is backed up first, like a reset: this is one whole-vault write,
     * and the backup is what a failed write is recovered from.
     */
    public void renameActivity(UUID id,String name) throws IOException {
        var existing=activity(id);
        var renamed=existing.renamed(name);
        if(renamed.name().equals(existing.name())) return;
        if(state.activities().stream().anyMatch(a->!a.id().equals(id)&&a.name().equalsIgnoreCase(renamed.name())))
            throw new IllegalArgumentException("Another activity is already called \""+renamed.name()+"\".");
        var list=new ArrayList<>(state.activities());
        list.set(list.indexOf(existing),renamed);
        repository.backup();
        commit(state.withCore(list,state.sessions(),state.blocks()));
    }

    /**
     * Changes an activity's daily target, by identity (#21).
     *
     * Like a rename, only the activity record changes: no session, block, repeat
     * or task is touched. Validated by Activity first, backed up like a rename,
     * and nothing is written when the target is already the one asked for.
     */
    public void retargetActivity(UUID id,int targetMinutes) throws IOException {
        var existing=activity(id);
        var retargeted=existing.retargeted(targetMinutes);
        if(retargeted.equals(existing)) return;
        var list=new ArrayList<>(state.activities());
        list.set(list.indexOf(existing),retargeted);
        repository.backup();
        commit(state.withCore(list,state.sessions(),state.blocks()));
    }

    /**
     * Removes an activity, keeping its recorded time under
     * {@link dev.yoru.domain.Model#UNCATEGORIZED} or deleting it along with the
     * activity.
     *
     * Refused while this activity is the one being timed, because the running
     * session has no end yet and deleting the activity under it would leave a
     * timer pointing at nothing. Refused before the backup is taken, so a
     * declined removal leaves no trace at all.
     */
    public void removeActivity(UUID id,boolean keepTime) throws IOException {
        var target=activity(id);
        var running=active();
        if(running!=null&&running.activityId().equals(id))
            throw new IllegalArgumentException("Clock out before removing \""+target.name()+"\": it is timing right now.");
        // Built and validated first: a refusal must not leave a backup behind.
        var next=state.withoutActivity(id,keepTime);
        repository.backup();
        commit(next);
    }
    public void start(UUID id) throws IOException {
        requireActivity(id);
        if(active()!=null)throw new IllegalArgumentException("Clock out before starting another session.");
        var list=new ArrayList<>(state.sessions());
        list.add(new Session(UUID.randomUUID(),id,clock.instant(),null));
        commit(state.withCore(state.activities(),list,state.blocks()));
    }
    /**
     * Ends the running session.
     *
     * Returns false when the session was under the minimum and was discarded
     * rather than stored. Below the floor a session counted for nothing anyway —
     * not toward totals, not toward encounters — so keeping it only produced a
     * row that disagreed with every number beside it.
     *
     * This is the one path that discards on its own, because it is the one the
     * clock takes without being asked. Deliberate entry — log and editSession —
     * refuses instead, so nobody types a short session and watches it disappear.
     */
    public boolean stop(Instant end) throws IOException {
        var current=active();
        if(current==null)throw new IllegalArgumentException("No running session.");
        if(end.isAfter(clock.instant())) throw new IllegalArgumentException("End cannot be in the future.");
        var list=new ArrayList<>(state.sessions());
        // Built first so the record's own validation runs before the floor does.
        // Checking the floor first turned "you clocked out before you clocked in"
        // into a silent delete, which CoreTest caught.
        var finished=new Session(current.id(),current.activityId(),current.start(),end);
        if(Analytics.tooShort(state,current.start(),end)) {
            list.remove(current);
            commit(state.withCore(state.activities(),list,state.blocks()));
            return false;
        }
        list.set(list.indexOf(current),finished);
        commit(state.withCore(state.activities(),list,state.blocks()));
        return true;
    }

    /** Minutes, for messages that have to explain the floor to somebody. */
    private String floor() {
        int minutes=state.settings().minSessionSeconds()/60;
        return minutes<=1?"a minute":minutes+" minutes";
    }

    private void requireLongEnough(Instant start,Instant end) {
        if(end==null) return;
        if(Analytics.tooShort(state,start,end))
            throw new IllegalArgumentException("Sessions under "+floor()
                +" are not recorded. Change the minimum in Settings, or delete this one instead.");
    }

    /**
     * Removes stored sessions below the current minimum. Explicit, never automatic.
     *
     * It deletes recorded time, so the vault is backed up before anything goes,
     * like a reset; a run that removes nothing writes nothing at all.
     */
    public int purgeShortSessions() throws IOException {
        var now=clock.instant();
        var keep=state.sessions().stream().filter(s->Analytics.counts(s,state,now)).toList();
        int removed=state.sessions().size()-keep.size();
        if(removed>0) {
            repository.backup();
            commit(state.withCore(state.activities(),keep,state.blocks()));
        }
        return removed;
    }
    public void log(UUID id,Instant start,Instant end) throws IOException {
        requireActivity(id);
        if(end.isAfter(clock.instant()))throw new IllegalArgumentException("Recorded work cannot end in the future.");
        requireLongEnough(start,end);
        var list=new ArrayList<>(state.sessions());
        list.add(new Session(UUID.randomUUID(),id,start,end));
        commit(state.withCore(state.activities(),list,state.blocks()));
    }
    /**
     * Adds finished Anki sittings to the tracked time (see {@link AnkiTime}).
     *
     * complete is the moment the answers are known to be complete from. Safe to
     * call on every refresh: a sitting already recorded, one still going and
     * one that would overlap recorded time are all left out, and when nothing
     * is left nothing is written. Otherwise one write adds every sitting, and
     * the activity they go under when this is the first.
     *
     * @return the sessions added, empty when there was nothing new
     */
    public List<Session> addAnkiTime(Collection<AnkiTime.Review> reviews, Instant complete) throws IOException {
        var now=clock.instant();
        var sittings=AnkiTime.sittings(reviews,complete);
        var existing=AnkiTime.activity(state);
        var activity=existing!=null?existing:UUID.randomUUID();
        var added=AnkiTime.toRecord(state,sittings,activity,now);
        if(added.isEmpty()) return List.of();
        var activities=new ArrayList<>(state.activities());
        if(existing==null) activities.add(new Activity(activity,AnkiTime.ACTIVITY,0));
        var sessions=new ArrayList<>(state.sessions());
        sessions.addAll(added);
        commit(state.withCore(activities,sessions,state.blocks()));
        return added;
    }
    public void editSession(UUID sessionId, UUID activityId, Instant start, Instant end) throws IOException {
        requireActivity(activityId);
        requireLongEnough(start,end);
        if(start.isAfter(clock.instant()) || end!=null && end.isAfter(clock.instant()))
            throw new IllegalArgumentException("Recorded time cannot be in the future.");
        var next=new ArrayList<>(state.sessions());
        var old=next.stream().filter(x->x.id().equals(sessionId)).findFirst().orElseThrow(()->new IllegalArgumentException("Session no longer exists."));
        if(old.end()!=null && end==null) throw new IllegalArgumentException("A completed session cannot be reopened. Start a new timer instead.");
        next.set(next.indexOf(old),new Session(old.id(),activityId,start,end));
        commit(state.withCore(state.activities(),next,state.blocks()));
    }
    public void editBlock(UUID blockId, UUID activityId, Instant start, Instant end) throws IOException {
        requireActivity(activityId);var next=new ArrayList<>(state.blocks());
        var old=next.stream().filter(x->x.id().equals(blockId)).findFirst().orElseThrow(()->new IllegalArgumentException("Block no longer exists."));
        next.set(next.indexOf(old),new ScheduleBlock(blockId,activityId,start,end));
        commit(state.withCore(state.activities(),state.sessions(),next));
    }
    public void plan(UUID id,Instant start,Instant end) throws IOException {
        requireActivity(id);
        var next=new ScheduleBlock(UUID.randomUUID(),id,start,end);
        var list=new ArrayList<>(state.blocks());
        list.add(next);
        commit(state.withCore(state.activities(),state.sessions(),list));
    }
    /** Refused for a session that is already gone, so a stale control says so instead of appearing to work. */
    public void deleteSession(UUID id) throws IOException {
        if(state.sessions().stream().noneMatch(s->s.id().equals(id)))throw new IllegalArgumentException("Session no longer exists.");
        if(active()!=null&&active().id().equals(id))throw new IllegalArgumentException("Clock out before deleting.");
        // Small deletions are backed up like large ones now that backups are pruned (#7).
        repository.backup();
        commit(state.withCore(state.activities(),state.sessions().stream().filter(s->!s.id().equals(id)).toList(),state.blocks()));
    }
    public void deleteBlock(UUID id) throws IOException {
        if(state.blocks().stream().noneMatch(b->b.id().equals(id)))throw new IllegalArgumentException("Block no longer exists.");
        repository.backup();
        commit(state.withCore(state.activities(),state.sessions(),state.blocks().stream().filter(b->!b.id().equals(id)).toList()));
    }

    /**
     * One task, typed by hand, always added.
     *
     * Not {@link #addTasks}: that skips a task matching one already stored,
     * which is right for a re-import and wrong here. Typing "laundry" when a
     * finished "Laundry" was already in the vault saved nothing, said nothing,
     * and closed the dialog as though it had worked.
     */
    public void addTask(Task task) throws IOException {
        if (task.activityId() != null) requireActivity(task.activityId());
        var next = new ArrayList<>(state.tasks());
        next.add(task);
        commit(state.withTasks(next));
    }

    /** Entire reviewed batch commits together. Entries with the title, deadline and activity of a stored task are skipped. */
    public int addTasks(List<Task> batch) throws IOException {
        if (batch.size() > 1000) throw new IllegalArgumentException("Import at most 1000 tasks at once.");
        var next = new ArrayList<>(state.tasks());
        int added = 0;
        for (Task task : batch) {
            if (task.activityId() != null) requireActivity(task.activityId());
            if (next.stream().noneMatch(existing -> existing.sameEntryAs(task))) { next.add(task); added++; }
        }
        commit(state.withTasks(next));
        return added;
    }

    /**
     * A reviewed import, tags and tasks in one write.
     *
     * Tags cannot be created one at a time here: an import that made the tags
     * and then failed on a task would leave tags for work that never arrived.
     * Both lists are validated together and {@code commit} saves before it
     * publishes, so a failure anywhere leaves the vault exactly as it was —
     * no partial import, no orphan tag.
     *
     * The new tags are supplied already built, with the ids their tasks point
     * at, because a task can only reference a tag that exists in the same write.
     */
    public int importTasks(List<Tag> newTags, List<Task> batch) throws IOException {
        if (batch.size() > 1000) throw new IllegalArgumentException("Import at most 1000 tasks at once.");
        var seen = new HashSet<String>();
        for (Tag tag : newTags) {
            if (!seen.add(tag.name().toLowerCase(Locale.ROOT)) || state.tags().stream().anyMatch(t -> t.name().equalsIgnoreCase(tag.name())))
                throw new IllegalArgumentException("The tag \"" + tag.name() + "\" already exists.");
        }
        var tags = new ArrayList<>(state.tags());
        tags.addAll(newTags);
        var next = new ArrayList<>(state.tasks());
        int added = 0;
        for (Task task : batch) {
            if (task.activityId() != null) requireActivity(task.activityId());
            // By class as well as title and deadline: two classes may set an
            // assignment of the same name on the same day (#import).
            if (next.stream().noneMatch(existing -> existing.sameImportEntryAs(task))) { next.add(task); added++; }
        }
        commit(state.withTags(tags).withTasks(next));
        return added;
    }

    /** Whether a task with this title and deadline is already recorded, by the same rule addTasks skips on. */
    public boolean alreadyHas(String title, LocalDate due) {
        return alreadyHas(title, due, null);
    }

    /**
     * The same question for one class: whether that class already has this task.
     *
     * The review list ticks a row when the vault does not hold it. Without the
     * class, one "Quiz 1" left every other class's quiz of that name unticked
     * and called it "already in Yoru".
     */
    public boolean alreadyHas(String title, LocalDate due, String tagName) {
        var tagId = tagName == null ? null : state.tags().stream()
            .filter(t -> t.name().equalsIgnoreCase(tagName)).map(Tag::id).findFirst().orElse(null);
        return state.tasks().stream().anyMatch(t -> t.activityId() == null
            && t.title().equalsIgnoreCase(title) && Objects.equals(t.due(), due)
            // A row with no class matches whatever is stored; a row with one
            // matches an untagged task, or a task under that same class. A class
            // the vault has never seen matches nothing that is filed under one.
            && (tagName == null || t.tagId() == null || (tagId != null && tagId.equals(t.tagId()))));
    }

    public void updateTask(Task task) throws IOException {
        var next = new ArrayList<>(state.tasks());
        int index = -1;
        for (int i = 0; i < next.size(); i++) if (next.get(i).id().equals(task.id())) index = i;
        if (index < 0) throw new IllegalArgumentException("Task no longer exists.");
        next.set(index, task);
        commit(state.withTasks(next));
    }

    public void addHabit(String name, HabitKind kind, ZoneId zone, Instant since) throws IOException {
        if (since != null && since.isAfter(clock.instant())) throw new IllegalArgumentException("Start cannot be in the future.");
        var next = new ArrayList<>(state.habits());
        next.add(new Habit(UUID.randomUUID(), name, kind, zone.getId(), Set.of(),
            kind == HabitKind.DAILY ? List.of() : List.of(Objects.requireNonNull(since))));
        commit(state.withHabits(next));
    }
    public void checkIn(UUID id, LocalDate date, boolean done) throws IOException {
        var h = habit(id);
        if(h.kind()!=HabitKind.DAILY) throw new IllegalArgumentException("Choose a daily tracker.");
        if(date.isAfter(LocalDate.now(clock.withZone(ZoneId.of(h.zone()))))) throw new IllegalArgumentException("Cannot check off a future day.");
        var dates = new HashSet<>(h.checkIns()); if(done) dates.add(date); else dates.remove(date);
        replaceHabit(new Habit(h.id(),h.name(),h.kind(),h.zone(),dates,h.starts()));
    }
    public void renameHabit(UUID id, String name) throws IOException {
        var h=habit(id);
        var renamed=new Habit(h.id(),name,h.kind(),h.zone(),h.checkIns(),h.starts());
        if(renamed.equals(h))return;
        repository.backup();
        replaceHabit(renamed);
    }
    public void deleteHabit(UUID id) throws IOException {
        habit(id); // Refuse stale controls before backing up or writing.
        var remaining=state.habits().stream().filter(h->!h.id().equals(id)).toList();
        repository.backup();
        commit(state.withHabits(remaining));
    }
    public void restartHabit(UUID id) throws IOException {
        var h=habit(id);
        if(h.kind()!=HabitKind.TIME_SINCE) throw new IllegalArgumentException("Choose a time-since tracker.");
        var starts=new ArrayList<>(h.starts()); starts.add(clock.instant());
        replaceHabit(new Habit(h.id(),h.name(),h.kind(),h.zone(),h.checkIns(),starts));
    }
    public void editHabitStart(UUID id, Instant since) throws IOException {
        var h=habit(id);
        if(h.kind()!=HabitKind.TIME_SINCE) throw new IllegalArgumentException("Choose a time-since tracker.");
        editHabitPeriod(id,h.starts().getLast(),since);
    }

    /**
     * Moves the start of any one period of a time-since tracker (#21).
     *
     * The period is found by the instant it starts at, not by its place in a
     * list, so a history that changed after its dialog opened refuses the edit
     * instead of moving some other period. A start stays after the period before
     * it, before the period after it, and out of the future; each refusal says
     * which, where the record itself could only say the order was wrong.
     */
    public void editHabitPeriod(UUID id, Instant periodStart, Instant newStart) throws IOException {
        var h=habit(id);
        if(h.kind()!=HabitKind.TIME_SINCE) throw new IllegalArgumentException("Choose a time-since tracker.");
        int index=h.starts().indexOf(periodStart);
        if(index<0) throw new IllegalArgumentException("That period changed after it was opened. Reopen the history and try again.");
        if(newStart.isAfter(clock.instant())) throw new IllegalArgumentException("Start cannot be in the future.");
        if(index>0&&!newStart.isAfter(h.starts().get(index-1)))
            throw new IllegalArgumentException("A period has to start after the one before it began.");
        if(index<h.starts().size()-1&&!newStart.isBefore(h.starts().get(index+1)))
            throw new IllegalArgumentException("A period has to start before the one after it began.");
        var starts=new ArrayList<>(h.starts());
        starts.set(index,newStart);
        replaceHabit(new Habit(h.id(),h.name(),h.kind(),h.zone(),h.checkIns(),starts));
    }

    /**
     * Removes one period's start, so the time it covered joins its neighbour (#21).
     *
     * A tracker always keeps one period; deleting the last is deleting the
     * tracker, which has its own control. Backed up first, like deleting a habit.
     */
    public void deleteHabitPeriod(UUID id, Instant periodStart) throws IOException {
        var h=habit(id);
        if(h.kind()!=HabitKind.TIME_SINCE) throw new IllegalArgumentException("Choose a time-since tracker.");
        if(!h.starts().contains(periodStart))
            throw new IllegalArgumentException("That period changed after it was opened. Reopen the history and try again.");
        if(h.starts().size()==1)
            throw new IllegalArgumentException("A time-since tracker keeps at least one period. Delete the tracker instead.");
        var starts=new ArrayList<>(h.starts());
        starts.remove(periodStart);
        repository.backup();
        replaceHabit(new Habit(h.id(),h.name(),h.kind(),h.zone(),h.checkIns(),starts));
    }
    /** The activity with this id, or a refusal that says it is gone. */
    private Activity activity(UUID id) {
        return state.activities().stream().filter(a->a.id().equals(id)).findFirst()
            .orElseThrow(()->new IllegalArgumentException("That activity no longer exists."));
    }
    private Habit habit(UUID id) { return state.habits().stream().filter(h->h.id().equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("Tracker no longer exists.")); }
    private void replaceHabit(Habit h) throws IOException {
        var next=new ArrayList<>(state.habits()); next.set(next.indexOf(habit(h.id())),h); commit(state.withHabits(next));
    }

    // ---- the game ------------------------------------------------------------

    /**
     * Opens the next encounter and keeps what it turned out to be, as a reward
     * for the game.
     *
     * The id must be the one the campaign has fixed for this encounter, which
     * is what stops the same encounter being caught twice and stops anything but
     * the next one being caught at all.
     */
    public Reward catchEncounter(UUID id, int nationalDex, int level) throws IOException {
        if (Encounters.available(state) < 1) throw new IllegalArgumentException("No encounter is waiting yet.");
        if (!state.campaign().nextEncounter().equals(Objects.requireNonNull(id)))
            throw new IllegalArgumentException("That encounter has already been opened.");
        var reward = new Reward(id, nationalDex, level, clock.instant(), null);
        var rewards = new ArrayList<>(state.rewards());
        rewards.add(reward);
        commit(state.withCampaign(state.campaign().encounterUsed(Encounters.SECONDS_PER_ENCOUNTER)).withRewards(rewards));
        return reward;
    }

    /**
     * Records that study has earned a Pokémon for the game, outside the
     * encounter sequence. Banking the same id twice is refused: that would be
     * one reward counted as two.
     */
    public Reward bankReward(UUID id, int nationalDex, int level) throws IOException {
        Objects.requireNonNull(id);
        if (state.rewards().stream().anyMatch(r -> r.id().equals(id)))
            throw new IllegalArgumentException("That reward has already been earned.");
        var reward = new Reward(id, nationalDex, level, clock.instant(), null);
        var next = new ArrayList<>(state.rewards());
        next.add(reward);
        commit(state.withRewards(next));
        return reward;
    }

    /**
     * Marks a reward as having reached the game. Idempotent, because delivery
     * is retried: marking it again must not fail or move the recorded time.
     */
    public void rewardDelivered(UUID id, Instant when) throws IOException {
        var rewards = new ArrayList<>(state.rewards());
        for (int i = 0; i < rewards.size(); i++) {
            var r = rewards.get(i);
            if (!r.id().equals(id)) continue;
            if (r.delivered()) return;
            rewards.set(i, r.deliveredAt(when));
            commit(state.withRewards(rewards));
            return;
        }
        throw new IllegalArgumentException("No such reward.");
    }

    /**
     * The running game has written its save; keep it in the vault.
     *
     * Nothing is written when the bytes are what the vault already holds, so an
     * idle game costs nothing.
     */
    public void gameSaved(byte[] bytes) throws IOException {
        if (state.game() != null && state.game().holds(bytes)) return;
        commit(state.withGame(new GameSave(bytes, clock.instant())));
    }

    /**
     * Delivery, in one write: the new save and the spent rewards together, or
     * neither.
     *
     * Refused when the vault's save is no longer the one delivery was planned
     * against — the only way to be sure a change made from one save is never
     * written over another.
     */
    public void recordDelivery(byte[] before, byte[] after, Collection<UUID> spent) throws IOException {
        if (state.game() == null || !state.game().holds(before))
            throw new IllegalStateException("The game save changed while Yoru was working on it, so nothing was changed.");
        var when = clock.instant();
        var rewards = state.rewards().stream()
            .map(r -> spent.contains(r.id()) && !r.delivered() ? r.deliveredAt(when) : r).toList();
        var next = state.withRewards(rewards);
        if (!Arrays.equals(before, after)) next = next.withGame(new GameSave(after, when));
        commit(next);
    }

    /**
     * Commits an edit to the game's save, made while the game is closed (#44).
     *
     * The same discipline as recordDelivery: refused when the vault's save is
     * no longer the one the edit was planned against, so a change made from one
     * save is never written over another. The vault is backed up before the
     * first edit of a run, because this writes the game's save without the
     * game's help.
     */
    public void editSave(byte[] before, byte[] after) throws IOException {
        if (state.game() == null || !state.game().holds(before))
            throw new IllegalStateException("The game save changed while Yoru was working on it, so nothing was changed.");
        if (Arrays.equals(before, after)) return;
        // One backup per run of edits, not one per edit: arranging a box is
        // dozens of moves, and a whole copy of the vault for each fills the
        // disk with states one move apart. The copy is taken before the first
        // edit to a save that came from anywhere else — the game, a delivery,
        // an import — which is the state worth getting back to.
        if (state.game() != lastEdit) repository.backup();
        commit(state.withGame(new GameSave(after, clock.instant())));
        lastEdit = state.game();
    }

    /**
     * Replaces the vault's game save with one brought in from elsewhere — an
     * emulator's save file, or the save an earlier version kept on disk.
     *
     * The vault as it was is backed up first when it already held a save, so
     * choosing the wrong file cannot cost the game that was there.
     */
    public void replaceGameSave(byte[] bytes) throws IOException {
        if (state.game() != null) repository.backup();
        commit(state.withGame(new GameSave(bytes, clock.instant())));
    }

    /** A weekly-template entry. Overlaps on the same weekday are refused by State. */
    public RecurringBlock repeat(UUID activityId,DayOfWeek day,LocalTime start,LocalTime end) throws IOException {
        requireActivity(activityId);
        var block=new RecurringBlock(UUID.randomUUID(),activityId,day,start,end);
        var next=new ArrayList<>(state.recurring());
        next.add(block);
        commit(state.withRecurring(next));
        return block;
    }

    public void editRepeat(UUID id,UUID activityId,DayOfWeek day,LocalTime start,LocalTime end) throws IOException {
        requireActivity(activityId);
        var next=new ArrayList<>(state.recurring());
        var existing=next.stream().filter(r->r.id().equals(id)).findFirst()
            .orElseThrow(()->new IllegalArgumentException("That repeating block no longer exists."));
        next.set(next.indexOf(existing),new RecurringBlock(id,activityId,day,start,end));
        commit(state.withRecurring(next));
    }

    public void deleteRepeat(UUID id) throws IOException {
        if(state.recurring().stream().noneMatch(r->r.id().equals(id)))
            throw new IllegalArgumentException("That repeating block no longer exists.");
        // Backed up like every other deletion (#7): a repeat deleted by mistake
        // was the one thing no backup held.
        repository.backup();
        commit(state.withRecurring(state.recurring().stream().filter(r->!r.id().equals(id)).toList()));
    }

    /** What a reset can clear, each with the words the reset dialog shows for it. */
    public enum ResetPart {
        SESSIONS("Time sessions"),
        SCHEDULE("Schedule blocks and weekly repeats"),
        TASKS("Tasks"),
        TAGS("Tags"),
        DAILY_HABITS("Daily check-offs"),
        TIME_SINCE("Time-since trackers"),
        GAME("Game save (the game starts over)"),
        SETTINGS("Settings"),
        PAGES("Pages and folders (tasks are kept, unlinked)"),
        ACTIVITIES("Activities (also clears sessions and schedule; tasks are kept, unlinked)");

        public final String label;
        ResetPart(String label) { this.label = label; }
    }

    /**
     * Clears the chosen parts, keeping everything else exactly as it was.
     *
     * Rewards are never reset. A delivered one stands for a Pokémon the game
     * holds; a pending one is study time already spent. Clearing sessions also
     * clears the encounter credit they had earned, so no encounter is owed for
     * time that no longer exists — but encounters already opened stay opened.
     */
    public void reset(Set<ResetPart> parts) throws IOException {
        if(parts.isEmpty())throw new IllegalArgumentException("Choose what to reset.");
        boolean activities=parts.contains(ResetPart.ACTIVITIES);
        boolean sessions=activities||parts.contains(ResetPart.SESSIONS);
        if(active()!=null&&!sessions)throw new IllegalArgumentException("Clock out before resetting other data.");
        var tasks=parts.contains(ResetPart.TASKS)?List.<Task>of():state.tasks();
        // Clearing activities unlinks tasks from them; the tasks themselves survive.
        if(activities)tasks=tasks.stream().map(x->x.withActivity(null)).toList();
        // Tags are their own section. Clearing tasks used to clear them too,
        // which the dialog never said and a new term never wanted: the classes
        // survive the assignments filed under them.
        boolean clearTags=parts.contains(ResetPart.TAGS);
        if(clearTags)tasks=tasks.stream().map(x->x.withTag(null)).toList();
        // Clearing pages unlinks the tasks that pointed at them; the tasks stay.
        boolean clearPages=parts.contains(ResetPart.PAGES);
        if(clearPages)tasks=tasks.stream().map(x->x.withPages(List.of())).toList();
        var next=new State(
            activities?List.of():state.activities(),
            sessions?List.of():state.sessions(),
            activities||parts.contains(ResetPart.SCHEDULE)?List.of():state.blocks(),
            activities||parts.contains(ResetPart.SCHEDULE)?List.of():state.recurring(),
            tasks,
            state.habits().stream().filter(h->!(h.kind()==HabitKind.DAILY?parts.contains(ResetPart.DAILY_HABITS):parts.contains(ResetPart.TIME_SINCE))).toList(),
            clearTags?List.of():state.tags(),
            parts.contains(ResetPart.SETTINGS)?Settings.defaults():state.settings(),
            sessions?state.campaign().withRewardedSeconds(0):state.campaign(),
            state.rewards(),
            parts.contains(ResetPart.GAME)?null:state.game(),
            clearPages?Notes.empty():state.notes());
        repository.backup();commit(next);
    }

    private Task task(UUID id) {
        return state.tasks().stream().filter(t->t.id().equals(id)).findFirst()
            .orElseThrow(()->new IllegalArgumentException("Task no longer exists."));
    }

    public void settings(Settings next) throws IOException {
        commit(state.withSettings(Objects.requireNonNull(next)));
    }

    public Tag addTag(String name,int colour) throws IOException {
        if(state.tags().stream().anyMatch(t->t.name().equalsIgnoreCase(name.strip())))
            throw new IllegalArgumentException("That tag already exists.");
        var tag=new Tag(UUID.randomUUID(),name,colour);
        var next=new ArrayList<>(state.tags());
        next.add(tag);
        commit(state.withTags(next));
        return tag;
    }

    public void editTag(UUID id,String name,int colour) throws IOException {
        var next=new ArrayList<>(state.tags());
        var old=next.stream().filter(t->t.id().equals(id)).findFirst()
            .orElseThrow(()->new IllegalArgumentException("Tag no longer exists."));
        if(next.stream().anyMatch(t->!t.id().equals(id)&&t.name().equalsIgnoreCase(name.strip())))
            throw new IllegalArgumentException("Another tag already has that name.");
        next.set(next.indexOf(old),new Tag(id,name,colour));
        commit(state.withTags(next));
    }

    /** Tasks keep everything except the tag; deleting a tag never deletes work. */
    public void deleteTag(UUID id) throws IOException {
        if(state.tags().stream().noneMatch(t->t.id().equals(id)))throw new IllegalArgumentException("Tag no longer exists.");
        var tasks=state.tasks().stream().map(t->id.equals(t.tagId())?t.withTag(null):t).toList();
        repository.backup();
        // Untag first: removing a tag a task still points at would not validate.
        commit(state.withTasks(tasks)
            .withTags(state.tags().stream().filter(t->!t.id().equals(id)).toList()));
    }

    public void taskStatus(UUID id,TaskStatus status) throws IOException {
        updateTask(task(id).withStatus(Objects.requireNonNull(status)));
    }

    public void deleteTask(UUID id) throws IOException {
        var task=task(id);
        repository.backup();
        commit(state.withTasks(state.tasks().stream().filter(t->!t.id().equals(task.id())).toList()));
    }

    /** Manual ordering for the board; ids not listed keep their current position. */
    public void reorderTasks(List<UUID> order) throws IOException {
        var positions=new HashMap<UUID,Integer>();
        for(int i=0;i<order.size();i++) positions.put(order.get(i),i);
        commit(state.withTasks(state.tasks().stream()
            .map(t->positions.containsKey(t.id())?t.withOrder(positions.get(t.id())):t)
            .toList()));
    }
}
