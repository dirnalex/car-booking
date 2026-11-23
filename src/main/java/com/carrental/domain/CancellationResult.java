package com.carrental.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Outcome of a cancellation request.
 */
public sealed interface CancellationResult
        permits CancellationResult.Cancelled, CancellationResult.NotFound, CancellationResult.AlreadyStarted {

    record Cancelled(Reservation reservation) implements CancellationResult {
        public Cancelled {
            Objects.requireNonNull(reservation, "Reservation must not be null");
        }
    }

    /**
     * No reservation with this id exists, or it was already cancelled.
     */
    record NotFound(UUID reservationId) implements CancellationResult {
        public NotFound {
            Objects.requireNonNull(reservationId, "Reservation id must not be null");
        }
    }

    /**
     * The rental period has already started or ended.
     */
    record AlreadyStarted(Reservation reservation) implements CancellationResult {
        public AlreadyStarted {
            Objects.requireNonNull(reservation, "Reservation must not be null");
        }
    }
}
