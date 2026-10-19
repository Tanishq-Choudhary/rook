package queuekit;

public class QueueClient {

    public String publish(String key) {
        return "queued:" + key;
    }

    public String status() {
        return "up";
    }

    public String listTopics() {
        return "none";
    }

}
