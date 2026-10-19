package app;

import ledgerkit.LedgerClient;

public class Wiring {

    public static Service service() {
        return new Service(new LedgerClient());
    }
}
