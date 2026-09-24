package dev.yoru.application;
import dev.yoru.domain.Model.*;
import java.time.*;
import java.util.*;
public final class Analytics {

    /**
     * One expansion of a weekly template entry onto a real date. Derived, never
     * stored: two occurrences of the same Monday block in different weeks share
     * a recurringId, which is how the grid maps a drawn box back to its rule.
     *
     * @param week    the day the rule puts this week's block on, which names the
     *                week when it is changed on its own (#59).
     * @param changed whether this week's block was moved from where the rule puts it.
     */
    public record Occurrence(UUID recurringId, UUID activityId, Instant start, Instant end,
                             LocalDate week, boolean changed) { }

    /**
     * Expands the weekly template across the seven days from weekStart.
     *
     * Resolved in the given zone, so a 09:00 class is 09:00 local on every one of
     * those days regardless of a daylight-saving change between them — which is
     * the whole reason the template stores LocalTime rather than Instant.
     *
     * A week changed on its own (#59) is left where the rule would put it and
     * drawn where it was moved to instead, or not at all when it was skipped.
     * A block moved into this week from a day just outside it is this week's to
     * draw, and one moved out of it is not.
     *
     * On the day a zone skips an hour, a time inside the gap moves later by the
     * length of the gap, so a block starting inside it is shorter that day. One
     * lying wholly inside the gap would end before it began, and is left out of
     * the week rather than returned inverted: the grid draws nothing for an
     * inverted block anyway, and every caller may assume end follows start.
     */
    public static List<Occurrence> occurrences(State state, LocalDate weekStart, ZoneId zone) {
        var out = new ArrayList<Occurrence>();
        var weekEnd = weekStart.plusDays(7);
        for (var rule : state.recurring()) {
            for (int offset = 0; offset < 7; offset++) {
                LocalDate date = weekStart.plusDays(offset);
                if (rule.dayOfWeek() != date.getDayOfWeek() || rule.changeOn(date) != null) continue;
                add(out, rule, date, date, rule.startTime(), rule.endTime(), false, zone);
            }
            for (var change : rule.changes()) {
                if (change.skipped()) continue;
                var day = change.movedTo();
                if (day.isBefore(weekStart) || !day.isBefore(weekEnd)) continue;
                add(out, rule, change.date(), day, change.start(), change.end(), true, zone);
            }
        }
        out.sort(Comparator.comparing(Occurrence::start));
        return List.copyOf(out);
    }

    private static void add(List<Occurrence> out, RecurringBlock rule, LocalDate week, LocalDate day,
                            LocalTime from, LocalTime to, boolean changed, ZoneId zone) {
        var start = ZonedDateTime.of(day, from, zone).toInstant();
        var end = ZonedDateTime.of(day, to, zone).toInstant();
        if (end.isAfter(start)) out.add(new Occurrence(rule.id(), rule.activityId(), start, end, week, changed));
    }

    /** The blocks the weekly template holds on one day, changed weeks and all. */
    public static List<Occurrence> occurrencesOn(State state, LocalDate day, ZoneId zone) {
        var out = new ArrayList<Occurrence>();
        for (var o : occurrences(state, day, zone))
            if (o.start().atZone(zone).toLocalDate().equals(day)) out.add(o);
        return List.copyOf(out);
    }

    private Analytics() {
    }
    public static long overlap(Instant a,Instant b,Instant c,Instant d) {
        Instant s=a.isAfter(c)?a:c,e=b.isBefore(d)?b:d;
        return e.isAfter(s)?Duration.between(s,e).getSeconds():0;
    }
    /**
     * True when a session is long enough to count toward totals.
     *
     * A derived rule, not a stored one: every session is kept exactly as
     * recorded and the floor is applied here, so changing it is an edit rather
     * than a data migration. A running session always counts — it has not
     * finished yet, so judging its length would be premature.
     */
    public static boolean counts(Session session,State state,Instant now) {
        return session.end()==null || !tooShort(state,session.start(),session.end());
    }

    /**
     * Whether a stretch of time is under the vault's minimum session.
     *
     * The one place the floor is compared, so clocking out, logging time,
     * purging and every total agree by construction rather than by five copies
     * of the same subtraction staying in step.
     */
    public static boolean tooShort(State state,Instant start,Instant end) {
        return Duration.between(start,end).getSeconds()<state.settings().minSessionSeconds();
    }

    public static Map<LocalDate,Long> daily(State state,UUID activity,ZoneId zone,Instant now) {
        Map<LocalDate,Long> result=new TreeMap<>();
        for(var s:state.sessions()) {
            if(activity!=null&&!activity.equals(s.activityId()))continue;
            if(!counts(s,state,now))continue;
            Instant end=s.end()==null?now:s.end(), cursor=s.start();
            while(cursor.isBefore(end)) {
                LocalDate day=cursor.atZone(zone).toLocalDate();
                Instant boundary=day.plusDays(1).atStartOfDay(zone).toInstant();
                Instant next=boundary.isBefore(end)?boundary:end;
                result.merge(day,Duration.between(cursor,next).getSeconds(),Long::sum);
                cursor=next;
            }
        }
        return result;
    }
    public static int streak(Map<LocalDate,Long> days,LocalDate today) {
        LocalDate d=days.getOrDefault(today,0L)>0?today:today.minusDays(1);
        int count=0;
        while(days.getOrDefault(d,0L)>0) {
            count++;
            d=d.minusDays(1);
        }
        return count;
    }
    public static double adherence(State state,ScheduleBlock b,Instant now) {
        long actual=0;
        // The same sessions every total and the grid count (#34): a session under
        // the floor showed as "20% matched" beside a block the grid drew empty.
        for(var s:state.sessions()) {
            if(!s.activityId().equals(b.activityId())||!counts(s,state,now)) continue;
            actual+=overlap(s.start(),s.end()==null?now:s.end(),b.start(),b.end());
        }
        return Math.min(1,(double)actual/Duration.between(b.start(),b.end()).getSeconds());
    }
    public static String duration(long seconds) {
        long whole=Math.max(0,seconds);
        return String.format("%02d:%02d:%02d",whole/3600,whole/60%60,whole%60);
    }

    /**
     * A duration as it is reported back rather than counted: "2h 15m", "45m", "0m".
     *
     * {@link #duration} is the clock form, for a figure you are watching tick.
     * This is the form for a figure you are reading — a total, a history, a
     * goal — and having exactly two forms is what stops "2.4 hours" and
     * "145 minutes" joining them.
     */
    public static String report(long seconds) {
        long minutes=Math.max(0,seconds)/60;
        return minutes<60 ? minutes+"m" : minutes/60+"h "+minutes%60+"m";
    }

    /** Heat map tiers, including the over-goal one. */
    public static final int HEAT_TIERS=6, HEAT_OVER_GOAL=HEAT_TIERS;

    /**
     * Heat level for a day, scaled to the user's own daily goal rather than a
     * fixed ladder: 0 is nothing, 1-5 climb toward the goal, and HEAT_OVER_GOAL
     * means the goal was beaten — which the UI renders as the rainbow tier.
     */
    public static int heat(long seconds,int dailyGoalHours) {
        if(seconds<=0) return 0;
        long goal=Math.max(1,dailyGoalHours)*3600L;
        if(seconds>=goal) return HEAT_OVER_GOAL;
        // One band per tier below the goal, so a quarter-goal day is visibly
        // different from a near-goal one at any goal size. Under the goal the
        // division cannot reach the top band, so nothing needs clamping.
        return (int)(seconds*(HEAT_TIERS-1)/goal)+1;
    }

    /**
     * One activity's share of the time recorded in a window.
     *
     * The share is of the window's own total, not of the vault: a week in which
     * only one activity was tracked gives that activity 1.0, however small the
     * week was next to every other week.
     */
    public record Slice(UUID activityId, long seconds, double share) { }

    /**
     * How the time in a window divides across activities, largest first.
     *
     * Sessions are clipped to the window rather than counted whole, so a session
     * running across its edge contributes only the part inside — which is what
     * makes the shares sum to the window's own total rather than to something
     * larger. Activities with no time in the window are absent rather than
     * present at zero, and an empty window yields an empty list, so a caller
     * never has to decide what 0/0 should have drawn.
     *
     * Ties are broken on the activity id. HashMap iteration order is not
     * guaranteed between runs, so without a total ordering two activities of
     * equal length could swap places between one repaint and the next.
     */
    public static List<Slice> distribution(State state, Instant from, Instant to, Instant now) {
        Map<UUID,Long> byActivity = new HashMap<>();
        for (var session : state.sessions()) {
            if (!counts(session, state, now)) continue;
            long inside = overlap(session.start(), session.end()==null?now:session.end(), from, to);
            if (inside > 0) byActivity.merge(session.activityId(), inside, Long::sum);
        }
        long total = 0;
        for (long seconds : byActivity.values()) total += seconds;
        if (total == 0) return List.of();
        final long denominator = total;
        return byActivity.entrySet().stream()
            .map(e -> new Slice(e.getKey(), e.getValue(), (double) e.getValue() / denominator))
            .sorted(Comparator.comparingLong(Slice::seconds).reversed()
                .thenComparing(slice -> slice.activityId().toString()))
            .toList();
    }

    /** Seconds recorded in a window, counting only what falls inside it. */
    public static long recorded(State state, Instant from, Instant to, Instant now) {
        long total = 0;
        for (var session : state.sessions()) {
            if (!counts(session, state, now)) continue;
            total += overlap(session.start(), session.end()==null?now:session.end(), from, to);
        }
        return total;
    }

    /**
     * Fractional change from one span to the equally-long span before it.
     *
     * Empty when the earlier span recorded nothing. There is no honest
     * percentage against a zero baseline — the first week of use has not gone
     * up by 100%, it has nothing to be compared with — and returning one would
     * put a confident number next to a comparison that was never made.
     */
    public static OptionalDouble change(long current, long previous) {
        if (previous <= 0) return OptionalDouble.empty();
        return OptionalDouble.of((double)(current - previous) / previous);
    }
}
