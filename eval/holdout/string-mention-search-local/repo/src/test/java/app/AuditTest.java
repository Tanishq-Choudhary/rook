package app;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AuditTest {

    @Test
    void descriptionNamesTheCall() {
        assertEquals("uses query on SearchClient", new Audit().describe());
        assertEquals("query", Audit.EVENT);
    }
}
