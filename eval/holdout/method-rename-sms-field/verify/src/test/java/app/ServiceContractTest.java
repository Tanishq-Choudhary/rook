package app;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ServiceContractTest {

    @Test
    void archiveReturnsTheTaggedKey() {
        assertEquals("dispatched:doc-7", new Archive().once("doc-7"));
    }

    @Test
    void anEmptyBatchIsEmpty() {
        assertEquals("", Wiring.service().batch(new String[0]));
    }

    @Test
    void serviceAndArchiveAgree() {
        assertEquals(Wiring.service().single("k"), new Archive().once("k"));
    }
}
