package app;

import ledgerkit.LedgerClient;

public class Archive {

    private final LedgerClient client = new LedgerClient();

    public String once(String key) {
        return client.record(key, true);
    }
}
