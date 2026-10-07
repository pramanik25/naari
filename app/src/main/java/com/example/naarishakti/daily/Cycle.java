package com.example.naarishakti.daily;

import java.util.ArrayList;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Cycle arithmetic on logged period days (epoch days). Pure Java so it can be unit-tested.
 * Predictions are simple calendar estimates from her own averages, never medical advice.
 */
final class Cycle {

    static final int DEFAULT_CYCLE = 28;
    static final int DEFAULT_PERIOD = 5;
    /** Cycle lengths outside this range are treated as a missed log, not as her rhythm. */
    static final int MIN_CYCLE = 15;
    static final int MAX_CYCLE = 60;
    /** Period days further apart than this belong to different periods. */
    private static final int SAME_PERIOD_GAP = 7;
    /** Only her most recent cycles shape the average. */
    private static final int RECENT = 6;
    /** Ovulation is estimated this many days before the next period. */
    private static final int LUTEAL_DAYS = 14;
    /** How many upcoming cycles are drawn on the calendar. */
    private static final int CYCLES_AHEAD = 3;

    private final List<int[]> periods = new ArrayList<>(); // {first day, last day}, oldest first
    final int cycleLength;
    final int periodLength;
    /** True once at least two periods are logged, i.e. the cycle length is hers and not the default. */
    final boolean personal;

    Cycle(SortedSet<Integer> periodDays) {
        int[] current = null;
        for (int day : new TreeSet<>(periodDays)) {
            if (current == null || day - current[1] > SAME_PERIOD_GAP) {
                current = new int[]{day, day};
                periods.add(current);
            } else {
                current[1] = day;
            }
        }

        List<Integer> gaps = new ArrayList<>();
        for (int i = periods.size() - 1; i > 0 && gaps.size() < RECENT; i--) {
            int gap = periods.get(i)[0] - periods.get(i - 1)[0];
            if (gap >= MIN_CYCLE && gap <= MAX_CYCLE) gaps.add(gap);
        }
        personal = !gaps.isEmpty();
        cycleLength = personal ? average(gaps) : DEFAULT_CYCLE;

        List<Integer> lengths = new ArrayList<>();
        for (int i = periods.size() - 1; i >= 0 && lengths.size() < RECENT; i--) {
            lengths.add(periods.get(i)[1] - periods.get(i)[0] + 1);
        }
        periodLength = lengths.isEmpty() ? DEFAULT_PERIOD : average(lengths);
    }

    private static int average(List<Integer> values) {
        int sum = 0;
        for (int v : values) sum += v;
        return Math.round(sum / (float) values.size());
    }

    boolean hasData() {
        return !periods.isEmpty();
    }

    /** First day of the most recent logged period. Only valid when {@link #hasData()}. */
    int lastStart() {
        return periods.get(periods.size() - 1)[0];
    }

    /** Expected first day of the next period. Only valid when {@link #hasData()}. */
    int nextStart() {
        return lastStart() + cycleLength;
    }

    /** 1 on the first day of the latest period. Only valid when {@link #hasData()}. */
    int cycleDay(int today) {
        return today - lastStart() + 1;
    }

    /** True for a day inside an expected future period (not for days she already logged). */
    boolean isPredictedPeriod(int day) {
        if (!hasData() || day <= periods.get(periods.size() - 1)[1]) return false;
        for (int k = 1; k <= CYCLES_AHEAD; k++) {
            int start = lastStart() + k * cycleLength;
            if (day >= start && day < start + periodLength) return true;
        }
        return false;
    }

    /** True for a day in an estimated fertile window (five days before ovulation to one after). */
    boolean isFertile(int day) {
        if (!hasData()) return false;
        for (int k = 1; k <= CYCLES_AHEAD; k++) {
            int ovulation = lastStart() + k * cycleLength - LUTEAL_DAYS;
            if (day >= ovulation - 5 && day <= ovulation + 1) return true;
        }
        return false;
    }
}
