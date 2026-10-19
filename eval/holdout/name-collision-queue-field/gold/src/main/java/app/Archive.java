package app;

import queuekit.QueueClient;

public class Archive {

    private final QueueClient client = new QueueClient();

    public String once(String key) {
        return client.enqueue(key);
    }
}
