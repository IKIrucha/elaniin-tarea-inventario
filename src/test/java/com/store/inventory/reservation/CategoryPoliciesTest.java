package com.store.inventory.reservation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.store.inventory.api.ProductCategory;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class CategoryPoliciesTest {

    @ParameterizedTest
    @EnumSource(ProductCategory.class)
    void everyCategoryHasAPolicy(ProductCategory category) {
        assertNotNull(CategoryPolicies.forCategory(category));
    }

    @Test
    void standardHasFifteenMinutesToPayAndNoLimit() {
        assertEquals(
                ReservationPolicy.unlimited(Duration.ofMinutes(15)),
                CategoryPolicies.forCategory(ProductCategory.STANDARD));
    }

    @Test
    void preOrderHasADayToPayAndNoLimit() {
        assertEquals(
                ReservationPolicy.unlimited(Duration.ofHours(24)),
                CategoryPolicies.forCategory(ProductCategory.PRE_ORDER));
    }

    @Test
    void flashSaleHasFiveMinutesToPayAndTwoUnitsPerOrder() {
        assertEquals(
                ReservationPolicy.limitedTo(2, Duration.ofMinutes(5)),
                CategoryPolicies.forCategory(ProductCategory.FLASH_SALE));
    }
}
