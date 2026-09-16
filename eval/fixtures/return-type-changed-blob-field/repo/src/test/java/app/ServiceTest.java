package app;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ServiceTest {

    @Test
    void singleCallUsesTheKey() {
        assertEquals("stored:a", Wiring.service().single("a"));
    }

    @Test
    void batchJoinsEveryResult() {
        assertEquals("stored:a;stored:b;", Wiring.service().batch(new String[] {"a", "b"}));
    }
}
