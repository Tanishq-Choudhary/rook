package app;

import paylib.PayClient;
import paylib.Ref;

public class Service {

    public String single(String key) {
        PayClient client = new PayClient();
        return client.charge(new Ref(key));
    }

    public String batch(String[] keys) {
        PayClient client = new PayClient();
        StringBuilder out = new StringBuilder();
        for (String key : keys) {
            out.append(client.charge(new Ref(key))).append(';');
        }
        return out.toString();
    }
}
