package app;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ServiceTest {

    @Test
    void singleCallUsesTheKey() {
        assertEquals("recorded:a", Wiring.service().single("a"));
    }

    @Test
    void batchJoinsEveryResult() {
        assertEquals("recorded:a;recorded:b;", Wiring.service().batch(new String[] {"a", "b"}));
    }
}
