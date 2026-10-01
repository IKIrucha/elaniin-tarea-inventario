package com.store.inventory.reservation;

import java.time.Duration;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * Business rules that apply to the reservations of one product category.
 *
 * @param paymentWindow how long the customer has to pay before the reservation is released
 * @param maxUnitsPerOrder maximum units a single order may reserve, empty when there is no limit
 */
public record ReservationPolicy(Duration paymentWindow, OptionalInt maxUnitsPerOrder) {

    public ReservationPolicy {
        Objects.requireNonNull(paymentWindow, "paymentWindow");
        Objects.requireNonNull(maxUnitsPerOrder, "maxUnitsPerOrder");
        if (paymentWindow.isZero() || paymentWindow.isNegative()) {
            throw new IllegalArgumentException("paymentWindow must be positive: " + paymentWindow);
        }
        if (maxUnitsPerOrder.isPresent() && maxUnitsPerOrder.getAsInt() <= 0) {
            throw new IllegalArgumentException("maxUnitsPerOrder must be positive: " + maxUnitsPerOrder.getAsInt());
        }
    }

    public static ReservationPolicy unlimited(Duration paymentWindow) {
        return new ReservationPolicy(paymentWindow, OptionalInt.empty());
    }

    public static ReservationPolicy limitedTo(int maxUnitsPerOrder, Duration paymentWindow) {
        return new ReservationPolicy(paymentWindow, OptionalInt.of(maxUnitsPerOrder));
    }
}
