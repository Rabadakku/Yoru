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
     * or lose recorded time. The name
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
     * Moves an activity one place up or down the order every picker, table and
     * legend shows (#59). Sessions, blocks and tasks point at it by id, so only
     * the order changes; nothing is written at either end. Backed up first, like
     * the habit order.
     */
    public void moveActivity(UUID id,int direction) throws IOException {
        var next=moved(state.activities(),activity(id),direction);
        if(next==null) return;
        repository.backup();
        commit(state.withCore(next,state.sessions(),state.blocks()));
    }

    /** {@code items} with {@code item} one place along, or null when it is already at that end. */
    private static <T> List<T> moved(List<T> items,T item,int direction) {
        if(direction!=-1&&direction!=1) throw new IllegalArgumentException("Choose up or down.");
        int from=items.indexOf(item),to=from+direction;
        if(to<0||to>=items.size()) return null;
        var next=new ArrayList<>(items);
        Collections.swap(next,from,to);
        return next;
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
     * rather than stored. Below the floor a session counted toward no total, so
     * keeping it only produced a row that disagreed with every number beside it.
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
    public void editSessions(Collection<UUID> ids, SessionBatch.Change change) throws IOException {
        var next=SessionBatch.edit(state,ids,change,clock.instant());
        if(next.equals(state))return;
        repository.backup();
        commit(next);
    }
    public void deleteSessions(Collection<UUID> ids) throws IOException {
        var next=SessionBatch.delete(state,ids);
        repository.backup();
        commit(next);
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
            && (tagName == null || t.tagIds().isEmpty() || (tagId != null && t.tagIds().contains(tagId))));
    }

    /**
     * A task from the editor, with the tags it made on the way, in one write (#66).
     *
     * A tag typed into the form is created with the task that carries it: made
     * first and alone, a save that then failed would leave a tag for a task
     * that was never kept. Adds the task when it is new, and replaces it when
     * it is not.
     */
    public void saveTask(Task task, List<Tag> newTags) throws IOException {
        var tags = new ArrayList<>(state.tags());
        for (var tag : newTags) {
            if (tags.stream().anyMatch(t -> t.name().equalsIgnoreCase(tag.name()) || t.id().equals(tag.id())))
                throw new IllegalArgumentException("The tag \"" + tag.name() + "\" already exists.");
            tags.add(tag);
        }
        if (task.activityId() != null) requireActivity(task.activityId());
        var next = new ArrayList<>(state.tasks());
        int index = -1;
        for (int i = 0; i < next.size(); i++) if (next.get(i).id().equals(task.id())) index = i;
        if (index < 0) next.add(task); else next.set(index, task);
        commit(state.withTags(tags).withTasks(next));
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
            kind == HabitKind.DAILY ? List.of() : List.of(Objects.requireNonNull(since)),
            (kind == HabitKind.DAILY ? clock.instant() : since).atZone(zone).toLocalDate()));
        commit(state.withHabits(next));
    }
    public void checkIn(UUID id, LocalDate date, boolean done) throws IOException {
        var h = habit(id);
        if(h.kind()!=HabitKind.DAILY) throw new IllegalArgumentException("Choose a daily tracker.");
        if(date.isAfter(LocalDate.now(clock.withZone(ZoneId.of(h.zone()))))) throw new IllegalArgumentException("Cannot check off a future day.");
        var dates = new HashSet<>(h.checkIns()); if(done) dates.add(date); else dates.remove(date);
        var began = date.isBefore(h.since()) && done ? date : h.since();
        var next = new Habit(h.id(),h.name(),h.kind(),h.zone(),dates,h.starts(),began);
        if(next.equals(h))return;
        if(!done || date.isBefore(LocalDate.now(clock.withZone(ZoneId.of(h.zone()))))) repository.backup();
        replaceHabit(next);
    }
    public void renameHabit(UUID id, String name) throws IOException {
        var h=habit(id);
        var renamed=new Habit(h.id(),name,h.kind(),h.zone(),h.checkIns(),h.starts(),h.since());
        if(renamed.equals(h))return;
        repository.backup();
        replaceHabit(renamed);
    }
    /** Changes the daily boundary without reinterpreting recorded calendar dates. */
    public void habitZone(UUID id, ZoneId zone) throws IOException {
        var h=habit(id);
        if(h.kind()!=HabitKind.DAILY) throw new IllegalArgumentException("Choose a daily tracker.");
        var next=new Habit(h.id(),h.name(),h.kind(),Objects.requireNonNull(zone).getId(),h.checkIns(),h.starts(),h.since());
        if(next.equals(h))return;
        repository.backup();
        replaceHabit(next);
    }

    /** Moves among neighbours of the same kind, leaving the other list in place. */
    public void moveHabit(UUID id, int direction) throws IOException {
        if(direction!=-1&&direction!=1)throw new IllegalArgumentException("Choose up or down.");
        var h=habit(id);
        var next=new ArrayList<>(state.habits());
        int from=next.indexOf(h),to=from+direction;
        while(to>=0&&to<next.size()&&next.get(to).kind()!=h.kind())to+=direction;
        if(to<0||to>=next.size())return;
        Collections.swap(next,from,to);
        var changed=state.withHabits(next);
        repository.backup();
        commit(changed);
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
        var starts=new ArrayList<>(h.starts()); starts.add(clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MINUTES));
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
        newStart = newStart.truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
        if(index>0&&!newStart.isAfter(h.starts().get(index-1)))
            throw new IllegalArgumentException("A period has to start after the one before it began.");
        if(index<h.starts().size()-1&&!newStart.isBefore(h.starts().get(index+1)))
            throw new IllegalArgumentException("A period has to start before the one after it began.");
        var starts=new ArrayList<>(h.starts());
        starts.set(index,newStart);
        var next=new Habit(h.id(),h.name(),h.kind(),h.zone(),h.checkIns(),starts);
        if(next.equals(h))return;
        repository.backup();
        replaceHabit(next);
    }

    /** Inserts a missed restart, preserving every existing boundary (#59). */
    public void addHabitPeriod(UUID id, Instant start) throws IOException {
        var h=habit(id);
        if(h.kind()!=HabitKind.TIME_SINCE) throw new IllegalArgumentException("Choose a time-since tracker.");
        if(start.isAfter(clock.instant())) throw new IllegalArgumentException("Start cannot be in the future.");
        start=start.truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
        final var minute=start;
        if(h.starts().stream().anyMatch(value->value.truncatedTo(java.time.temporal.ChronoUnit.MINUTES).equals(minute)))
            throw new IllegalArgumentException("A period already starts in that minute.");
        var starts=new ArrayList<>(h.starts());
        starts.add(start);
        starts.sort(Comparator.naturalOrder());
        var next=new Habit(h.id(),h.name(),h.kind(),h.zone(),h.checkIns(),starts);
        repository.backup();
        replaceHabit(next);
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

    // ---- Anki ----------------------------------------------------------------

    /** Saves the integration's settings: switched on, its key, and how it behaves (#85). */
    public void anki(Anki next) throws IOException {
        commit(state.withAnki(Objects.requireNonNull(next)));
    }

    /**
     * Keeps what Anki last said, so the card can show it while Anki is closed.
     *
     * Counts and times only. Nothing is written when the numbers are the ones
     * already kept, so an idle refresh costs no vault write.
     */
    public void ankiSeen(AnkiSnapshot snapshot) throws IOException {
        if (Objects.equals(state.anki().last(), Objects.requireNonNull(snapshot))) return;
        commit(state.withAnki(state.anki().withSnapshot(snapshot)));
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
        LISTS("Task lists (their tasks move to the Inbox)"),
        DAILY_HABITS("Daily check-offs"),
        TIME_SINCE("Time-since trackers"),
        SETTINGS("Settings"),
        PAGES("Pages and folders (tasks are kept, unlinked)"),
        ANKI("Anki connection and its saved counts"),
        ACTIVITIES("Activities (also clears sessions and schedule; tasks are kept, unlinked)");

        public final String label;
        ResetPart(String label) { this.label = label; }
    }

    /** Clears the chosen parts, keeping everything else exactly as it was. */
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
        if(clearTags)tasks=tasks.stream().map(x->x.withTags(List.of())).toList();
        // Clearing lists files their tasks in the Inbox; the tasks stay (#56).
        boolean clearLists=parts.contains(ResetPart.LISTS);
        if(clearLists)tasks=tasks.stream().map(x->x.withList(null)).toList();
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
            clearPages?Notes.empty():state.notes(),
            parts.contains(ResetPart.ANKI)?Anki.off():state.anki(),
            clearLists?List.of():state.lists());
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

    /** Moves a tag one place up or down the order the tag field offers them in; tasks keep it by id. */
    public void moveTag(UUID id,int direction) throws IOException {
        var tag=state.tags().stream().filter(t->t.id().equals(id)).findFirst()
            .orElseThrow(()->new IllegalArgumentException("Tag no longer exists."));
        var next=moved(state.tags(),tag,direction);
        if(next==null) return;
        repository.backup();
        commit(state.withTags(next));
    }

    /** Tasks keep everything except that tag, and any others they carry; deleting a tag never deletes work. */
    public void deleteTag(UUID id) throws IOException {
        if(state.tags().stream().noneMatch(t->t.id().equals(id)))throw new IllegalArgumentException("Tag no longer exists.");
        var tasks=state.tasks().stream().map(t->t.tagIds().contains(id)?t.withoutTag(id):t).toList();
        repository.backup();
        // Untag first: removing a tag a task still points at would not validate.
        commit(state.withTasks(tasks)
            .withTags(state.tags().stream().filter(t->!t.id().equals(id)).toList()));
    }

    // ---- lists (#56) ---------------------------------------------------------

    /** A new list at the end of the sidebar. Names are unique, whatever their case. */
    public TaskList addList(String name,int colour) throws IOException {
        requireFreeListName(null,name);
        int order=state.lists().stream().mapToInt(TaskList::order).max().orElse(-1)+1;
        var list=new TaskList(UUID.randomUUID(),name,colour,order);
        var next=new ArrayList<>(state.lists());
        next.add(list);
        commit(state.withLists(next));
        return list;
    }

    /** A list's new name and colour; the tasks in it are untouched. */
    public void editList(UUID id,String name,int colour) throws IOException {
        var old=list(id);
        requireFreeListName(id,name);
        commit(state.withLists(state.lists().stream()
            .map(l->l.id().equals(id)?old.renamed(name).recoloured(colour):l).toList()));
    }

    private void requireFreeListName(UUID except,String name) {
        String wanted=Objects.requireNonNull(name).strip();
        if(state.lists().stream().anyMatch(l->!l.id().equals(except)&&l.name().equalsIgnoreCase(wanted)))
            throw new IllegalArgumentException("Another list is already called \""+wanted+"\".");
    }

    private TaskList list(UUID id) {
        return state.lists().stream().filter(l->l.id().equals(id)).findFirst()
            .orElseThrow(()->new IllegalArgumentException("That list no longer exists."));
    }

    /** The lists in the order given; the sidebar's order is the owner's. */
    public void reorderLists(List<UUID> order) throws IOException {
        if(order.size()!=state.lists().size()||!new HashSet<>(order).equals(
                state.lists().stream().map(TaskList::id).collect(java.util.stream.Collectors.toSet())))
            throw new IllegalArgumentException("The lists changed. Try again.");
        var byId=new HashMap<UUID,TaskList>();
        state.lists().forEach(l->byId.put(l.id(),l));
        var next=new ArrayList<TaskList>();
        for(int i=0;i<order.size();i++) next.add(byId.get(order.get(i)).withOrder(i));
        commit(state.withLists(next));
    }

    /**
     * Removes a list. Its tasks move to the Inbox, or go with it when
     * {@code deleteTasks}; a backup is taken first either way, since either can
     * move a great many tasks at once.
     */
    public void deleteList(UUID id,boolean deleteTasks) throws IOException {
        list(id);
        var tasks=deleteTasks
            ?state.tasks().stream().filter(t->!id.equals(t.listId())).toList()
            :state.tasks().stream().map(t->id.equals(t.listId())?t.withList(null):t).toList();
        repository.backup();
        // Out of the list first: removing a list a task is still in would not validate.
        commit(state.withTasks(tasks).withLists(state.lists().stream().filter(l->!l.id().equals(id)).toList()));
    }

    /** Files one task in a list, or in the Inbox for null. */
    public void moveTask(UUID taskId,UUID listId) throws IOException {
        if(listId!=null) list(listId);
        updateTask(task(taskId).withList(listId));
    }

    /**
     * A tag as a list (#56): a list with the tag's name and colour, holding
     * every task the tag was on. The tag stays, so nothing about the tasks is
     * lost; it can be deleted afterwards if it is no longer wanted.
     */
    public TaskList tagToList(UUID tagId) throws IOException {
        var tag=state.tags().stream().filter(t->t.id().equals(tagId)).findFirst()
            .orElseThrow(()->new IllegalArgumentException("Tag no longer exists."));
        requireFreeListName(null,tag.name());
        int order=state.lists().stream().mapToInt(TaskList::order).max().orElse(-1)+1;
        var list=new TaskList(UUID.randomUUID(),tag.name(),tag.colour(),order);
        var lists=new ArrayList<>(state.lists());
        lists.add(list);
        commit(state.withLists(lists).withTasks(state.tasks().stream()
            .map(t->t.tagIds().contains(tagId)?t.withList(list.id()):t).toList()));
        return list;
    }

    public void taskStatus(UUID id,TaskStatus status) throws IOException {
        taskStatus(id,status,ZoneId.systemDefault());
    }

    /**
     * A task's new status, with "today" read in {@code zone}.
     *
     * Finishing a repeating task (#57) records the occurrence and moves the
     * task on to its next date, back to To do: it stays one task with a
     * history rather than a pile of copies. When the rule has run out, the task
     * is simply done.
     */
    public void taskStatus(UUID id,TaskStatus status,ZoneId zone) throws IOException {
        updateTask(TaskBatch.status(task(id), Objects.requireNonNull(status), state, clock.instant(), zone));
    }

    /** Passes over this occurrence of a repeating task without doing it, and moves it to the next (#57). */
    public void skipOccurrence(UUID id,ZoneId zone) throws IOException {
        var task=task(id);
        if(!task.repeats()) throw new IllegalArgumentException("Only a repeating task has an occurrence to skip.");
        if(task.status()==TaskStatus.DONE) throw new IllegalArgumentException("This task has finished repeating.");
        advance(task,true,zone);
    }

    private void advance(Task task,boolean skipped,ZoneId zone) throws IOException {
        updateTask(TaskBatch.advance(task, skipped, state, clock.instant(), zone));
    }

    /** Validate the whole selection, back up once, then save and publish once. */
    public void editTasks(Collection<UUID> ids, TaskBatch.Change change, ZoneId zone) throws IOException {
        var next = TaskBatch.edit(state, ids, change, clock.instant(), zone);
        if (next.equals(state)) return;
        repository.backup();
        commit(next);
    }

    public void deleteTasks(Collection<UUID> ids) throws IOException {
        var next = TaskBatch.delete(state, ids);
        repository.backup();
        commit(next);
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
