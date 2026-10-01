package com.store.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.store.inventory.api.InventoryService;
import com.store.inventory.api.ProductCategory;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ConfirmTest {

    private MutableClock clock;
    private InventoryService service;

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        service = Inventory.create(clock, new RecordingAlertListener());
        service.registerProduct("SKU-1", ProductCategory.STANDARD);
        service.addStock("SKU-1", 10);
    }

    @Test
    void confirmingDoesNotChangeAvailableUnits() {
        service.reserve("ORDER-1", "SKU-1", 4);

        service.confirm("ORDER-1");

        assertEquals(6, service.available("SKU-1"));
    }

    @Test
    void canConfirmRightBeforeTheTimeToPayRunsOut() {
        service.reserve("ORDER-1", "SKU-1", 4);
        clock.advance(Duration.ofMinutes(15).minusMillis(1));

        service.confirm("ORDER-1");
        clock.advance(Duration.ofMinutes(1));

        assertEquals(6, service.available("SKU-1"));
    }

    @Test
    void cannotConfirmUnknownOrder() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> service.confirm("UNKNOWN"));
        assertEquals(
                "Order UNKNOWN has no active reservation: it was never reserved or its time to pay ran out",
                e.getMessage());
    }

    @Test
    void cannotConfirmAnOrderWhoseReservationFailed() {
        assertThrows(RuntimeException.class, () -> service.reserve("ORDER-1", "SKU-1", 11));

        assertThrows(IllegalStateException.class, () -> service.confirm("ORDER-1"));
    }

    @Test
    void cannotConfirmTheSameOrderTwice() {
        service.reserve("ORDER-1", "SKU-1", 4);
        service.confirm("ORDER-1");

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> service.confirm("ORDER-1"));
        assertEquals("Order ORDER-1 is already confirmed", e.getMessage());
        assertEquals(6, service.available("SKU-1"));
    }

    @Test
    void soldUnitsAreNotCountedAgainAfterRestocking() {
        service.reserve("ORDER-1", "SKU-1", 4);
        service.confirm("ORDER-1");

        service.addStock("SKU-1", 5);

        assertEquals(11, service.available("SKU-1"));
    }
}
