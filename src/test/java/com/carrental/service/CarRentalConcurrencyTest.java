package com.carrental.service;

import com.carrental.domain.CarType;
import com.carrental.domain.FleetConfig;
import com.carrental.domain.ReservationPeriod;
import com.carrental.domain.ReservationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntUnaryOperator;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Concurrency tests for {@link CarRentalService}.
 */
class CarRentalConcurrencyTest {

    private static final Instant NOW = Instant.parse("2026-06-01T10:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final ReservationPeriod WHOLE_HORIZON = ReservationPeriod.ofDays(NOW, 30);

    private static Instant day(int offsetDays) {
        return NOW.plus(Duration.ofDays(offsetDays));
    }

    @Test
    @DisplayName("50 concurrent threads competing for 5 sedans yield exactly 5 confirmed and 45 unavailable")
    void neverOverbooksUnderHighContention() throws Exception {
        int threads = 50;
        int capacity = 5;

        for (int round = 0; round < 10; round++) {
            CarRentalService service = new CarRentalService(FleetConfig.of(capacity, 0, 0), FIXED_CLOCK);
            List<Callable<ReservationResult>> tasks = new ArrayList<>(threads);
            for (int i = 0; i < threads; i++) {
                final int offsetHours = i % 12; // all 3-day intervals overlap on [day+12h, day+72h)
                tasks.add(() -> service.reserve(CarType.SEDAN, NOW.plus(Duration.ofHours(offsetHours)), 3));
            }

            List<ReservationPeriod> confirmed = new ArrayList<>();
            int unavailableCount = 0;
            for (ReservationResult result : runSimultaneously(tasks)) {
                switch (result) {
                    case ReservationResult.Confirmed c -> {
                        confirmed.add(c.reservation().period());
                        assertTrue(service.findReservation(c.reservation().id()).isPresent());
                    }
                    case ReservationResult.Unavailable ignored -> unavailableCount++;
                }
            }

            assertEquals(capacity, confirmed.size(), "Round " + round + " confirmed count mismatch");
            assertEquals(threads - capacity, unavailableCount, "Round " + round + " unavailable count mismatch");
            assertEquals(capacity, BruteForceOccupancy.peakWithin(confirmed, WHOLE_HORIZON));
            assertEquals(0, service.availableCount(CarType.SEDAN, NOW.plus(Duration.ofHours(12)), 2));
        }
    }

    @Test
    @DisplayName("Mixed overlapping intervals under concurrency never exceed fleet capacity")
    void preservesPeakOccupancyInvariantAcrossVariedIntervals() throws Exception {
        int threads = 60;
        int capacity = 4;
        IntUnaryOperator startDay = i -> i % 6;
        IntUnaryOperator durationDays = i -> 1 + (i % 4);
        CarRentalService service = new CarRentalService(FleetConfig.of(0, capacity, 0), FIXED_CLOCK);

        List<Callable<ReservationResult>> tasks = new ArrayList<>(threads);
        for (int i = 0; i < threads; i++) {
            Instant start = day(startDay.applyAsInt(i));
            int days = durationDays.applyAsInt(i);
            tasks.add(() -> service.reserve(CarType.SUV, start, days));
        }

        List<ReservationPeriod> confirmed = new ArrayList<>();
        for (ReservationResult result : runSimultaneously(tasks)) {
            if (result instanceof ReservationResult.Confirmed c) {
                confirmed.add(c.reservation().period());
            }
        }

        int peak = BruteForceOccupancy.peakWithin(confirmed, WHOLE_HORIZON);
        assertTrue(peak <= capacity, "Peak occupancy " + peak + " exceeded capacity " + capacity);

        // Only day-0 bookings cover NOW. A one-day request [0, 1) can only be blocked by other day-0
        // bookings, so with more such requests than cars at least one is rejected, which means day 0
        // is full whatever order the threads ran in. Longer day-0 requests prove nothing on their own:
        // bookings on days 1 and 2 can block them.
        long oneDayRequestsOnDay0 = IntStream.range(0, threads)
                .filter(i -> startDay.applyAsInt(i) == 0 && durationDays.applyAsInt(i) == 1)
                .count();
        assertTrue(oneDayRequestsOnDay0 > capacity, "Setup needs more [0, 1) requests than cars");
        assertEquals(capacity, BruteForceOccupancy.occupancyAt(confirmed, NOW));

        // The service's own view must agree with the independent count at every day
        for (int d = 0; d < 9; d++) {
            Instant point = day(d);
            assertEquals(capacity - BruteForceOccupancy.occupancyAt(confirmed, point),
                    service.availableCount(CarType.SUV, point, 1), "day " + d);
        }
    }

    /**
     * Starts every task on its own thread, releases them together through a start gate, and returns
     * their results in submission order.
     */
    private static <T> List<T> runSimultaneously(List<Callable<T>> tasks) throws Exception {
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch startGate = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(tasks.size())) {
            List<Future<T>> futures = new ArrayList<>(tasks.size());
            for (Callable<T> task : tasks) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    startGate.await();
                    return task.call();
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS), "Worker threads failed to reach barrier");
            startGate.countDown();

            List<T> results = new ArrayList<>(tasks.size());
            for (Future<T> future : futures) {
                results.add(future.get(10, TimeUnit.SECONDS));
            }
            return results;
        }
    }
}
