package com.store.inventory.reservation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.store.inventory.MutableClock;
import com.store.inventory.RecordingAlertListener;
import com.store.inventory.RecordingAlertListener.Alert;
import com.store.inventory.api.InventoryService;
import com.store.inventory.api.OrderLimitExceededException;
import com.store.inventory.api.ProductCategory;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The service takes its rules from outside: these tests run it with rules that are not the store's.
 */
class ReservationServiceTest {

    private final MutableClock clock = new MutableClock();
    private final RecordingAlertListener listener = new RecordingAlertListener();

    @Test
    void appliesWhateverPolicyTheCategoryHas() {
        ReservationPolicy oneUnitForOneMinute = ReservationPolicy.limitedTo(1, Duration.ofMinutes(1));
        InventoryService service = new ReservationService(clock, category -> oneUnitForOneMinute, listener, 5);
        service.registerProduct("SKU-1", ProductCategory.STANDARD);
        service.addStock("SKU-1", 10);

        assertThrows(OrderLimitExceededException.class, () -> service.reserve("ORDER-1", "SKU-1", 2));
        service.reserve("ORDER-2", "SKU-1", 1);
        assertEquals(9, service.available("SKU-1"));

        clock.advance(Duration.ofMinutes(1));
        assertEquals(10, service.available("SKU-1"));
    }

    @Test
    void alertsAtTheConfiguredThreshold() {
        InventoryService service = new ReservationService(clock, CategoryPolicies::forCategory, listener, 20);
        service.registerProduct("SKU-1", ProductCategory.STANDARD);
        service.addStock("SKU-1", 22);

        service.reserve("ORDER-1", "SKU-1", 1);
        service.reserve("ORDER-2", "SKU-1", 1);

        assertEquals(List.of(new Alert("SKU-1", 20)), listener.alerts());
    }

    @Test
    void rejectsPoliciesThatMakeNoSense() {
        assertThrows(IllegalArgumentException.class, () -> ReservationPolicy.unlimited(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> ReservationPolicy.limitedTo(0, Duration.ofMinutes(5)));
    }
}
