package app;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AuditTest {

    @Test
    void descriptionNamesTheCall() {
        assertEquals("uses charge on PayClient", new Audit().describe());
        assertEquals("charge", Audit.EVENT);
    }
}
