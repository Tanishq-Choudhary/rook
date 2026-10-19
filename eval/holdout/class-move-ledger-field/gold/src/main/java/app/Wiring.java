package app;

import ledgerkit.client.LedgerClient;

public class Wiring {

    public static Service service() {
        return new Service(new LedgerClient());
    }
}
