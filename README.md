# Car Rental System

An in-memory car rental system in Java 21. You reserve a car of a given type (sedan, SUV or van) from a date and time for a number of days. Each type has a limited number of cars, and a reservation is confirmed only if a car of that type is free for the whole period.

## Requirements

JDK 21 or later and Maven 3.9 or later.

## Build and test

```sh
mvn test
```

## Usage

```java
CarRentalService service = new CarRentalService(FleetConfig.of(3, 2, 1)); // 3 sedans, 2 SUVs, 1 van

Instant tomorrow = Instant.now().plus(Duration.ofDays(1));
switch (service.reserve(CarType.SUV, tomorrow, 3)) {
    case ReservationResult.Confirmed confirmed -> System.out.println("Booked " + confirmed.reservation().id());
    case ReservationResult.Unavailable unavailable -> System.out.println("No SUV is free for that period");
}
```

`CarRentalService` is thread-safe and offers:

- `reserve(carType, start, days)`: `Confirmed` with the reservation, or `Unavailable` if every car of that type is taken at some point of the period.
- `availableCount(carType, start, days)`: how many such reservations would be confirmed right now.
- `cancel(id)`: `Cancelled`; `AlreadyStarted` once the rental has begun; or `NotFound` for an unknown or already cancelled id.
- `findReservation(id)`: the reservation, unless it was cancelled.

Null arguments throw `NullPointerException`. Fewer than 1 day, a start more than 5 minutes in the past, or an end that doesn't fit in an `Instant` throw `IllegalArgumentException`.

## Rules

- Times are UTC `Instant`s, and a day is exactly 24 hours.
- A start up to 5 minutes in the past is accepted and moved to now.
- A rental lasts at least 1 day, with no upper limit.
- A reservation is for a car type, not a specific car.
- A period is half-open, `[start, end)`: a car returned at a given instant can be booked again from that instant.
- A reservation can be cancelled until its start time.

## Structure

```text
com.carrental
├── domain
│   ├── CarType             SEDAN, SUV, VAN
│   ├── FleetConfig         number of cars per type
│   ├── ReservationPeriod   half-open [start, end) in UTC
│   ├── Reservation         UUID id + car type + period
│   ├── ReservationResult   Confirmed | Unavailable
│   └── CancellationResult  Cancelled | NotFound | AlreadyStarted
└── service
    ├── CarRentalService    entry point: validation, clock, reserve / availableCount / cancel / findReservation
    ├── CarTypeInventory    one car type: its reservations and its lock
    └── OccupancyTimeline   occupancy levels over time for one car type
```
