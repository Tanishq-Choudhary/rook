package app;

import ledgerkit.LedgerClient;

public class Service {

    private final LedgerClient client;

    public Service(LedgerClient client) {
        this.client = client;
    }

    public String single(String key) {
        return client.record(key, true);
    }

    public String batch(String[] keys) {
        StringBuilder out = new StringBuilder();
        for (String key : keys) {
            out.append(client.record(key, true)).append(';');
        }
        return out.toString();
    }
}
