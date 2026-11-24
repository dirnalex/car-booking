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
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Reservations and capacity tracking for a single {@link CarType}.
 */
final class CarTypeInventory {

    private final CarType carType;
    private final int capacity;
    private final OccupancyTimeline timeline = new OccupancyTimeline();
    private final Map<UUID, Reservation> reservations = new HashMap<>();
    private final ReentrantLock lock = new ReentrantLock();

    CarTypeInventory(CarType carType, int capacity) {
        this.carType = carType;
        this.capacity = capacity;
    }

    ReservationResult tryReserve(ReservationPeriod period) {
        lock.lock();
        try {
            if (timeline.peakOccupancy(period) >= capacity) {
                return new ReservationResult.Unavailable(carType, period);
            }
            timeline.add(period);
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
            return capacity - timeline.peakOccupancy(period);
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
            timeline.remove(reservation.period());
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
}
