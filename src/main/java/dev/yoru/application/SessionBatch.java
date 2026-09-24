package dev.yoru.application;

import dev.yoru.domain.Model.*;
import java.time.*;
import java.util.*;

/** Validates the entire replacement timeline before a backup or write (#59). */
public final class SessionBatch {
    private SessionBatch(){}
    public sealed interface Change permits Move,Shift{}
    public record Move(UUID activityId) implements Change{public Move{Objects.requireNonNull(activityId);}}
    public record Shift(Duration offset) implements Change{public Shift{Objects.requireNonNull(offset);}}

    static State edit(State state,Collection<UUID> selected,Change change,Instant now){
        var ids=selected(state,selected);
        Objects.requireNonNull(change);
        if(change instanceof Move move&&state.activities().stream().noneMatch(a->a.id().equals(move.activityId())))
            throw new IllegalArgumentException("The selected activity no longer exists.");
        var sessions=state.sessions().stream().map(session->{
            if(!ids.contains(session.id()))return session;
            return switch(change){
                case Move move->new Session(session.id(),move.activityId(),session.start(),session.end());
                case Shift shift->{
                    var start=session.start().plus(shift.offset());
                    var end=session.end().plus(shift.offset());
                    if(start.isAfter(now)||end.isAfter(now))throw new IllegalArgumentException("Recorded time cannot be in the future.");
                    yield new Session(session.id(),session.activityId(),start,end);
                }
            };
        }).toList();
        // State checks the final timeline, not each intermediate move. This lets
        // adjacent selected sessions move together without false overlap errors.
        return state.withCore(state.activities(),sessions,state.blocks());
    }
    static State delete(State state,Collection<UUID> selected){
        var ids=selected(state,selected);
        return state.withCore(state.activities(),state.sessions().stream().filter(s->!ids.contains(s.id())).toList(),state.blocks());
    }
    private static Set<UUID> selected(State state,Collection<UUID> selected){
        var ids=Set.copyOf(selected);
        if(ids.isEmpty())throw new IllegalArgumentException("Select at least one session.");
        var found=new HashSet<UUID>();
        for(var session:state.sessions())if(ids.contains(session.id())){
            if(session.end()==null)throw new IllegalArgumentException("Clock out before changing a selection that includes the running session.");
            found.add(session.id());
        }
        if(!found.equals(ids))throw new IllegalArgumentException("A selected session no longer exists. Select the sessions again.");
        return ids;
    }
}
