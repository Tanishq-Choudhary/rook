package app;

import queuekit.client.QueueClient;

public class Archive {

    private final QueueClient client = new QueueClient();

    public String once(String key) {
        return client.publish(key);
    }
}
