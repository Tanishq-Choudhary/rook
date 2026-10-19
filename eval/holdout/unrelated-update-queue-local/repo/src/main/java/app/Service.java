package app;

import queuekit.QueueClient;

public class Service {

    public String single(String key) {
        QueueClient client = new QueueClient();
        return client.publish(key);
    }

    public String batch(String[] keys) {
        QueueClient client = new QueueClient();
        StringBuilder out = new StringBuilder();
        for (String key : keys) {
            out.append(client.publish(key)).append(';');
        }
        return out.toString();
    }
}
