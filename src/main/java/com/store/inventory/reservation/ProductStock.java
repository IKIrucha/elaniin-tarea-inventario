package com.store.inventory.reservation;

import com.store.inventory.api.InsufficientStockException;
import com.store.inventory.api.ProductCategory;
import java.util.OptionalInt;

/**
 * Unit counts of a single product. Not thread-safe: {@link ReservationService} guards every access.
 */
final class ProductStock {

    private final String sku;
    private ProductCategory category;
    /** Units in the warehouse that have not been sold, whether held by a reservation or not. */
    private int onHand;
    /** Units held by reservations that are still waiting for payment. */
    private int held;
    private LowStockAlert lowStockAlert = LowStockAlert.NOT_SENT;

    private enum LowStockAlert {
        NOT_SENT,
        SENT,
        /** It was sent but the channel failed, so purchasing still does not know. */
        UNDELIVERED
    }

    ProductStock(String sku, ProductCategory category) {
        this.sku = sku;
        this.category = category;
    }

    ProductCategory category() {
        return category;
    }

    void changeCategory(ProductCategory category) {
        this.category = category;
    }

    int available() {
        return onHand - held;
    }

    void addStock(int quantity) {
        onHand = Math.addExact(onHand, quantity);
        // Purchasing asked not to be told twice about the same shortage; a restock starts a new one.
        lowStockAlert = LowStockAlert.NOT_SENT;
    }

    void hold(int quantity) {
        if (quantity > available()) {
            throw new InsufficientStockException(sku, quantity, available());
        }
        held += quantity;
    }

    void release(int quantity) {
        held -= quantity;
    }

    void sell(int quantity) {
        held -= quantity;
        onHand -= quantity;
    }

    /**
     * Returns the available units if purchasing has to be alerted now, and remembers that it was,
     * so the alert is not raised again until the product is restocked.
     */
    OptionalInt claimLowStockAlert(int threshold) {
        if (lowStockAlert == LowStockAlert.SENT || available() > threshold) {
            return OptionalInt.empty();
        }
        lowStockAlert = LowStockAlert.SENT;
        return OptionalInt.of(available());
    }

    /**
     * Same as {@link #claimLowStockAlert(int)}, but only for an alert that failed to be delivered before.
     */
    OptionalInt claimUndeliveredLowStockAlert(int threshold) {
        return lowStockAlert == LowStockAlert.UNDELIVERED ? claimLowStockAlert(threshold) : OptionalInt.empty();
    }

    void lowStockAlertNotDelivered() {
        // If the product was restocked while the alert was on its way, that alert no longer matters.
        if (lowStockAlert == LowStockAlert.SENT) {
            lowStockAlert = LowStockAlert.UNDELIVERED;
        }
    }
}
