package com.store.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.store.inventory.api.InsufficientStockException;
import com.store.inventory.api.InventoryService;
import com.store.inventory.api.ProductCategory;
import com.store.inventory.api.Reservation;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Timeout;

/**
 * High season: many customers going after the same product at the same time.
 */
@Timeout(30)
class ConcurrencyTest {

    private static final int THREADS = 16;

    private RecordingAlertListener listener;
    private InventoryService service;
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        listener = new RecordingAlertListener();
        service = Inventory.create(new MutableClock(), listener);
        service.registerProduct("SKU-1", ProductCategory.STANDARD);
        executor = Executors.newFixedThreadPool(THREADS);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @RepeatedTest(20)
    void neverReservesMoreUnitsThanThereAreInStock() throws Exception {
        service.addStock("SKU-1", 50);

        List<Boolean> reserved = runAtOnce(IntStream.range(0, 300)
                .mapToObj(i -> (Callable<Boolean>) () -> tryReserve("ORDER-" + i, 1))
                .toList());

        assertEquals(50, reserved.stream().filter(wasReserved -> wasReserved).count());
        assertEquals(0, service.available("SKU-1"));
    }

    @RepeatedTest(20)
    void ordersResentAtTheSameTimeReserveOnlyOnce() throws Exception {
        service.addStock("SKU-1", 50);

        List<Reservation> reservations = runAtOnce(IntStream.range(0, 300)
                .mapToObj(i -> (Callable<Reservation>) () -> service.reserve("ORDER-" + i % 10, "SKU-1", 2))
                .toList());

        assertEquals(10, new HashSet<>(reservations).size());
        assertEquals(30, service.available("SKU-1"));
    }

    @RepeatedTest(20)
    void confirmingWhileOthersReserveNeverSellsMoreThanTheStock() throws Exception {
        service.addStock("SKU-1", 50);

        List<Boolean> sold = runAtOnce(IntStream.range(0, 300)
                .mapToObj(i -> (Callable<Boolean>) () -> {
                    if (!tryReserve("ORDER-" + i, 1)) {
                        return false;
                    }
                    service.confirm("ORDER-" + i);
                    return true;
                })
                .toList());

        assertEquals(50, sold.stream().filter(wasSold -> wasSold).count());
        assertEquals(0, service.available("SKU-1"));
    }

    @RepeatedTest(20)
    void alertsOnlyOnceWhenManyOrdersCrossTheThresholdTogether() throws Exception {
        service.addStock("SKU-1", 50);

        runAtOnce(IntStream.range(0, 300)
                .mapToObj(i -> (Callable<Boolean>) () -> tryReserve("ORDER-" + i, 1))
                .toList());

        assertEquals(List.of(new RecordingAlertListener.Alert("SKU-1", 5)), listener.alerts());
    }

    private boolean tryReserve(String orderId, int quantity) {
        try {
            service.reserve(orderId, "SKU-1", quantity);
            return true;
        } catch (InsufficientStockException e) {
            return false;
        }
    }

    /** Runs the tasks on all threads, released together so they actually contend. */
    private <T> List<T> runAtOnce(List<Callable<T>> tasks) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (Callable<T> task : tasks) {
            futures.add(executor.submit(() -> {
                start.await();
                return task.call();
            }));
        }
        start.countDown();

        List<T> results = new ArrayList<>();
        for (Future<T> future : futures) {
            results.add(future.get(10, TimeUnit.SECONDS));
        }
        return results;
    }
}
