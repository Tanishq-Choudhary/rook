package app;

import ledgerkit.client.LedgerClient;

public class Service {

    public String single(String key) {
        LedgerClient client = new LedgerClient();
        return client.record(key);
    }

    public String batch(String[] keys) {
        LedgerClient client = new LedgerClient();
        StringBuilder out = new StringBuilder();
        for (String key : keys) {
            out.append(client.record(key)).append(';');
        }
        return out.toString();
    }
}
