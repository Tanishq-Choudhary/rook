package app;

import queuekit.client.QueueClient;

public class Archive {

    public String once(String key) {
        QueueClient archive = new QueueClient();
        return archive.publish(key);
    }
}
