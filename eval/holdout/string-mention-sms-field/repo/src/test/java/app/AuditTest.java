package app;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AuditTest {

    @Test
    void descriptionNamesTheCall() {
        assertEquals("uses dispatch on SmsClient", new Audit().describe());
        assertEquals("dispatch", Audit.EVENT);
    }
}
