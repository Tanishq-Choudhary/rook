package app;

import paylib.PayClient;
import paylib.Ref;

public class Service {

    private final PayClient client;

    public Service(PayClient client) {
        this.client = client;
    }

    public String single(String key) {
        return client.charge(new Ref(key));
    }

    public String batch(String[] keys) {
        StringBuilder out = new StringBuilder();
        for (String key : keys) {
            out.append(client.charge(new Ref(key))).append(';');
        }
        return out.toString();
    }
}
