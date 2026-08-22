package dev.rook.agent.model;

import java.util.Map;

public final class PriceTable {

    private final Map<String, ModelPrice> prices;

    private PriceTable(Map<String, ModelPrice> prices) {
        this.prices = Map.copyOf(prices);
    }

    public static PriceTable of(Map<String, ModelPrice> prices) {
        return new PriceTable(prices);
    }

    public static PriceTable empty() {
        return new PriceTable(Map.of());
    }

    public boolean knows(String model) {
        return prices.containsKey(model);
    }

    public long costMicros(String model, Usage usage) {
        ModelPrice price = prices.get(model);
        if (price == null) {
            throw new IllegalArgumentException("no price configured for model " + model);
        }
        return price.costMicros(usage);
    }
}
