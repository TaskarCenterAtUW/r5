package com.conveyal.r5.streets;

import com.conveyal.r5.profile.StreetMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Checks that a router state adds up times given in fractions of a second, and rounds only their total. */
public class StateExactTimeTest {

    @Test
    public void fractionsOfASecondAreKeptAlongAPath () {
        StreetRouter.State state = new StreetRouter.State(0, -1, StreetMode.WALK);
        // Forty edges of 1.3 seconds each: 52 seconds. Rounding each one up would give 80.
        for (int i = 0; i < 40; i++) {
            state = new StreetRouter.State(0, i, state);
            state.incrementTimeExact(1.3);
        }
        assertEquals(52.0, state.getExactDurationSeconds(), 1e-6);
        assertEquals(52, state.getDurationSeconds());
    }

    @Test
    public void wholeSecondsAreTheTotalRoundedUp () {
        StreetRouter.State state = new StreetRouter.State(0, -1, StreetMode.WALK);
        state.incrementTimeExact(0.25);
        assertEquals(1, state.getDurationSeconds());
        state.incrementTimeExact(0.5);
        assertEquals(1, state.getDurationSeconds());
        assertEquals(0.75, state.getExactDurationSeconds(), 1e-9);
        state.incrementTimeExact(0.5);
        assertEquals(2, state.getDurationSeconds());
        assertEquals(1.25, state.getExactDurationSeconds(), 1e-9);
    }

    @Test
    public void wholeSecondIncrementsLeaveTheFractionAlone () {
        StreetRouter.State state = new StreetRouter.State(0, -1, StreetMode.WALK);
        state.incrementTimeExact(2.4);
        state.incrementTimeInSeconds(10);
        assertEquals(13, state.getDurationSeconds());
        assertEquals(12.4, state.getExactDurationSeconds(), 1e-9);
    }
}
