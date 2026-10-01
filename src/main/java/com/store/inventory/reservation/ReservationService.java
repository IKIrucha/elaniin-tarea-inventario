package com.store.inventory.reservation;

import com.store.inventory.api.InsufficientStockException;
import com.store.inventory.api.InventoryService;
import com.store.inventory.api.OrderLimitExceededException;
import com.store.inventory.api.ProductCategory;
import com.store.inventory.api.Reservation;
import com.store.inventory.api.StockAlertListener;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * In-memory {@link InventoryService}.
 *
 * <p>All state is guarded by a single lock, so each operation is atomic and units can never be
 * oversold. Reservations are not released by a timer: every operation first releases the ones whose
 * payment window has passed according to the {@link Clock}, so what callers observe depends only on
 * the time of their call.
 */
public final class ReservationService implements InventoryService {

    private static final Logger LOG = System.getLogger(ReservationService.class.getName());

    private final Clock clock;
    private final Function<ProductCategory, ReservationPolicy> policies;
    private final StockAlertListener alertListener;
    private final int lowStockThreshold;

    private final Lock lock = new ReentrantLock();
    private final Map<String, ProductStock> products = new HashMap<>();
    /** Reservations waiting for payment, by order id. */
    private final Map<String, Reservation> pending = new HashMap<>();
    /** Paid reservations, by order id. Kept so that a late retry of a paid order does not reserve again. */
    private final Map<String, Reservation> confirmed = new HashMap<>();
    /** Every reservation ever made that has not reached its expiry time yet, soonest first. */
    private final Queue<Reservation> expirations = new PriorityQueue<>(Comparator.comparing(Reservation::expiresAt));

    public ReservationService(
            Clock clock,
            Function<ProductCategory, ReservationPolicy> policies,
            StockAlertListener alertListener,
            int lowStockThreshold) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.policies = Objects.requireNonNull(policies, "policies");
        this.alertListener = Objects.requireNonNull(alertListener, "alertListener");
        this.lowStockThreshold = lowStockThreshold;
    }

    /**
     * Registering a known product again changes its category for future reservations and keeps its
     * stock, so a product can move in and out of a seasonal category.
     */
    @Override
    public void registerProduct(String sku, ProductCategory category) {
        Objects.requireNonNull(sku, "sku");
        Objects.requireNonNull(category, "category");
        lock.lock();
        try {
            ProductStock product = products.get(sku);
            if (product == null) {
                products.put(sku, new ProductStock(sku, category));
            } else {
                product.changeCategory(category);
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void addStock(String sku, int quantity) {
        Objects.requireNonNull(sku, "sku");
        requirePositive(quantity);
        lock.lock();
        try {
            ProductStock product = products.get(sku);
            if (product == null) {
                throw new IllegalArgumentException("Product " + sku + " is not registered");
            }
            product.addStock(quantity);
        } finally {
            lock.unlock();
        }
    }

    /**
     * The app resends an order until it gets an answer, so reserving is idempotent per order id:
     * while the order has a pending or paid reservation, that reservation is returned and no more
     * units are taken. Once a reservation has expired, the same order id reserves from scratch.
     *
     * <p>A low stock alert that the listener failed to deliver is sent again by the next order for
     * the product, accepted or not, until it gets through or the product is restocked.
     *
     * @throws IllegalStateException if the order already has a reservation for another product or quantity
     */
    @Override
    public Reservation reserve(String orderId, String sku, int quantity) {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(sku, "sku");
        requirePositive(quantity);

        OptionalInt lowStockUnits = OptionalInt.empty();
        try {
            lock.lock();
            try {
                Instant now = clock.instant();
                releaseExpired(now);

                Reservation existing = pending.containsKey(orderId) ? pending.get(orderId) : confirmed.get(orderId);
                if (existing != null) {
                    requireSameRequest(existing, sku, quantity);
                    return existing;
                }

                ProductStock product = products.get(sku);
                if (product == null) {
                    throw new InsufficientStockException(sku, quantity, 0);
                }
                ReservationPolicy policy = policies.apply(product.category());
                try {
                    requireWithinLimit(policy, sku, quantity);
                    product.hold(quantity);
                } catch (OrderLimitExceededException | InsufficientStockException rejected) {
                    // A sold-out product only gets rejected orders, and those must still be able to
                    // retry an alert that did not reach purchasing.
                    lowStockUnits = product.claimUndeliveredLowStockAlert(lowStockThreshold);
                    throw rejected;
                }

                Reservation reservation = new Reservation(orderId, sku, quantity, now.plus(policy.paymentWindow()));
                pending.put(orderId, reservation);
                expirations.add(reservation);
                lowStockUnits = product.claimLowStockAlert(lowStockThreshold);
                return reservation;
            } finally {
                lock.unlock();
            }
        } finally {
            // Outside the lock: a slow alert channel must not hold up other customers.
            if (lowStockUnits.isPresent()) {
                alertLowStock(sku, lowStockUnits.getAsInt());
            }
        }
    }

    @Override
    public void confirm(String orderId) {
        Objects.requireNonNull(orderId, "orderId");
        lock.lock();
        try {
            releaseExpired(clock.instant());
            Reservation reservation = pending.remove(orderId);
            if (reservation == null) {
                throw new IllegalStateException(confirmed.containsKey(orderId)
                        ? "Order " + orderId + " is already confirmed"
                        : "Order " + orderId + " has no active reservation: it was never reserved or its time to pay ran out");
            }
            products.get(reservation.sku()).sell(reservation.quantity());
            confirmed.put(orderId, reservation);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int available(String sku) {
        Objects.requireNonNull(sku, "sku");
        lock.lock();
        try {
            releaseExpired(clock.instant());
            ProductStock product = products.get(sku);
            return product == null ? 0 : product.available();
        } finally {
            lock.unlock();
        }
    }

    /** A reservation is active strictly before its expiry time; at that instant it is already released. */
    private void releaseExpired(Instant now) {
        while (!expirations.isEmpty() && !expirations.peek().expiresAt().isAfter(now)) {
            Reservation expired = expirations.remove();
            // Confirmed reservations stay in the queue until their expiry time and are skipped here.
            if (pending.remove(expired.orderId(), expired)) {
                products.get(expired.sku()).release(expired.quantity());
            }
        }
    }

    private void alertLowStock(String sku, int availableUnits) {
        try {
            alertListener.onLowStock(sku, availableUnits);
        } catch (RuntimeException e) {
            // A broken alert channel must not change the outcome of the customer's order.
            LOG.log(Level.ERROR, "Low stock alert for " + sku + " (" + availableUnits
                    + " available) was not delivered, it will be retried with the next order for the product", e);
            lock.lock();
            try {
                products.get(sku).lowStockAlertNotDelivered();
            } finally {
                lock.unlock();
            }
        }
    }

    private static void requireWithinLimit(ReservationPolicy policy, String sku, int quantity) {
        OptionalInt limit = policy.maxUnitsPerOrder();
        if (limit.isPresent() && quantity > limit.getAsInt()) {
            throw new OrderLimitExceededException(sku, quantity, limit.getAsInt());
        }
    }

    private static void requireSameRequest(Reservation existing, String sku, int quantity) {
        if (!existing.sku().equals(sku) || existing.quantity() != quantity) {
            throw new IllegalStateException("Order " + existing.orderId() + " already reserved "
                    + existing.quantity() + " x " + existing.sku() + ", cannot reserve " + quantity + " x " + sku);
        }
    }

    private static void requirePositive(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive: " + quantity);
        }
    }
}
