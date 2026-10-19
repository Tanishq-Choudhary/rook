package app;

import queuekit.client.QueueClient;

public class Wiring {

    public static Service service() {
        return new Service(new QueueClient());
    }
}
