package app;

import paylib.PayClient;

public class Service {

    public String single(String key) {
        PayClient client = new PayClient();
        return client.charge(key).id();
    }

    public String batch(String[] keys) {
        PayClient client = new PayClient();
        StringBuilder out = new StringBuilder();
        for (String key : keys) {
            out.append(client.charge(key).id()).append(';');
        }
        return out.toString();
    }
}
