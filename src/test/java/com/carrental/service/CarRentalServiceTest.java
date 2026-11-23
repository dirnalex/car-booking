package com.carrental.service;

import com.carrental.domain.CancellationResult;
import com.carrental.domain.CarType;
import com.carrental.domain.FleetConfig;
import com.carrental.domain.Reservation;
import com.carrental.domain.ReservationPeriod;
import com.carrental.domain.ReservationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CarRentalServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-01T10:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static Instant day(int offsetDays) {
        return NOW.plus(Duration.ofDays(offsetDays));
    }

    private static Reservation assertConfirmed(ReservationResult result) {
        return assertInstanceOf(ReservationResult.Confirmed.class, result).reservation();
    }

    private static ReservationResult.Unavailable assertUnavailable(ReservationResult result) {
        return assertInstanceOf(ReservationResult.Unavailable.class, result);
    }

    @Nested
    @DisplayName("Happy path & capacity limits")
    class CapacityLimitsTests {

        @Test
        void reservesUpToConfiguredCapacityAndRejectsNextOverlappingRequest() {
            CarRentalService service = new CarRentalService(FleetConfig.of(3, 2, 1), FIXED_CLOCK);

            Reservation r1 = assertConfirmed(service.reserve(CarType.SEDAN, day(1), 3));
            Reservation r2 = assertConfirmed(service.reserve(CarType.SEDAN, day(1), 3));
            Reservation r3 = assertConfirmed(service.reserve(CarType.SEDAN, day(1), 3));

            ReservationResult fourth = service.reserve(CarType.SEDAN, day(1), 3);
            ReservationResult.Unavailable unavailable = assertUnavailable(fourth);

            assertEquals(CarType.SEDAN, unavailable.carType());
            assertEquals(ReservationPeriod.ofDays(day(1), 3), unavailable.period());
            assertEquals(Optional.of(r1), service.findReservation(r1.id()));
            assertEquals(Optional.of(r2), service.findReservation(r2.id()));
            assertEquals(Optional.of(r3), service.findReservation(r3.id()));
            assertEquals(0, service.availableCount(CarType.SEDAN, day(1), 3));
            assertEquals(3, service.availableCount(CarType.SEDAN, day(4), 3));
        }

        @Test
        void availableCountReflectsPartialOverlapsWithinTheRequestedWindow() {
            CarRentalService service = new CarRentalService(FleetConfig.of(3, 0, 0), FIXED_CLOCK);
            assertConfirmed(service.reserve(CarType.SEDAN, day(1), 2)); // [1, 3)
            assertConfirmed(service.reserve(CarType.SEDAN, day(2), 2)); // [2, 4)

            assertEquals(2, service.availableCount(CarType.SEDAN, day(0), 2)); // peak 1 on [1, 2)
            assertEquals(1, service.availableCount(CarType.SEDAN, day(0), 10)); // peak 2 on [2, 3)
            assertEquals(2, service.availableCount(CarType.SEDAN, day(3), 1)); // only [2, 4)
            assertEquals(3, service.availableCount(CarType.SEDAN, day(4), 1)); // both ended
        }

        @Test
        void allowsReservationStartingAtExactCurrentClockInstant() {
            CarRentalService service = new CarRentalService(FleetConfig.of(1, 0, 0), FIXED_CLOCK);

            Reservation reservation = assertConfirmed(service.reserve(CarType.SEDAN, NOW, 2));

            assertEquals(NOW, reservation.period().start());
            assertEquals(NOW.plus(Duration.ofDays(2)), reservation.period().end());
        }
    }

    @Nested
    @DisplayName("Half-open [start, end) boundary conditions")
    class HalfOpenBoundaryTests {

        @Test
        void allowsBackToBackBookingsOnCapacityOneInForwardOrder() {
            CarRentalService service = new CarRentalService(FleetConfig.of(1, 0, 0), FIXED_CLOCK);

            assertConfirmed(service.reserve(CarType.SEDAN, day(0), 2));
            assertConfirmed(service.reserve(CarType.SEDAN, day(2), 2));
            assertConfirmed(service.reserve(CarType.SEDAN, day(4), 1));

            assertEquals(0, service.availableCount(CarType.SEDAN, day(0), 5));
            assertEquals(1, service.availableCount(CarType.SEDAN, day(5), 1));
        }

        @Test
        void allowsBackToBackBookingsOnCapacityOneInReverseOrder() {
            CarRentalService service = new CarRentalService(FleetConfig.of(1, 0, 0), FIXED_CLOCK);

            assertConfirmed(service.reserve(CarType.SEDAN, day(2), 2));
            assertConfirmed(service.reserve(CarType.SEDAN, day(0), 2));

            assertEquals(0, service.availableCount(CarType.SEDAN, day(0), 4));
            assertEquals(1, service.availableCount(CarType.SEDAN, day(4), 1));
        }

        @Test
        void rejectsOverlapByOneSecondAtEndBoundary() {
            CarRentalService service = new CarRentalService(FleetConfig.of(1, 0, 0), FIXED_CLOCK);

            assertConfirmed(service.reserve(CarType.SEDAN, day(1), 2));
            Instant oneSecondBeforeEnd = day(3).minusSeconds(1);

            assertUnavailable(service.reserve(CarType.SEDAN, oneSecondBeforeEnd, 2));
        }

        @Test
        void rejectsOverlapByOneSecondAtStartBoundary() {
            CarRentalService service = new CarRentalService(FleetConfig.of(1, 0, 0), FIXED_CLOCK);

            assertConfirmed(service.reserve(CarType.SEDAN, day(3), 2));
            Instant endsOneSecondAfterDay3 = day(1).plusSeconds(1);

            assertUnavailable(service.reserve(CarType.SEDAN, endsOneSecondAfterDay3, 2));
        }
    }

    @Nested
    @DisplayName("All overlap topologies on capacity 1")
    class OverlapTopologyTests {

        @Test
        void rejectsPartialLeftOverlap() {
            CarRentalService service = new CarRentalService(FleetConfig.of(1, 0, 0), FIXED_CLOCK);
            assertConfirmed(service.reserve(CarType.SEDAN, day(5), 4)); // [5, 9)

            assertUnavailable(service.reserve(CarType.SEDAN, day(3), 3)); // [3, 6)
        }

        @Test
        void rejectsPartialRightOverlap() {
            CarRentalService service = new CarRentalService(FleetConfig.of(1, 0, 0), FIXED_CLOCK);
            assertConfirmed(service.reserve(CarType.SEDAN, day(5), 4)); // [5, 9)

            assertUnavailable(service.reserve(CarType.SEDAN, day(8), 3)); // [8, 11)
        }

        @Test
        void rejectsFullyContainedInterval() {
            CarRentalService service = new CarRentalService(FleetConfig.of(1, 0, 0), FIXED_CLOCK);
            assertConfirmed(service.reserve(CarType.SEDAN, day(5), 6)); // [5, 11)

            assertUnavailable(service.reserve(CarType.SEDAN, day(7), 2)); // [7, 9)
        }

        @Test
        void rejectsFullyEnclosingInterval() {
            CarRentalService service = new CarRentalService(FleetConfig.of(1, 0, 0), FIXED_CLOCK);
            assertConfirmed(service.reserve(CarType.SEDAN, day(7), 2)); // [7, 9)

            assertUnavailable(service.reserve(CarType.SEDAN, day(5), 6)); // [5, 11)
        }

        @Test
        void rejectsExactMatchInterval() {
            CarRentalService service = new CarRentalService(FleetConfig.of(1, 0, 0), FIXED_CLOCK);
            assertConfirmed(service.reserve(CarType.SEDAN, day(5), 3)); // [5, 8)

            assertUnavailable(service.reserve(CarType.SEDAN, day(5), 3)); // [5, 8)
        }
    }

    @Nested
    @DisplayName("Peak-load sweep-line correctness (anti-naive overlap counting)")
    class PeakLoadTests {

        @Test
        void allowsBookingOverlappingTwoDisjointReservationsWhenPeakOccupancyRemainsWithinCapacity() {
            CarRentalService service = new CarRentalService(FleetConfig.of(2, 0, 0), FIXED_CLOCK);

            // Two disjoint existing bookings: [day 1, day 3) and [day 4, day 6)
            assertConfirmed(service.reserve(CarType.SEDAN, day(1), 2));
            assertConfirmed(service.reserve(CarType.SEDAN, day(4), 2));

            // [day 2, day 5) overlaps BOTH existing bookings, yet peak concurrency is only 2 <= capacity 2
            assertConfirmed(service.reserve(CarType.SEDAN, day(2), 3));
            assertEquals(0, service.availableCount(CarType.SEDAN, day(1), 5));

            // [day 3, day 4) only overlaps [day 2, day 5), so occupancy becomes 2 <= 2 (succeeds)
            assertEquals(1, service.availableCount(CarType.SEDAN, day(3), 1));
            assertConfirmed(service.reserve(CarType.SEDAN, day(3), 1));
            assertEquals(0, service.availableCount(CarType.SEDAN, day(3), 1));

            // Any booking touching [day 2, day 5) now hits capacity 2 and must be rejected
            assertUnavailable(service.reserve(CarType.SEDAN, day(2), 1));
            assertUnavailable(service.reserve(CarType.SEDAN, day(4), 1));
        }

        @Test
        void rejectsBookingWhenInteriorSpikeHitsCapacityEvenIfStartHasFreeCapacity() {
            CarRentalService service = new CarRentalService(FleetConfig.of(2, 0, 0), FIXED_CLOCK);

            // [day 3, day 5) has 2 bookings; day 1 has 0 bookings
            assertConfirmed(service.reserve(CarType.SEDAN, day(3), 2));
            assertConfirmed(service.reserve(CarType.SEDAN, day(3), 2));

            // Starts at day 1 (occupancy 0), spans across the interior spike at day 3..5 (occupancy 2)
            assertUnavailable(service.reserve(CarType.SEDAN, day(1), 6));
        }
    }

    @Nested
    @DisplayName("Car type isolation")
    class CarTypeIsolationTests {

        @Test
        void exhaustingOneCarTypeDoesNotAffectOtherCarTypes() {
            CarRentalService service = new CarRentalService(FleetConfig.of(1, 2, 1), FIXED_CLOCK);

            assertConfirmed(service.reserve(CarType.SEDAN, day(1), 3));
            assertUnavailable(service.reserve(CarType.SEDAN, day(1), 3));

            // SUV and VAN remain independently bookable for the exact same window
            assertConfirmed(service.reserve(CarType.SUV, day(1), 3));
            assertConfirmed(service.reserve(CarType.SUV, day(1), 3));
            assertUnavailable(service.reserve(CarType.SUV, day(1), 3));

            assertConfirmed(service.reserve(CarType.VAN, day(1), 3));
            assertUnavailable(service.reserve(CarType.VAN, day(1), 3));
        }

        @Test
        void cancellingAVanFreesOnlyTheVan() {
            CarRentalService service = new CarRentalService(FleetConfig.of(1, 1, 1), FIXED_CLOCK);
            Reservation sedan = assertConfirmed(service.reserve(CarType.SEDAN, day(1), 2));
            assertConfirmed(service.reserve(CarType.SUV, day(1), 2));
            Reservation van = assertConfirmed(service.reserve(CarType.VAN, day(1), 2));

            // The id doesn't name the car type, so the van is found after sedans and SUVs are checked
            assertEquals(Optional.of(van), service.findReservation(van.id()));
            assertEquals(new CancellationResult.Cancelled(van), service.cancel(van.id()));

            assertEquals(1, service.availableCount(CarType.VAN, day(1), 2));
            assertEquals(0, service.availableCount(CarType.SUV, day(1), 2));
            assertEquals(0, service.availableCount(CarType.SEDAN, day(1), 2));
            assertEquals(Optional.of(sedan), service.findReservation(sedan.id()));
        }
    }

    @Nested
    @DisplayName("Cancellation")
    class CancellationTests {

        @Test
        void cancellingFutureReservationFreesItsCar() {
            CarRentalService service = new CarRentalService(FleetConfig.of(1, 0, 0), FIXED_CLOCK);

            Reservation original = assertConfirmed(service.reserve(CarType.SEDAN, day(1), 4));
            assertUnavailable(service.reserve(CarType.SEDAN, day(2), 2));

            CancellationResult cancelResult = service.cancel(original.id());
            CancellationResult.Cancelled cancelled =
                    assertInstanceOf(CancellationResult.Cancelled.class, cancelResult);

            assertEquals(original, cancelled.reservation());
            assertTrue(service.findReservation(original.id()).isEmpty());
            assertEquals(1, service.availableCount(CarType.SEDAN, day(1), 4));

            // Same and overlapping periods are bookable again
            Reservation rebooked = assertConfirmed(service.reserve(CarType.SEDAN, day(2), 2));
            assertEquals(Optional.of(rebooked), service.findReservation(rebooked.id()));
        }

        @Test
        void cancellingUnknownOrAlreadyCancelledIdReturnsNotFound() {
            CarRentalService service = new CarRentalService(FleetConfig.of(1, 0, 0), FIXED_CLOCK);
            UUID unknownId = UUID.randomUUID();

            CancellationResult unknownResult = service.cancel(unknownId);
            assertEquals(new CancellationResult.NotFound(unknownId), unknownResult);

            Reservation booked = assertConfirmed(service.reserve(CarType.SEDAN, day(1), 2));
            assertInstanceOf(CancellationResult.Cancelled.class, service.cancel(booked.id()));

            // Second cancel is idempotent and does not corrupt capacity deltas
            assertEquals(new CancellationResult.NotFound(booked.id()), service.cancel(booked.id()));
            assertEquals(1, service.availableCount(CarType.SEDAN, day(1), 2));
            // A double-applied reversal would push occupancy to -1 and report 2 free cars
            assertConfirmed(service.reserve(CarType.SEDAN, day(1), 2));
            assertEquals(0, service.availableCount(CarType.SEDAN, day(1), 2));
        }

        @Test
        void cancellingAtOrAfterStartReturnsAlreadyStartedAndKeepsTheCarBooked() {
            MutableClock clock = new MutableClock(NOW);
            CarRentalService service = new CarRentalService(FleetConfig.of(1, 0, 0), clock);
            Reservation booked = assertConfirmed(service.reserve(CarType.SEDAN, day(1), 4)); // [1, 5)

            clock.advance(Duration.ofDays(1)); // now == start: the rental has begun
            assertEquals(new CancellationResult.AlreadyStarted(booked), service.cancel(booked.id()));

            clock.advance(Duration.ofDays(1)); // now = day 2, rental in progress
            assertEquals(new CancellationResult.AlreadyStarted(booked), service.cancel(booked.id()));
            assertEquals(Optional.of(booked), service.findReservation(booked.id()));
            assertUnavailable(service.reserve(CarType.SEDAN, day(3), 1));
        }
    }

    @Nested
    @DisplayName("Reservations over time")
    class LifecycleTests {

        @Test
        void completedReservationIsStillFindableButCannotBeCancelled() {
            MutableClock clock = new MutableClock(NOW);
            CarRentalService service = new CarRentalService(FleetConfig.of(1, 0, 0), clock);
            Reservation finished = assertConfirmed(service.reserve(CarType.SEDAN, day(0), 2)); // [0, 2)
            Reservation ongoing = assertConfirmed(service.reserve(CarType.SEDAN, day(2), 3)); // [2, 5)

            clock.advance(Duration.ofDays(3)); // now = day 3

            assertEquals(Optional.of(finished), service.findReservation(finished.id()));
            assertEquals(new CancellationResult.AlreadyStarted(finished), service.cancel(finished.id()));
            // The ongoing rental still holds the only car until day 5
            assertEquals(Optional.of(ongoing), service.findReservation(ongoing.id()));
            assertEquals(0, service.availableCount(CarType.SEDAN, day(3), 1));
            assertEquals(1, service.availableCount(CarType.SEDAN, day(5), 1));
        }

        @Test
        void longRunningServiceKeepsCorrectCapacityWeekAfterWeek() {
            MutableClock clock = new MutableClock(NOW);
            CarRentalService service = new CarRentalService(FleetConfig.of(2, 0, 0), clock);

            for (int week = 0; week < 200; week++) {
                Instant start = clock.instant();
                assertConfirmed(service.reserve(CarType.SEDAN, start, 3));
                assertConfirmed(service.reserve(CarType.SEDAN, start.plus(Duration.ofDays(1)), 3));
                clock.advance(Duration.ofDays(2)); // inside both rentals
                assertEquals(0, service.availableCount(CarType.SEDAN, clock.instant(), 1));
                clock.advance(Duration.ofDays(5)); // both finished
                assertEquals(2, service.availableCount(CarType.SEDAN, clock.instant(), 7));
            }
        }
    }

    @Nested
    @DisplayName("Randomized comparison with an independent brute-force model")
    class ReferenceModelTests {

        @ParameterizedTest
        @ValueSource(longs = {1, 2, 3, 42, 2026})
        void reserveCancelAndAvailabilityMatchBruteForceModel(long seed) {
            Random random = new Random(seed);
            int capacity = 1 + random.nextInt(3);
            CarRentalService service = new CarRentalService(FleetConfig.of(capacity, 0, 0), FIXED_CLOCK);
            List<Reservation> model = new ArrayList<>();

            for (int op = 0; op < 2_000; op++) {
                String context = "seed=%d op=%d".formatted(seed, op);
                if (!model.isEmpty() && random.nextInt(4) == 0) {
                    Reservation victim = model.remove(random.nextInt(model.size()));
                    assertInstanceOf(CancellationResult.Cancelled.class, service.cancel(victim.id()), context);
                    continue;
                }

                // Starts at least an hour ahead: with the fixed clock, a booking starting at NOW has
                // already started and could not be cancelled.
                Instant start = NOW.plus(Duration.ofHours(1 + random.nextInt(24 * 30)));
                int days = 1 + random.nextInt(5);
                ReservationPeriod candidate = ReservationPeriod.ofDays(start, days);
                List<ReservationPeriod> booked = model.stream().map(Reservation::period).toList();
                int expectedPeak = BruteForceOccupancy.peakWithin(booked, candidate);

                assertEquals(capacity - Math.min(capacity, expectedPeak),
                        service.availableCount(CarType.SEDAN, start, days), context);
                ReservationResult result = service.reserve(CarType.SEDAN, start, days);
                assertEquals(expectedPeak < capacity, result instanceof ReservationResult.Confirmed, context);
                if (result instanceof ReservationResult.Confirmed confirmed) {
                    model.add(confirmed.reservation());
                }
            }
        }
    }

    @Nested
    @DisplayName("Time rules: grace window, no upper limits")
    class TimeRuleTests {

        @Test
        void startInsideGraceWindowIsConfirmedAndMovedToNow() {
            CarRentalService service = new CarRentalService(FleetConfig.of(2, 0, 0), FIXED_CLOCK);
            Instant thirtySecondsAgo = NOW.minusSeconds(30);

            Reservation reservation = assertConfirmed(service.reserve(CarType.SEDAN, thirtySecondsAgo, 2));

            assertEquals(new ReservationPeriod(NOW, thirtySecondsAgo.plus(Duration.ofDays(2))),
                    reservation.period());
            assertEquals(1, service.availableCount(CarType.SEDAN, thirtySecondsAgo, 2));
            assertConfirmed(service.reserve(CarType.SEDAN, NOW.minus(CarRentalService.START_GRACE), 1));
        }

        @Test
        void rejectsStartBeforeGraceWindow() {
            CarRentalService service = new CarRentalService(FleetConfig.of(2, 2, 2), FIXED_CLOCK);
            Instant justTooEarly = NOW.minus(CarRentalService.START_GRACE).minusNanos(1);

            assertThrows(IllegalArgumentException.class, () -> service.reserve(CarType.SEDAN, justTooEarly, 1));
            assertThrows(IllegalArgumentException.class,
                    () -> service.availableCount(CarType.SEDAN, justTooEarly, 1));
            assertThrows(IllegalArgumentException.class,
                    () -> service.reserve(CarType.SEDAN, NOW.minus(Duration.ofDays(1)), 2));
        }

        @Test
        void acceptsAnyLengthAndStartUntilTheEndOverflows() {
            CarRentalService service = new CarRentalService(FleetConfig.of(1, 0, 0), FIXED_CLOCK);

            Reservation longest = assertConfirmed(service.reserve(CarType.SEDAN, day(1), Integer.MAX_VALUE));
            assertEquals(day(1).plus(Duration.ofDays(Integer.MAX_VALUE)), longest.period().end());
            assertUnavailable(service.reserve(CarType.SEDAN, day(100_000), 1));

            assertThrows(IllegalArgumentException.class,
                    () -> service.reserve(CarType.SEDAN, Instant.MAX.minusSeconds(60), 1));
        }
    }

    @Nested
    @DisplayName("Input & fleet configuration validation")
    class ValidationTests {

        @ParameterizedTest
        @ValueSource(ints = {0, -1, -100, Integer.MIN_VALUE})
        void rejectsNonPositiveDays(int invalidDays) {
            CarRentalService service = new CarRentalService(FleetConfig.of(2, 2, 2), FIXED_CLOCK);

            assertThrows(
                    IllegalArgumentException.class,
                    () -> service.reserve(CarType.SEDAN, day(1), invalidDays));
        }

        @Test
        void rejectsNullArguments() {
            CarRentalService service = new CarRentalService(FleetConfig.of(1, 1, 1), FIXED_CLOCK);

            assertThrows(NullPointerException.class, () -> new CarRentalService(null, FIXED_CLOCK));
            assertThrows(NullPointerException.class, () -> new CarRentalService(FleetConfig.of(1, 1, 1), null));
            assertThrows(NullPointerException.class, () -> service.reserve(null, day(1), 1));
            assertThrows(NullPointerException.class, () -> service.reserve(CarType.SEDAN, null, 1));
            assertThrows(NullPointerException.class, () -> service.cancel(null));
            assertThrows(NullPointerException.class, () -> service.findReservation(null));
        }

        @Test
        void rejectsNegativeFleetCapacityAndNullMapEntries() {
            assertThrows(IllegalArgumentException.class, () -> FleetConfig.of(-1, 2, 2));
            assertThrows(IllegalArgumentException.class, () -> FleetConfig.of(2, -5, 2));
            assertThrows(NullPointerException.class, () -> new FleetConfig(null));

            Map<CarType, Integer> withNullValue = new HashMap<>();
            withNullValue.put(CarType.SEDAN, null);
            assertThrows(NullPointerException.class, () -> new FleetConfig(withNullValue));
        }

        @Test
        void handlesZeroCapacityCarTypeGracefully() {
            // Explicitly 0 for VAN; omitted from map defaults to 0 for SUV
            CarRentalService service =
                    new CarRentalService(new FleetConfig(Map.of(CarType.SEDAN, 1, CarType.VAN, 0)), FIXED_CLOCK);

            assertUnavailable(service.reserve(CarType.VAN, day(1), 1));
            assertUnavailable(service.reserve(CarType.SUV, day(1), 1));
            assertConfirmed(service.reserve(CarType.SEDAN, day(1), 1));
        }

        @Test
        void reservationPeriodRejectsEmptyAndOverflowingPeriods() {
            assertThrows(IllegalArgumentException.class, () -> new ReservationPeriod(day(1), day(1)));
            assertThrows(IllegalArgumentException.class,
                    () -> ReservationPeriod.ofDays(Instant.MAX.minusSeconds(60), 1));
        }
    }
}
