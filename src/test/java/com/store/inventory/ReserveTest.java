package com.store.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.store.inventory.api.InsufficientStockException;
import com.store.inventory.api.InventoryService;
import com.store.inventory.api.OrderLimitExceededException;
import com.store.inventory.api.ProductCategory;
import com.store.inventory.api.Reservation;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ReserveTest {

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

    @Test
    void reservationDescribesTheOrder() {
        Reservation reservation = service.reserve("ORDER-1", "SKU-1", 3);

        assertEquals(new Reservation("ORDER-1", "SKU-1", 3, clock.instant().plus(Duration.ofMinutes(15))), reservation);
    }

    @ParameterizedTest
    @CsvSource({"SKU-1, PT15M", "PRE-1, PT24H", "FLASH-1, PT5M"})
    void timeToPayDependsOnTheCategory(String sku, Duration timeToPay) {
        Reservation reservation = service.reserve("ORDER-1", sku, 1);

        assertEquals(clock.instant().plus(timeToPay), reservation.expiresAt());
    }

    @Test
    void reservedUnitsCannotBeReservedByAnotherOrder() {
        service.reserve("ORDER-1", "SKU-1", 8);

        assertThrows(InsufficientStockException.class, () -> service.reserve("ORDER-2", "SKU-1", 3));
        assertEquals(2, service.available("SKU-1"));
    }

    @Test
    void canReserveEveryAvailableUnit() {
        service.reserve("ORDER-1", "SKU-1", 10);

        assertEquals(0, service.available("SKU-1"));
    }

    @Test
    void cannotReserveUnknownProduct() {
        assertThrows(InsufficientStockException.class, () -> service.reserve("ORDER-1", "UNKNOWN", 1));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void cannotReserveZeroOrNegativeUnits(int quantity) {
        assertThrows(IllegalArgumentException.class, () -> service.reserve("ORDER-1", "SKU-1", quantity));
        assertEquals(10, service.available("SKU-1"));
    }

    @Test
    void flashSaleAllowsUpToTwoUnitsPerOrder() {
        service.reserve("ORDER-1", "FLASH-1", 2);

        assertEquals(8, service.available("FLASH-1"));
    }

    @Test
    void flashSaleRejectsMoreThanTwoUnitsPerOrder() {
        assertThrows(OrderLimitExceededException.class, () -> service.reserve("ORDER-1", "FLASH-1", 3));
        assertEquals(10, service.available("FLASH-1"));
    }

    @Test
    void orderLimitIsReportedEvenWhenStockIsAlsoInsufficient() {
        service.reserve("ORDER-1", "FLASH-1", 2);
        service.reserve("ORDER-2", "FLASH-1", 2);
        service.reserve("ORDER-3", "FLASH-1", 2);
        service.reserve("ORDER-4", "FLASH-1", 2);

        assertThrows(OrderLimitExceededException.class, () -> service.reserve("ORDER-5", "FLASH-1", 3));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SKU-1", "PRE-1"})
    void categoriesWithoutLimitAllowAnyQuantity(String sku) {
        service.reserve("ORDER-1", sku, 10);

        assertEquals(0, service.available(sku));
    }

    @Test
    void resendingAnOrderDoesNotReserveTwice() {
        Reservation first = service.reserve("ORDER-1", "SKU-1", 3);
        clock.advance(Duration.ofSeconds(30));

        Reservation resent = service.reserve("ORDER-1", "SKU-1", 3);

        assertEquals(first, resent);
        assertEquals(7, service.available("SKU-1"));
    }

    @Test
    void resendingAnOrderSucceedsEvenWhenNoStockIsLeft() {
        Reservation first = service.reserve("ORDER-1", "SKU-1", 10);

        assertEquals(first, service.reserve("ORDER-1", "SKU-1", 10));
    }

    @Test
    void resendingAPaidOrderDoesNotReserveAgain() {
        Reservation first = service.reserve("ORDER-1", "SKU-1", 3);
        service.confirm("ORDER-1");
        clock.advance(Duration.ofHours(1));

        assertEquals(first, service.reserve("ORDER-1", "SKU-1", 3));
        assertEquals(7, service.available("SKU-1"));
    }

    @Test
    void resendingAnOrderWithDifferentContentIsRejected() {
        service.reserve("ORDER-1", "SKU-1", 3);

        IllegalStateException otherQuantity =
                assertThrows(IllegalStateException.class, () -> service.reserve("ORDER-1", "SKU-1", 4));
        IllegalStateException otherProduct =
                assertThrows(IllegalStateException.class, () -> service.reserve("ORDER-1", "PRE-1", 3));

        assertEquals("Order ORDER-1 already reserved 3 x SKU-1, cannot reserve 4 x SKU-1", otherQuantity.getMessage());
        assertEquals("Order ORDER-1 already reserved 3 x SKU-1, cannot reserve 3 x PRE-1", otherProduct.getMessage());
        assertEquals(7, service.available("SKU-1"));
        assertEquals(10, service.available("PRE-1"));
    }

    @Test
    void failedReservationCanBeRetriedOnceThereIsStock() {
        assertThrows(InsufficientStockException.class, () -> service.reserve("ORDER-1", "SKU-1", 11));
        service.addStock("SKU-1", 1);

        service.reserve("ORDER-1", "SKU-1", 11);

        assertEquals(0, service.available("SKU-1"));
    }
}
