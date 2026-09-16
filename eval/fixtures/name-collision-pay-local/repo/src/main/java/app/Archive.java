package app;

import paylib.PayClient;

public class Archive {

    public String once(String key) {
        PayClient archive = new PayClient();
        return archive.charge(key);
    }
}
