package app;

import queuekit.QueueClient;
import queuekit.Ref;

public class Service {

    public String single(String key) {
        QueueClient client = new QueueClient();
        return client.publish(new Ref(key));
    }

    public String batch(String[] keys) {
        QueueClient client = new QueueClient();
        StringBuilder out = new StringBuilder();
        for (String key : keys) {
            out.append(client.publish(new Ref(key))).append(';');
        }
        return out.toString();
    }
}
