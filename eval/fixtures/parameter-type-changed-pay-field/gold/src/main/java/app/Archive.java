package app;

import paylib.PayClient;
import paylib.Ref;

public class Archive {

    private final PayClient client = new PayClient();

    public String once(String key) {
        return client.charge(new Ref(key));
    }
}
