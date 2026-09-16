package app;

import paylib.PayClient;

public class Wiring {

    public static Service service() {
        return new Service(new PayClient());
    }
}
