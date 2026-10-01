package com.store.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.store.inventory.api.InsufficientStockException;
import com.store.inventory.api.InventoryService;
import com.store.inventory.api.ProductCategory;
import com.store.inventory.api.Reservation;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ExpirationTest {

    private MutableClock clock;
    private InventoryService service;

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        service = Inventory.create(clock, new RecordingAlertListener());
        service.registerProduct("SKU-1", ProductCategory.STANDARD);
        service.registerProduct("PRE-1", ProductCategory.PRE_ORDER);
        service.registerProduct("FLASH-1", ProductCategory.FLASH_SALE);
        service.addStock("SKU-1", 10);
        service.addStock("PRE-1", 10);
        service.addStock("FLASH-1", 10);
    }

    @ParameterizedTest
    @CsvSource({"SKU-1, PT15M", "PRE-1, PT24H", "FLASH-1, PT5M"})
    void unitsStayReservedUntilTheTimeToPayRunsOut(String sku, Duration timeToPay) {
        service.reserve("ORDER-1", sku, 2);

        clock.advance(timeToPay.minusMillis(1));

        assertEquals(8, service.available(sku));
    }

    @ParameterizedTest
    @CsvSource({"SKU-1, PT15M", "PRE-1, PT24H", "FLASH-1, PT5M"})
    void unitsAreReleasedWhenTheTimeToPayRunsOut(String sku, Duration timeToPay) {
        service.reserve("ORDER-1", sku, 2);

        clock.advance(timeToPay);

        assertEquals(10, service.available(sku));
    }

    @Test
    void releasedUnitsCanBeReservedByAnotherOrder() {
        service.reserve("ORDER-1", "SKU-1", 10);
        assertThrows(InsufficientStockException.class, () -> service.reserve("ORDER-2", "SKU-1", 1));

        clock.advance(Duration.ofMinutes(15));
        service.reserve("ORDER-2", "SKU-1", 10);

        assertEquals(0, service.available("SKU-1"));
    }

    @Test
    void onlyExpiredReservationsAreReleased() {
        service.reserve("ORDER-1", "SKU-1", 2);
        clock.advance(Duration.ofMinutes(10));
        service.reserve("ORDER-2", "SKU-1", 3);

        clock.advance(Duration.ofMinutes(5));

        assertEquals(7, service.available("SKU-1"));
    }

    @Test
    void expiredReservationCannotBeConfirmed() {
        service.reserve("ORDER-1", "SKU-1", 2);
        clock.advance(Duration.ofMinutes(15));

        assertThrows(IllegalStateException.class, () -> service.confirm("ORDER-1"));
        assertEquals(10, service.available("SKU-1"));
    }

    @Test
    void confirmedReservationNeverExpires() {
        service.reserve("ORDER-1", "SKU-1", 2);
        service.confirm("ORDER-1");

        clock.advance(Duration.ofDays(30));

        assertEquals(8, service.available("SKU-1"));
    }

    @Test
    void orderCanReserveAgainAfterItsReservationExpired() {
        service.reserve("ORDER-1", "SKU-1", 2);
        clock.advance(Duration.ofMinutes(15));

        Reservation renewed = service.reserve("ORDER-1", "SKU-1", 2);

        assertEquals(clock.instant().plus(Duration.ofMinutes(15)), renewed.expiresAt());
        assertEquals(8, service.available("SKU-1"));
        service.confirm("ORDER-1");
        assertEquals(8, service.available("SKU-1"));
    }

    @Test
    void reservationKeepsItsTimeToPayWhenTheProductChangesCategory() {
        service.reserve("ORDER-1", "SKU-1", 2);

        service.registerProduct("SKU-1", ProductCategory.FLASH_SALE);
        clock.advance(Duration.ofMinutes(10));

        assertEquals(8, service.available("SKU-1"));
    }
}
