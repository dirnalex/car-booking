package com.carrental.service;

import com.carrental.domain.ReservationPeriod;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OccupancyTimelineTest {

    private static final Instant DAY_0 = Instant.parse("2026-06-01T10:00:00Z");

    private static ReservationPeriod days(int fromDay, int toDay) {
        return new ReservationPeriod(DAY_0.plus(Duration.ofDays(fromDay)), DAY_0.plus(Duration.ofDays(toDay)));
    }

    @Test
    void backToBackPeriodsDoNotOverlap() {
        OccupancyTimeline timeline = new OccupancyTimeline();
        timeline.add(days(0, 2));
        timeline.add(days(2, 4));

        assertEquals(1, timeline.peakOccupancy(days(0, 4)));
        assertEquals(1, timeline.peakOccupancy(days(1, 2)));
        assertEquals(0, timeline.peakOccupancy(days(4, 6))); // the second period has ended at day 4
    }

    @Test
    void peakIsFoundInsideTheWindowNotOnlyAtItsStart() {
        OccupancyTimeline timeline = new OccupancyTimeline();
        timeline.add(days(1, 3));
        timeline.add(days(2, 4));

        // Nothing is booked at day 0, but both periods overlap on [2, 3)
        assertEquals(2, timeline.peakOccupancy(days(0, 5)));
        assertEquals(1, timeline.peakOccupancy(days(3, 5)));
        assertEquals(0, timeline.peakOccupancy(days(0, 1)));
    }

    @Test
    void removingEveryAddedPeriodLeavesNoKeysBehind() {
        OccupancyTimeline timeline = new OccupancyTimeline();
        timeline.add(days(0, 2));
        timeline.add(days(2, 4));
        timeline.add(days(1, 3));
        assertFalse(timeline.isEmpty());

        timeline.remove(days(2, 4));
        timeline.remove(days(0, 2));
        timeline.remove(days(1, 3));

        assertTrue(timeline.isEmpty());
        assertEquals(0, timeline.peakOccupancy(days(0, 4)));
    }

    @Test
    void addingThenRemovingTheSamePeriodLeavesTheMapEmpty() {
        OccupancyTimeline timeline = new OccupancyTimeline();
        timeline.add(days(1, 3));
        timeline.remove(days(1, 3));

        assertTrue(timeline.isEmpty());
    }

    @Test
    void adjacentPeriodsMergeInsteadOfKeepingARedundantBreakpoint() {
        OccupancyTimeline laterFirst = new OccupancyTimeline();
        laterFirst.add(days(0, 2));
        assertEquals(2, laterFirst.breakpointCount()); // level 1 from day 0, back to 0 at day 2
        laterFirst.add(days(2, 4));
        // The level is 1 on all of [0, 4), so day 2 is no longer a change: only days 0 and 4 remain
        assertEquals(2, laterFirst.breakpointCount());
        assertEquals(1, laterFirst.peakOccupancy(days(0, 4)));

        OccupancyTimeline earlierFirst = new OccupancyTimeline();
        earlierFirst.add(days(2, 4));
        earlierFirst.add(days(0, 2));
        assertEquals(2, earlierFirst.breakpointCount());

        earlierFirst.add(days(1, 2)); // levels 1, 2, 1, 0 at days 0, 1, 2, 4
        assertEquals(4, earlierFirst.breakpointCount());
        assertEquals(2, earlierFirst.peakOccupancy(days(0, 4)));
    }
}
