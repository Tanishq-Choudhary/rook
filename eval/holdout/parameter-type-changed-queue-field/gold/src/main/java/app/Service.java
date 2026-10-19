package app;

import queuekit.QueueClient;
import queuekit.Ref;

public class Service {

    private final QueueClient client;

    public Service(QueueClient client) {
        this.client = client;
    }

    public String single(String key) {
        return client.publish(new Ref(key));
    }

    public String batch(String[] keys) {
        StringBuilder out = new StringBuilder();
        for (String key : keys) {
            out.append(client.publish(new Ref(key))).append(';');
        }
        return out.toString();
    }
}
