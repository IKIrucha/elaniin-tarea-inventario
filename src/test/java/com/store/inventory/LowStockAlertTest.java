package com.store.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.store.inventory.RecordingAlertListener.Alert;
import com.store.inventory.api.InsufficientStockException;
import com.store.inventory.api.InventoryService;
import com.store.inventory.api.ProductCategory;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LowStockAlertTest {

    private MutableClock clock;
    private RecordingAlertListener listener;
    private InventoryService service;
    /** How many of the next alerts the channel will fail to deliver. */
    private int channelFailures;

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        listener = new RecordingAlertListener();
        service = Inventory.create(clock, (sku, availableUnits) -> {
            if (channelFailures > 0) {
                channelFailures--;
                throw new IllegalStateException("mail server is down");
            }
            listener.onLowStock(sku, availableUnits);
        });
        service.registerProduct("SKU-1", ProductCategory.STANDARD);
        service.addStock("SKU-1", 10);
    }

    @Test
    void noAlertWhileMoreThanFiveUnitsAreAvailable() {
        service.reserve("ORDER-1", "SKU-1", 4);

        assertEquals(List.of(), listener.alerts());
    }

    @Test
    void alertsWhenFiveUnitsAreLeft() {
        service.reserve("ORDER-1", "SKU-1", 5);

        assertEquals(List.of(new Alert("SKU-1", 5)), listener.alerts());
    }

    @Test
    void alertsWithTheUnitsLeftWhenAvailabilityDropsBelowFive() {
        service.reserve("ORDER-1", "SKU-1", 10);

        assertEquals(List.of(new Alert("SKU-1", 0)), listener.alerts());
    }

    @Test
    void doesNotRepeatTheAlertWhileTheProductIsNotRestocked() {
        service.reserve("ORDER-1", "SKU-1", 5);
        service.reserve("ORDER-2", "SKU-1", 1);
        service.confirm("ORDER-1");
        service.reserve("ORDER-3", "SKU-1", 4);

        assertEquals(List.of(new Alert("SKU-1", 5)), listener.alerts());
    }

    @Test
    void doesNotRepeatTheAlertWhenExpiredUnitsAreReservedAgain() {
        service.reserve("ORDER-1", "SKU-1", 6);
        clock.advance(Duration.ofMinutes(15));

        service.reserve("ORDER-2", "SKU-1", 6);

        assertEquals(List.of(new Alert("SKU-1", 4)), listener.alerts());
    }

    @Test
    void alertsAgainAfterTheProductIsRestocked() {
        service.reserve("ORDER-1", "SKU-1", 5);
        service.addStock("SKU-1", 10);
        service.reserve("ORDER-2", "SKU-1", 9);
        service.reserve("ORDER-3", "SKU-1", 1);

        assertEquals(List.of(new Alert("SKU-1", 5), new Alert("SKU-1", 5)), listener.alerts());
    }

    @Test
    void restockingDoesNotAlertByItself() {
        service.reserve("ORDER-1", "SKU-1", 8);
        service.addStock("SKU-1", 1);

        assertEquals(List.of(new Alert("SKU-1", 2)), listener.alerts());
    }

    @Test
    void alertsAgainOnTheNextReservationWhenARestockLeavesStockLow() {
        service.reserve("ORDER-1", "SKU-1", 8);
        service.addStock("SKU-1", 1);

        service.reserve("ORDER-2", "SKU-1", 1);

        assertEquals(List.of(new Alert("SKU-1", 2), new Alert("SKU-1", 2)), listener.alerts());
    }

    @Test
    void resendingAnOrderDoesNotAlertAgain() {
        service.reserve("ORDER-1", "SKU-1", 5);
        service.addStock("SKU-1", 1);

        service.reserve("ORDER-1", "SKU-1", 5);

        assertEquals(List.of(new Alert("SKU-1", 5)), listener.alerts());
    }

    @Test
    void rejectedReservationDoesNotAlert() {
        assertThrows(InsufficientStockException.class, () -> service.reserve("ORDER-1", "SKU-1", 11));

        assertEquals(List.of(), listener.alerts());
    }

    @Test
    void alertsAreTrackedPerProduct() {
        service.registerProduct("SKU-2", ProductCategory.STANDARD);
        service.addStock("SKU-2", 6);

        service.reserve("ORDER-1", "SKU-1", 5);
        service.reserve("ORDER-2", "SKU-2", 1);

        assertEquals(List.of(new Alert("SKU-1", 5), new Alert("SKU-2", 5)), listener.alerts());
    }

    @Test
    void reservationSucceedsEvenIfTheAlertCannotBeDelivered() {
        channelFailures = 1;

        service.reserve("ORDER-1", "SKU-1", 6);

        assertEquals(4, service.available("SKU-1"));
        assertEquals(List.of(), listener.alerts());
    }

    @Test
    void undeliveredAlertIsRetriedByTheNextReservation() {
        channelFailures = 1;
        service.reserve("ORDER-1", "SKU-1", 5);

        service.reserve("ORDER-2", "SKU-1", 1);
        service.reserve("ORDER-3", "SKU-1", 1);

        assertEquals(List.of(new Alert("SKU-1", 4)), listener.alerts());
    }

    @Test
    void undeliveredAlertIsRetriedUntilItGetsThrough() {
        channelFailures = 3;
        service.reserve("ORDER-1", "SKU-1", 5);
        service.reserve("ORDER-2", "SKU-1", 1);
        service.reserve("ORDER-3", "SKU-1", 1);

        service.reserve("ORDER-4", "SKU-1", 1);

        assertEquals(List.of(new Alert("SKU-1", 2)), listener.alerts());
    }

    @Test
    void undeliveredAlertOfASoldOutProductIsRetriedByARejectedOrder() {
        channelFailures = 1;
        service.reserve("ORDER-1", "SKU-1", 10);

        assertThrows(InsufficientStockException.class, () -> service.reserve("ORDER-2", "SKU-1", 1));
        assertThrows(InsufficientStockException.class, () -> service.reserve("ORDER-3", "SKU-1", 1));

        assertEquals(List.of(new Alert("SKU-1", 0)), listener.alerts());
    }

    @Test
    void rejectedOrderStillFailsTheSameWayWhenTheRetriedAlertFailsAgain() {
        channelFailures = 2;
        service.reserve("ORDER-1", "SKU-1", 10);

        assertThrows(InsufficientStockException.class, () -> service.reserve("ORDER-2", "SKU-1", 1));
    }

    @Test
    void undeliveredAlertIsNotRetriedWhileStockIsNoLongerLow() {
        channelFailures = 1;
        service.reserve("ORDER-1", "SKU-1", 6);
        clock.advance(Duration.ofMinutes(15));

        service.reserve("ORDER-2", "SKU-1", 1);
        assertEquals(List.of(), listener.alerts());

        service.reserve("ORDER-3", "SKU-1", 4);
        assertEquals(List.of(new Alert("SKU-1", 5)), listener.alerts());
    }

    @Test
    void deliveredAlertIsNotRepeatedByRejectedOrders() {
        service.reserve("ORDER-1", "SKU-1", 10);

        assertThrows(InsufficientStockException.class, () -> service.reserve("ORDER-2", "SKU-1", 1));

        assertEquals(List.of(new Alert("SKU-1", 0)), listener.alerts());
    }
}
