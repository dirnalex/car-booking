package com.carrental.domain;

import java.util.Objects;

/**
 * Outcome of a reservation attempt.
 */
public sealed interface ReservationResult
        permits ReservationResult.Confirmed, ReservationResult.Unavailable {

    record Confirmed(Reservation reservation) implements ReservationResult {
        public Confirmed {
            Objects.requireNonNull(reservation, "Reservation must not be null");
        }
    }

    /**
     * Every car of {@code carType} is already reserved at some point during {@code period}.
     */
    record Unavailable(CarType carType, ReservationPeriod period) implements ReservationResult {
        public Unavailable {
            Objects.requireNonNull(carType, "CarType must not be null");
            Objects.requireNonNull(period, "ReservationPeriod must not be null");
        }
    }
}
