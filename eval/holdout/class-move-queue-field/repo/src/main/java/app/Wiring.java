package app;

import queuekit.QueueClient;

public class Wiring {

    public static Service service() {
        return new Service(new QueueClient());
    }
}
