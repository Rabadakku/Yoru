package dev.yoru.application;

import dev.yoru.domain.Model.State;
import java.time.Duration;

/**
 * The study-credit arithmetic: one encounter for every thirty minutes recorded.
 *
 * What an encounter turns out to be comes from the player's game
 * (dev.yoru.game.StudyEncounter); this only counts how many have been earned.
 * No clock, network or game file is needed to count them.
 */
public final class Encounters {
    public static final long SECONDS_PER_ENCOUNTER = 30 * 60;

    private Encounters() { }

    /**
     * Recorded time that counts toward encounters.
     *
     * Applies the same minimum-session floor Analytics uses, so the reward
     * economy and the displayed totals can never disagree about what a day was
     * worth.
     */
    public static long completedSeconds(State state) {
        return state.sessions().stream()
            .filter(s -> s.end() != null && Analytics.counts(s, state, s.end()))
            .mapToLong(s -> Duration.between(s.start(), s.end()).getSeconds()).sum();
    }

    /** Encounters earned and not yet opened. */
    public static long available(State state) {
        return Math.max(0, (completedSeconds(state) - state.campaign().rewardedSeconds()) / SECONDS_PER_ENCOUNTER);
    }

    /** Seconds already recorded toward the next encounter. */
    public static long towardNext(State state) {
        return Math.max(0, completedSeconds(state) - state.campaign().rewardedSeconds()) % SECONDS_PER_ENCOUNTER;
    }
}
