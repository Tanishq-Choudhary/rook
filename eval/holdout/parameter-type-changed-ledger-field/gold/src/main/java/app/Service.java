package app;

import ledgerkit.LedgerClient;
import ledgerkit.Ref;

public class Service {

    private final LedgerClient client;

    public Service(LedgerClient client) {
        this.client = client;
    }

    public String single(String key) {
        return client.record(new Ref(key));
    }

    public String batch(String[] keys) {
        StringBuilder out = new StringBuilder();
        for (String key : keys) {
            out.append(client.record(new Ref(key))).append(';');
        }
        return out.toString();
    }
}
