package app;

import queuekit.QueueClient;
import queuekit.Ref;

public class Archive {

    public String once(String key) {
        QueueClient archive = new QueueClient();
        return archive.publish(new Ref(key));
    }
}
