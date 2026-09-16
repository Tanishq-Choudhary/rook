package app;

import paylib.client.PayClient;

public class Archive {

    public String once(String key) {
        PayClient archive = new PayClient();
        return archive.charge(key);
    }
}
