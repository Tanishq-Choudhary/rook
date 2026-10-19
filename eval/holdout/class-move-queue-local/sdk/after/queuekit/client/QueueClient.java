package queuekit.client;

public class QueueClient {

    public String publish(String key) {
        return "queued:" + key;
    }

    public String status() {
        return "up";
    }

}
