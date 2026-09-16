package app;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AuditTest {

    @Test
    void descriptionNamesTheCall() {
        assertEquals("uses send on MailClient", new Audit().describe());
        assertEquals("send", Audit.EVENT);
    }
}
