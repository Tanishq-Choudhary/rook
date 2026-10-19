package queuekit;

public class QueueClient {

    public String publish(String key, boolean audit) {
        return "queued:" + key;
    }

    public String status() {
        return "up";
    }

}
