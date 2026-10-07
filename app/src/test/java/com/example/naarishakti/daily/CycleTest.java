package com.example.naarishakti.daily;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.SortedSet;
import java.util.TreeSet;

public class CycleTest {

    private static SortedSet<Integer> days(int... days) {
        SortedSet<Integer> out = new TreeSet<>();
        for (int d : days) out.add(d);
        return out;
    }

    /** A period of {@code length} days starting on {@code start}. */
    private static void period(SortedSet<Integer> into, int start, int length) {
        for (int i = 0; i < length; i++) into.add(start + i);
    }

    @Test
    public void emptyLogHasNoPredictions() {
        Cycle c = new Cycle(days());
        assertFalse(c.hasData());
        assertFalse(c.personal);
        assertEquals(Cycle.DEFAULT_CYCLE, c.cycleLength);
        assertEquals(Cycle.DEFAULT_PERIOD, c.periodLength);
        assertFalse(c.isPredictedPeriod(100));
        assertFalse(c.isFertile(100));
    }

    @Test
    public void onePeriodUsesTheDefaultCycle() {
        SortedSet<Integer> log = days();
        period(log, 1000, 4);
        Cycle c = new Cycle(log);
        assertTrue(c.hasData());
        assertFalse(c.personal);
        assertEquals(1000, c.lastStart());
        assertEquals(1028, c.nextStart());
        assertEquals(4, c.periodLength);
        assertEquals(1, c.cycleDay(1000));
        assertEquals(12, c.cycleDay(1011));
    }

    @Test
    public void averagesHerOwnCycles() {
        SortedSet<Integer> log = days();
        period(log, 1000, 5);
        period(log, 1030, 5); // 30 days
        period(log, 1056, 3); // 26 days
        Cycle c = new Cycle(log);
        assertTrue(c.personal);
        assertEquals(28, c.cycleLength);
        assertEquals(4, c.periodLength); // (5 + 5 + 3) / 3 rounded
        assertEquals(1056, c.lastStart());
        assertEquals(1084, c.nextStart());
    }

    @Test
    public void aSkippedDayStaysTheSamePeriod() {
        Cycle c = new Cycle(days(1000, 1001, 1003, 1004));
        assertEquals(1000, c.lastStart());
        assertEquals(5, c.periodLength);
    }

    @Test
    public void aMissedMonthDoesNotStretchTheAverage() {
        SortedSet<Integer> log = days();
        period(log, 1000, 5);
        period(log, 1028, 5);
        period(log, 1112, 5); // 84 days later: two periods were not logged
        Cycle c = new Cycle(log);
        assertEquals(28, c.cycleLength);
        assertEquals(1140, c.nextStart());
    }

    @Test
    public void predictsUpcomingPeriodAndFertileDays() {
        SortedSet<Integer> log = days();
        period(log, 1000, 5);
        Cycle c = new Cycle(log);
        assertFalse(c.isPredictedPeriod(1002)); // logged, not predicted
        assertFalse(c.isPredictedPeriod(1027));
        assertTrue(c.isPredictedPeriod(1028));
        assertTrue(c.isPredictedPeriod(1032));
        assertFalse(c.isPredictedPeriod(1033));
        assertTrue(c.isPredictedPeriod(1056)); // the cycle after

        // Ovulation estimated on 1014: window 1009..1015.
        assertFalse(c.isFertile(1008));
        assertTrue(c.isFertile(1009));
        assertTrue(c.isFertile(1015));
        assertFalse(c.isFertile(1016));
    }
}
