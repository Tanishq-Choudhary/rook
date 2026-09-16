package app;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AuditTest {

    @Test
    void descriptionNamesTheCall() {
        assertEquals("uses lookup on GeoClient", new Audit().describe());
        assertEquals("lookup", Audit.EVENT);
    }
}
