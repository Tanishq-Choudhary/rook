package app;

import queuekit.QueueClient;
import queuekit.Ref;

public class Archive {

    private final QueueClient client = new QueueClient();

    public String once(String key) {
        return client.publish(new Ref(key));
    }
}
