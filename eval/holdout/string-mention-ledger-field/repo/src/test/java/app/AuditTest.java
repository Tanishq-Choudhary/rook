package app;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AuditTest {

    @Test
    void descriptionNamesTheCall() {
        assertEquals("uses record on LedgerClient", new Audit().describe());
        assertEquals("record", Audit.EVENT);
    }
}
