package com.store.inventory;

import com.store.inventory.api.InventoryService;
import com.store.inventory.api.StockAlertListener;
import com.store.inventory.reservation.CategoryPolicies;
import com.store.inventory.reservation.ReservationService;
import java.time.Clock;

/**
 * Entry point used by our automated tests. Keep this signature exactly as it is,
 * and build your implementation here.
 */
public final class Inventory {

    /** Purchasing is alerted when a product has this many available units or fewer. */
    private static final int LOW_STOCK_THRESHOLD = 5;

    private Inventory() {
    }

    public static InventoryService create(Clock clock, StockAlertListener alertListener) {
        return new ReservationService(clock, CategoryPolicies::forCategory, alertListener, LOW_STOCK_THRESHOLD);
    }
}
