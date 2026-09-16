package app;

import paylib.PayClient;
import paylib.Ref;

public class Archive {

    public String once(String key) {
        PayClient archive = new PayClient();
        return archive.charge(new Ref(key));
    }
}
