package app;

import ledgerkit.client.LedgerClient;

public class Archive {

    public String once(String key) {
        LedgerClient archive = new LedgerClient();
        return archive.record(key);
    }
}
