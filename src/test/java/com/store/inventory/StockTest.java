package com.store.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.store.inventory.api.InventoryService;
import com.store.inventory.api.OrderLimitExceededException;
import com.store.inventory.api.ProductCategory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StockTest {

    private InventoryService service;

    @BeforeEach
    void setUp() {
        service = Inventory.create(new MutableClock(), new RecordingAlertListener());
        service.registerProduct("SKU-1", ProductCategory.STANDARD);
    }

    @Test
    void registeredProductStartsWithoutStock() {
        assertEquals(0, service.available("SKU-1"));
    }

    @Test
    void unknownProductHasNoAvailableUnits() {
        assertEquals(0, service.available("UNKNOWN"));
    }

    @Test
    void addedStockAccumulates() {
        service.addStock("SKU-1", 10);
        service.addStock("SKU-1", 5);
        assertEquals(15, service.available("SKU-1"));
    }

    @Test
    void stockOfEachProductIsIndependent() {
        service.registerProduct("SKU-2", ProductCategory.STANDARD);
        service.addStock("SKU-1", 10);
        service.addStock("SKU-2", 4);

        service.reserve("ORDER-1", "SKU-1", 3);

        assertEquals(7, service.available("SKU-1"));
        assertEquals(4, service.available("SKU-2"));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void cannotAddZeroOrNegativeStock(int quantity) {
        assertThrows(IllegalArgumentException.class, () -> service.addStock("SKU-1", quantity));
    }

    @Test
    void cannotAddStockToUnregisteredProduct() {
        assertThrows(IllegalArgumentException.class, () -> service.addStock("UNKNOWN", 10));
    }

    @Test
    void registeringAProductAgainKeepsItsStockAndReservations() {
        service.addStock("SKU-1", 10);
        service.reserve("ORDER-1", "SKU-1", 3);

        service.registerProduct("SKU-1", ProductCategory.STANDARD);

        assertEquals(7, service.available("SKU-1"));
    }

    @Test
    void registeringAProductAgainMovesItToTheNewCategory() {
        service.addStock("SKU-1", 10);

        service.registerProduct("SKU-1", ProductCategory.FLASH_SALE);

        assertThrows(OrderLimitExceededException.class, () -> service.reserve("ORDER-1", "SKU-1", 3));
        assertEquals(10, service.available("SKU-1"));
    }
}
