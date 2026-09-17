package dev.yoru.application;
import dev.yoru.domain.Model.*;
import java.time.*;
import java.util.*;
public final class Analytics {

    /**
     * One expansion of a weekly template entry onto a real date. Derived, never
     * stored: two occurrences of the same Monday block in different weeks share
     * a recurringId, which is how the grid maps a drawn box back to its rule.
     */
    public record Occurrence(UUID recurringId, UUID activityId, Instant start, Instant end) { }

    /**
     * Expands the weekly template across the seven days from weekStart.
     *
     * Resolved in the given zone, so a 09:00 class is 09:00 local on every one of
     * those days regardless of a daylight-saving change between them — which is
     * the whole reason the template stores LocalTime rather than Instant.
     *
     * On the day a zone skips an hour, a time inside the gap moves later by the
     * length of the gap, so a block starting inside it is shorter that day. One
     * lying wholly inside the gap would end before it began, and is left out of
     * the week rather than returned inverted: the grid draws nothing for an
     * inverted block anyway, and every caller may assume end follows start.
     */
    public static List<Occurrence> occurrences(State state, LocalDate weekStart, ZoneId zone) {
        var out = new ArrayList<Occurrence>();
        for (int offset = 0; offset < 7; offset++) {
            LocalDate date = weekStart.plusDays(offset);
            for (var rule : state.recurring()) {
                if (rule.dayOfWeek() != date.getDayOfWeek()) continue;
                var start = ZonedDateTime.of(date, rule.startTime(), zone).toInstant();
                var end = ZonedDateTime.of(date, rule.endTime(), zone).toInstant();
                if (!end.isAfter(start)) continue;
                out.add(new Occurrence(rule.id(), rule.activityId(), start, end));
            }
        }
        out.sort(Comparator.comparing(Occurrence::start));
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
        return session.end()==null || session.seconds(now)>=state.settings().minSessionSeconds();
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
        return String.format("%02d:%02d:%02d",Math.max(0,seconds)/3600,Math.max(0,seconds)/60%60,Math.max(0,seconds)%60);
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
        // Five bands below the goal, so a quarter-goal day is visibly different
        // from a near-goal one at any goal size.
        int band=(int)(seconds*5/goal)+1;
        return Math.min(5,band);
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
