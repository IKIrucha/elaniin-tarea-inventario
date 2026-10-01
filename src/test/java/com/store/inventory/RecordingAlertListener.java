package com.store.inventory;

import com.store.inventory.api.StockAlertListener;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Keeps the low stock alerts it receives so tests can assert on them.
 */
public final class RecordingAlertListener implements StockAlertListener {

    public record Alert(String sku, int availableUnits) {
    }

    private final List<Alert> alerts = new CopyOnWriteArrayList<>();

    @Override
    public void onLowStock(String sku, int availableUnits) {
        alerts.add(new Alert(sku, availableUnits));
    }

    public List<Alert> alerts() {
        return List.copyOf(alerts);
    }
}
