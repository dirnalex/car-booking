package com.carrental.service;

import com.carrental.domain.ReservationPeriod;

import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

/**
 * Occupancy levels over time for one car type. Not thread-safe.
 */
final class OccupancyTimeline {

    /**
     * Cars in use from each key until the next key, and 0 before the first key. No key repeats the
     * level just before it, so every key is a real change.
     */
    private final TreeMap<Instant, Integer> levels = new TreeMap<>();

    /**
     * Highest occupancy at any instant in {@code [start, end)}.
     */
    int peakOccupancy(ReservationPeriod period) {
        int peak = levelAt(period.start());
        for (int level : levels.subMap(period.start(), false, period.end(), false).values()) {
            peak = Math.max(peak, level);
        }
        return peak;
    }

    void add(ReservationPeriod period) {
        shift(period, 1);
    }

    /**
     * Reverses an earlier {@link #add} of the same period.
     */
    void remove(ReservationPeriod period) {
        shift(period, -1);
    }

    boolean isEmpty() {
        return levels.isEmpty();
    }

    int breakpointCount() {
        return levels.size();
    }

    private void shift(ReservationPeriod period, int delta) {
        // Both breakpoints are inserted before any level changes, so each copies the old level there
        ensureBreakpoint(period.end());
        ensureBreakpoint(period.start());
        levels.subMap(period.start(), true, period.end(), false).replaceAll((time, level) -> level + delta);
        // Levels inside the period moved together, so only its two edges can have become redundant
        dropIfRedundant(period.start());
        dropIfRedundant(period.end());
    }

    private int levelAt(Instant time) {
        Map.Entry<Instant, Integer> entry = levels.floorEntry(time);
        return entry == null ? 0 : entry.getValue();
    }

    private void ensureBreakpoint(Instant time) {
        if (!levels.containsKey(time)) {
            levels.put(time, levelAt(time));
        }
    }

    private void dropIfRedundant(Instant time) {
        Map.Entry<Instant, Integer> previous = levels.lowerEntry(time);
        int levelBefore = previous == null ? 0 : previous.getValue();
        if (levels.get(time) == levelBefore) {
            levels.remove(time);
        }
    }
}
