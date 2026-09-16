package app;

import paylib.PayClient;

public class Archive {

    private final PayClient client = new PayClient();

    public String once(String key) {
        return client.charge(key, true);
    }
}
