package app;

import paylib.PayClient;

public class Service {

    public String single(String key) {
        PayClient client = new PayClient();
        return client.createCharge(key);
    }

    public String batch(String[] keys) {
        PayClient client = new PayClient();
        StringBuilder out = new StringBuilder();
        for (String key : keys) {
            out.append(client.createCharge(key)).append(';');
        }
        return out.toString();
    }
}
