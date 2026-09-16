package app;

import paylib.client.PayClient;

public class Wiring {

    public static Service service() {
        return new Service(new PayClient());
    }
}
