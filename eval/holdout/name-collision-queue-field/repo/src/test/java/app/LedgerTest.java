package app;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class LedgerTest {

    @Test
    void ledgerLinesKeepTheirOwnPrefix() {
        assertEquals("ledger:x", new Report().line(new Ledger(), "x"));
    }
}
