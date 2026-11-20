package com.carrental.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * A confirmed reservation of one car of {@code carType} for {@code period}.
 */
public record Reservation(UUID id, CarType carType, ReservationPeriod period) {

    public Reservation {
        Objects.requireNonNull(id, "Reservation id must not be null");
        Objects.requireNonNull(carType, "CarType must not be null");
        Objects.requireNonNull(period, "ReservationPeriod must not be null");
    }
}
