package com.store.inventory.reservation;

import com.store.inventory.api.ProductCategory;
import java.time.Duration;

/**
 * The reservation rules of each product category. This is the only place to touch when
 * marketing adds a category or changes the rules of an existing one.
 */
public final class CategoryPolicies {

    private static final ReservationPolicy STANDARD = ReservationPolicy.unlimited(Duration.ofMinutes(15));
    // Pre-orders are paid by bank transfer, which can take up to a day to clear.
    private static final ReservationPolicy PRE_ORDER = ReservationPolicy.unlimited(Duration.ofHours(24));
    private static final ReservationPolicy FLASH_SALE = ReservationPolicy.limitedTo(2, Duration.ofMinutes(5));

    private CategoryPolicies() {
    }

    public static ReservationPolicy forCategory(ProductCategory category) {
        // No default branch on purpose: a category added to the enum without its rules breaks the build here.
        return switch (category) {
            case STANDARD -> STANDARD;
            case PRE_ORDER -> PRE_ORDER;
            case FLASH_SALE -> FLASH_SALE;
        };
    }
}
