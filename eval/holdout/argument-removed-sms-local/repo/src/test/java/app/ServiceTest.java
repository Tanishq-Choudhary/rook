package app;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ServiceTest {

    @Test
    void singleCallUsesTheKey() {
        assertEquals("dispatched:a", Wiring.service().single("a"));
    }

    @Test
    void batchJoinsEveryResult() {
        assertEquals("dispatched:a;dispatched:b;", Wiring.service().batch(new String[] {"a", "b"}));
    }
}
