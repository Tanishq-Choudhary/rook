package app;

import ledgerkit.LedgerClient;

public class Service {

    public String single(String key) {
        LedgerClient client = new LedgerClient();
        return client.record(key).id();
    }

    public String batch(String[] keys) {
        LedgerClient client = new LedgerClient();
        StringBuilder out = new StringBuilder();
        for (String key : keys) {
            out.append(client.record(key).id()).append(';');
        }
        return out.toString();
    }
}
