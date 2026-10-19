package app;

import ledgerkit.client.LedgerClient;

public class Archive {

    private final LedgerClient client = new LedgerClient();

    public String once(String key) {
        return client.record(key);
    }
}
