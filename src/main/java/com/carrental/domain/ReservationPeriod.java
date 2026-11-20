package com.carrental.domain;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Immutable half-open UTC reservation interval {@code [start, end)}.
 */
public record ReservationPeriod(Instant start, Instant end) {

    public ReservationPeriod {
        Objects.requireNonNull(start, "Start instant must not be null");
        Objects.requireNonNull(end, "End instant must not be null");
        if (!start.isBefore(end)) {
            throw new IllegalArgumentException(
                    "Start instant must be strictly before end instant: start=%s, end=%s"
                            .formatted(start, end));
        }
    }

    /**
     * Creates a reservation period starting at {@code start} for {@code days} exact 24-hour days.
     *
     * @param start UTC start instant (inclusive)
     * @param days  positive number of 24-hour days
     * @return immutable half-open interval {@code [start, start + days)}
     * @throws NullPointerException     if {@code start} is null
     * @throws IllegalArgumentException if {@code days <= 0} or the computed end overflows {@link Instant}
     */
    public static ReservationPeriod ofDays(Instant start, int days) {
        Objects.requireNonNull(start, "Start instant must not be null");
        if (days <= 0) {
            throw new IllegalArgumentException("Number of days must be positive, got: " + days);
        }
        try {
            Instant end = start.plus(Duration.ofDays(days));
            return new ReservationPeriod(start, end);
        } catch (ArithmeticException | DateTimeException ex) {
            throw new IllegalArgumentException(
                    "Reservation period overflows Instant range: start=%s, days=%d"
                            .formatted(start, days),
                    ex);
        }
    }
}
