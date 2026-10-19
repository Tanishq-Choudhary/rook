package app;

import queuekit.QueueClient;

public class Archive {

    public String once(String key) {
        QueueClient archive = new QueueClient();
        return archive.enqueue(key);
    }
}
