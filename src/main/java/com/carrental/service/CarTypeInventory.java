package com.carrental.service;

import com.carrental.domain.CancellationResult;
import com.carrental.domain.CarType;
import com.carrental.domain.Reservation;
import com.carrental.domain.ReservationPeriod;
import com.carrental.domain.ReservationResult;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Reservations and capacity tracking for a single {@link CarType}.
 */
final class CarTypeInventory {

    private final CarType carType;
    private final int capacity;
    private final TreeMap<Instant, Integer> levels = new TreeMap<>();
    private final Map<UUID, Reservation> reservations = new HashMap<>();
    private final ReentrantLock lock = new ReentrantLock();

    CarTypeInventory(CarType carType, int capacity) {
        this.carType = carType;
        this.capacity = capacity;
    }

    ReservationResult tryReserve(ReservationPeriod period) {
        lock.lock();
        try {
            if (peakOccupancy(period) >= capacity) {
                return new ReservationResult.Unavailable(carType, period);
            }
            shift(period, 1);
            Reservation reservation = new Reservation(UUID.randomUUID(), carType, period);
            reservations.put(reservation.id(), reservation);
            return new ReservationResult.Confirmed(reservation);
        } finally {
            lock.unlock();
        }
    }

    int availableCount(ReservationPeriod period) {
        lock.lock();
        try {
            return capacity - peakOccupancy(period);
        } finally {
            lock.unlock();
        }
    }

    CancellationResult cancel(UUID reservationId, Instant now) {
        lock.lock();
        try {
            Reservation reservation = reservations.get(reservationId);
            if (reservation == null) {
                return new CancellationResult.NotFound(reservationId);
            }
            if (!now.isBefore(reservation.period().start())) {
                return new CancellationResult.AlreadyStarted(reservation);
            }
            reservations.remove(reservationId);
            shift(reservation.period(), -1);
            return new CancellationResult.Cancelled(reservation);
        } finally {
            lock.unlock();
        }
    }

    Optional<Reservation> find(UUID reservationId) {
        lock.lock();
        try {
            return Optional.ofNullable(reservations.get(reservationId));
        } finally {
            lock.unlock();
        }
    }

    private int peakOccupancy(ReservationPeriod period) {
        int peak = levelAt(period.start());
        for (int level : levels.subMap(period.start(), false, period.end(), false).values()) {
            peak = Math.max(peak, level);
        }
        return peak;
    }

    private void shift(ReservationPeriod period, int delta) {
        ensureBreakpoint(period.end());
        ensureBreakpoint(period.start());
        levels.subMap(period.start(), true, period.end(), false).replaceAll((time, level) -> level + delta);
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
