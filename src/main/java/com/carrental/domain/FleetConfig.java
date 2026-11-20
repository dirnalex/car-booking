package com.carrental.domain;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable fleet capacity configuration specifying how many vehicles of each {@link CarType}
 * exist in the rental fleet.
 *
 * <p>Any {@link CarType} omitted from the input map defaults to capacity {@code 0}.
 */
public record FleetConfig(Map<CarType, Integer> capacities) {

    public FleetConfig {
        Objects.requireNonNull(capacities, "Fleet capacities map must not be null");
        EnumMap<CarType, Integer> normalized = new EnumMap<>(CarType.class);
        for (CarType type : CarType.values()) {
            normalized.put(type, 0);
        }
        for (Map.Entry<CarType, Integer> entry : capacities.entrySet()) {
            CarType type = Objects.requireNonNull(entry.getKey(), "CarType key must not be null");
            Integer capacity = Objects.requireNonNull(
                    entry.getValue(), "Capacity for " + type + " must not be null");
            if (capacity < 0) {
                throw new IllegalArgumentException(
                        "Capacity for %s must be non-negative, got: %d".formatted(type, capacity));
            }
            normalized.put(type, capacity);
        }
        capacities = Collections.unmodifiableMap(normalized);
    }

    public static FleetConfig of(int sedans, int suvs, int vans) {
        EnumMap<CarType, Integer> map = new EnumMap<>(CarType.class);
        map.put(CarType.SEDAN, sedans);
        map.put(CarType.SUV, suvs);
        map.put(CarType.VAN, vans);
        return new FleetConfig(map);
    }

    public int capacityOf(CarType carType) {
        Objects.requireNonNull(carType, "CarType must not be null");
        return capacities.get(carType);
    }
}
