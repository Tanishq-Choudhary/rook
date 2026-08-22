package dev.rook.agent.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.api.Test;

class PriceTableTest {

    @Test
    void costSplitsCachedAndUncachedInputAndOutput() {
        ModelPrice price = new ModelPrice(3_000_000, 300_000, 15_000_000);
        Usage usage = new Usage(1_000_000, 100_000, 400_000);
        assertThat(price.costMicros(usage))
                .isEqualTo(600_000 * 3 + 400_000 * 3 / 10 + 100_000 * 15);
    }

    @Test
    void partialMillionsRoundUpToWholeMicros() {
        ModelPrice price = new ModelPrice(1, 0, 1);
        assertThat(price.costMicros(new Usage(1, 1, 0))).isEqualTo(2);
        assertThat(price.costMicros(Usage.NONE)).isZero();
    }

    @Test
    void unknownModelsAreRejectedInsteadOfPricedAtZero() {
        PriceTable table = PriceTable.of(Map.of("known", ModelPrice.FREE));
        assertThat(table.knows("known")).isTrue();
        assertThat(table.knows("other")).isFalse();
        assertThat(table.costMicros("known", new Usage(5, 5, 0))).isZero();
        assertThatThrownBy(() -> table.costMicros("other", Usage.NONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("other");
        assertThatThrownBy(() -> PriceTable.empty().costMicros("x", Usage.NONE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void negativePricesAreRejected() {
        assertThatThrownBy(() -> new ModelPrice(-1, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Property
    void costNeverDecreasesWhenUsageGrows(
            @ForAll @LongRange(min = 0, max = 5_000_000) long input,
            @ForAll @LongRange(min = 0, max = 5_000_000) long output,
            @ForAll @LongRange(min = 0, max = 1_000_000) long extra) {
        ModelPrice price = new ModelPrice(3_000_000, 300_000, 15_000_000);
        long base = price.costMicros(new Usage(input, output, 0));
        long grown = price.costMicros(new Usage(input + extra, output + extra, 0));
        assertThat(grown).isGreaterThanOrEqualTo(base);
    }
}
