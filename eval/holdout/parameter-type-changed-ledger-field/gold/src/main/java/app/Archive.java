package app;

import ledgerkit.LedgerClient;
import ledgerkit.Ref;

public class Archive {

    private final LedgerClient client = new LedgerClient();

    public String once(String key) {
        return client.record(new Ref(key));
    }
}
