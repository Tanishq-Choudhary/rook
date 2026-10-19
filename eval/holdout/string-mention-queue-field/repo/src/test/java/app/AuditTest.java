package app;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AuditTest {

    @Test
    void descriptionNamesTheCall() {
        assertEquals("uses publish on QueueClient", new Audit().describe());
        assertEquals("publish", Audit.EVENT);
    }
}
