package app;

import ledgerkit.LedgerClient;

public class Service {

    public String single(String key) {
        LedgerClient client = new LedgerClient();
        return client.record(key, true);
    }

    public String batch(String[] keys) {
        LedgerClient client = new LedgerClient();
        StringBuilder out = new StringBuilder();
        for (String key : keys) {
            out.append(client.record(key, true)).append(';');
        }
        return out.toString();
    }
}
