package app;

import ledgerkit.LedgerClient;
import ledgerkit.Ref;

public class Service {

    public String single(String key) {
        LedgerClient client = new LedgerClient();
        return client.record(new Ref(key));
    }

    public String batch(String[] keys) {
        LedgerClient client = new LedgerClient();
        StringBuilder out = new StringBuilder();
        for (String key : keys) {
            out.append(client.record(new Ref(key))).append(';');
        }
        return out.toString();
    }
}
