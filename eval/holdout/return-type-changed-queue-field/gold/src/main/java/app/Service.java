package app;

import queuekit.QueueClient;

public class Service {

    private final QueueClient client;

    public Service(QueueClient client) {
        this.client = client;
    }

    public String single(String key) {
        return client.publish(key).id();
    }

    public String batch(String[] keys) {
        StringBuilder out = new StringBuilder();
        for (String key : keys) {
            out.append(client.publish(key).id()).append(';');
        }
        return out.toString();
    }
}
