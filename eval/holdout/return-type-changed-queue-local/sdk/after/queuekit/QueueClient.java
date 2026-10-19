package queuekit;

public class QueueClient {

    public Receipt publish(String key) {
        return new Receipt("queued:" + key);
    }

    public String status() {
        return "up";
    }

}
