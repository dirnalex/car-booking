package com.carrental.service;

import com.carrental.domain.CancellationResult;
import com.carrental.domain.CarType;
import com.carrental.domain.FleetConfig;
import com.carrental.domain.Reservation;
import com.carrental.domain.ReservationPeriod;
import com.carrental.domain.ReservationResult;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Thread-safe entry point for reserving, querying and cancelling rental cars.
 *
 * <p>Time rules: a rental lasts a whole number of 24-hour days, in UTC. A start up to
 * {@link #START_GRACE} in the past is accepted and moved to now. There is no upper limit on the
 * length of a rental or on how far ahead it starts.
 */
public final class CarRentalService {

    public static final Duration START_GRACE = Duration.ofMinutes(5);

    private final Map<CarType, CarTypeInventory> inventories = new EnumMap<>(CarType.class);

    public CarRentalService(FleetConfig fleetConfig) {
        Objects.requireNonNull(fleetConfig, "FleetConfig must not be null");
        for (CarType type : CarType.values()) {
            inventories.put(type, new CarTypeInventory(type, fleetConfig.capacityOf(type)));
        }
    }

    /**
     * Reserves a car of {@code carType} from {@code start} for {@code days} days.
     *
     * @return {@link ReservationResult.Confirmed} with a reservation for
     *     {@code [max(start, now), start + days)}, or {@link ReservationResult.Unavailable} if every
     *     car of that type is taken at some point of that period
     * @throws IllegalArgumentException if {@code days} is less than 1, {@code start} is more than
     *     {@link #START_GRACE} in the past, or the end doesn't fit in an {@link Instant}
     */
    public ReservationResult reserve(CarType carType, Instant start, int days) {
        Objects.requireNonNull(carType, "CarType must not be null");
        return inventories.get(carType).tryReserve(effectivePeriod(start, days));
    }

    /**
     * How many identical {@link #reserve} requests would be confirmed right now. Same validation
     * and period as {@code reserve}. Another thread can book in the meantime, so the answer is a
     * snapshot, not a hold.
     */
    public int availableCount(CarType carType, Instant start, int days) {
        Objects.requireNonNull(carType, "CarType must not be null");
        return inventories.get(carType).availableCount(effectivePeriod(start, days));
    }

    /**
     * Cancels a reservation that has not started yet and frees its car. The id doesn't say which
     * car type holds the reservation, so each type is asked in turn, one lock at a time.
     *
     * @return {@link CancellationResult.Cancelled}; {@link CancellationResult.AlreadyStarted} once
     *     the rental period has begun; or {@link CancellationResult.NotFound} for an unknown or
     *     already-cancelled id
     */
    public CancellationResult cancel(UUID reservationId) {
        Objects.requireNonNull(reservationId, "Reservation id must not be null");
        Instant now = Instant.now();
        for (CarTypeInventory inventory : inventories.values()) {
            CancellationResult result = inventory.cancel(reservationId, now);
            if (!(result instanceof CancellationResult.NotFound)) {
                return result;
            }
        }
        return new CancellationResult.NotFound(reservationId);
    }

    /**
     * Looks up a reservation that has not been cancelled, including started and completed ones.
     */
    public Optional<Reservation> findReservation(UUID reservationId) {
        Objects.requireNonNull(reservationId, "Reservation id must not be null");
        for (CarTypeInventory inventory : inventories.values()) {
            Optional<Reservation> found = inventory.find(reservationId);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    private ReservationPeriod effectivePeriod(Instant start, int days) {
        Objects.requireNonNull(start, "Start instant must not be null");
        Instant now = Instant.now();
        if (start.isBefore(now.minus(START_GRACE))) {
            throw new IllegalArgumentException("Start %s is in the past (now=%s)".formatted(start, now));
        }
        ReservationPeriod requested = ReservationPeriod.ofDays(start, days);
        return start.isBefore(now) ? new ReservationPeriod(now, requested.end()) : requested;
    }
}
