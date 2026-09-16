package app;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AuditTest {

    @Test
    void descriptionNamesTheCall() {
        assertEquals("uses upload on BlobClient", new Audit().describe());
        assertEquals("upload", Audit.EVENT);
    }
}
