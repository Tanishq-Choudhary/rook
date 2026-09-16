package app;

import paylib.PayClient;

public class Service {

    private final PayClient client;

    public Service(PayClient client) {
        this.client = client;
    }

    public String single(String key) {
        return client.charge(key, true);
    }

    public String batch(String[] keys) {
        StringBuilder out = new StringBuilder();
        for (String key : keys) {
            out.append(client.charge(key, true)).append(';');
        }
        return out.toString();
    }
}
