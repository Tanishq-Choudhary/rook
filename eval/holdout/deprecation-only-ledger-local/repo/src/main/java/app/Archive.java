package app;

import ledgerkit.LedgerClient;

public class Archive {

    public String once(String key) {
        LedgerClient archive = new LedgerClient();
        return archive.record(key);
    }
}
