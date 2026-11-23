package com.carrental.service;

import com.carrental.domain.ReservationPeriod;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Reference model for occupancy calculation in tests.
 */
final class BruteForceOccupancy {

    private BruteForceOccupancy() {
    }

    static int peakWithin(Collection<ReservationPeriod> periods, ReservationPeriod window) {
        List<Instant> points = new ArrayList<>();
        points.add(window.start());
        for (ReservationPeriod p : periods) {
            if (p.start().isAfter(window.start()) && p.start().isBefore(window.end())) {
                points.add(p.start());
            }
        }
        int peak = 0;
        for (Instant point : points) {
            peak = Math.max(peak, occupancyAt(periods, point));
        }
        return peak;
    }

    static int occupancyAt(Collection<ReservationPeriod> periods, Instant point) {
        int count = 0;
        for (ReservationPeriod p : periods) {
            if (!p.start().isAfter(point) && point.isBefore(p.end())) {
                count++;
            }
        }
        return count;
    }
}
