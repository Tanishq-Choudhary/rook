package app;

import ledgerkit.LedgerClient;
import ledgerkit.Ref;

public class Archive {

    public String once(String key) {
        LedgerClient archive = new LedgerClient();
        return archive.record(new Ref(key));
    }
}
